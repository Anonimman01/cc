package com.example.aiblocker

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import kotlin.math.abs

class AiBlockerService : AccessibilityService() {

    /** BLOCK: сильное совпадение (шаблон, канал, вердикт ИИ). SOFT: только слабые признаки, лишь плашка. */
    private enum class Decision { BLOCK, SOFT, PASS, NONE }

    private class Hit(val rect: Rect, val container: AccessibilityNodeInfo, val key: String, val auto: Boolean)

    // ---- правила (перечитываются при изменении настроек) ----
    private var strong: List<Regex> = emptyList()
    private var weak: List<Regex> = emptyList()
    private var channels: List<String> = emptyList()
    private var allow: List<String> = emptyList()
    private var llmPackages: Set<String> = emptySet()
    private var llmOn = false
    private var autoHide = true
    private var watchOn = true
    private var closeOpen = true
    private val weakThreshold = 2

    private val ignoredPackages = setOf(
        "com.example.aiblocker", "com.android.systemui", "com.android.settings"
    )

    // ---- Groq: кэш, очередь, обучение ----
    private val verdicts = object : LinkedHashMap<String, Boolean>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>) = size > 2000
    }
    private val pending = LinkedHashMap<String, String>()
    private val inflight = HashSet<String>()
    private val channelHits = HashMap<String, Int>()
    private val executor = Executors.newSingleThreadExecutor()
    private var pauseUntil = 0L

    // ---- страница видео ----
    private var watchScheduled = false
    private var lastProbeAt = 0L
    private var lastWatchAt = 0L
    private var lastWatchKey: String? = null

    // ---- автоскрытие через меню YouTube ----
    private val hideDone = LinkedHashSet<String>()
    private var hideBusy = false
    private var hideGen = 0
    private var lastHideAt = 0L
    private var lastFailToastAt = 0L
    private var lastBackAt = 0L
    private var lastOpenCheckAt = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val scanRunnable = Runnable { scanScreen() }
    private val flushRunnable = Runnable { flush() }
    private lateinit var wm: WindowManager
    private val overlays = mutableListOf<TextView>()
    private val maxOverlays = 8
    private var lastPkg = ""

    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> reloadRules() }

    override fun onServiceConnected() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        Rules.migrate(this)
        reloadRules()
        getSharedPreferences(Rules.PREFS, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefsListener)
    }

    private fun toRegex(lines: List<String>) = lines.mapNotNull {
        runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull()
    }

    private fun reloadRules() {
        strong = toRegex(Rules.lines(Rules.get(this, Rules.K_STRONG)))
        weak = toRegex(Rules.lines(Rules.get(this, Rules.K_WEAK)))
        channels = (Rules.lines(Rules.get(this, Rules.K_CHANNELS)) +
            Rules.lines(Rules.get(this, Rules.K_LEARNED))).map { it.lowercase() }
        allow = Rules.lines(Rules.get(this, Rules.K_ALLOW)).map { it.lowercase() }
        llmPackages = Rules.lines(Rules.get(this, Rules.K_PACKAGES)).toSet()
        llmOn = Rules.get(this, Rules.K_LLM_ON) == "1" &&
            Rules.get(this, Rules.K_API_KEY).isNotBlank()
        autoHide = Rules.get(this, Rules.K_AUTO_HIDE) == "1"
        watchOn = Rules.get(this, Rules.K_WATCH) == "1"
        closeOpen = Rules.get(this, Rules.K_CLOSE) == "1"
        verdicts.clear() // правила/порог могли поменяться
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg in ignoredPackages) return
        handler.removeCallbacks(scanRunnable)
        handler.postDelayed(scanRunnable, 80)
    }

    override fun onInterrupt() = clearOverlays()

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        handler.removeCallbacksAndMessages(null)
        getSharedPreferences(Rules.PREFS, Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
        executor.shutdown()
        clearOverlays()
        return super.onUnbind(intent)
    }

    // ---------------- решение по карточке ----------------

    /** 2 = сильное совпадение или канал из списка, 1 = достаточно слабых признаков, 0 = нет. */
    private fun localLevel(text: String, low: String): Int {
        if (strong.any { it.containsMatchIn(text) }) return 2
        if (channels.any { low.contains(it) }) return 2
        return if (weak.count { it.containsMatchIn(text) } >= weakThreshold) 1 else 0
    }

    private fun keyOf(text: String) =
        text.lowercase().replace(Regex("[0-9\\s]+"), " ").trim().take(120)

    private fun decide(text: String, pkg: String): Decision {
        val low = text.lowercase()
        if (allow.any { low.contains(it) }) return Decision.PASS
        when (localLevel(text, low)) {
            2 -> return Decision.BLOCK
            1 -> return Decision.SOFT
        }
        if (llmOn && pkg in llmPackages && text.length in 30..800) {
            val key = keyOf(text)
            return when (verdicts[key]) {
                true -> Decision.BLOCK
                false -> Decision.PASS
                null -> { enqueue(key, text); Decision.PASS }
            }
        }
        return Decision.NONE
    }

    private fun enqueue(key: String, text: String) {
        if (pending.containsKey(key) || key in inflight) return
        pending[key] = text
        handler.removeCallbacks(flushRunnable)
        handler.postDelayed(flushRunnable, 1200)
    }

    private fun flush() {
        if (pending.isEmpty()) return
        val apiKey = Rules.get(this, Rules.K_API_KEY).trim()
        if (apiKey.isEmpty() || System.currentTimeMillis() < pauseUntil) {
            pending.clear(); return
        }
        val batch = pending.entries.take(15).map { it.key to it.value }
        batch.forEach { pending.remove(it.first); inflight.add(it.first) }
        if (pending.isNotEmpty()) handler.postDelayed(flushRunnable, 1500)

        val model = Rules.get(this, Rules.K_MODEL).trim()
        val prompt = Rules.get(this, Rules.K_PROMPT)
        try {
            executor.execute {
                val res = GroqClient.classify(apiKey, model, prompt, batch.map { it.second })
                handler.post { onResult(batch, res) }
            }
        } catch (e: Exception) {
            batch.forEach { inflight.remove(it.first) }
        }
    }

    private fun onResult(batch: List<Pair<String, String>>, res: Map<Int, GroqClient.Verdict>?) {
        batch.forEach { inflight.remove(it.first) }
        if (res == null) { // ошибка сети, лимит или неверный ключ: пауза, чтобы не долбить API
            pauseUntil = System.currentTimeMillis() + 60_000
            return
        }
        val threshold = Rules.get(this, Rules.K_THRESHOLD).toDoubleOrNull() ?: 0.65
        batch.forEachIndexed { i, (key, _) ->
            val v = res[i]
            val blocked = v != null && v.category != "normal" && v.confidence >= threshold
            verdicts[key] = blocked
            if (blocked && v != null) learn(v.channel, v.confidence)
        }
        scanScreen()
    }

    /** Канал попадает в «выученные» после одного уверенного вердикта (>= 0.85) или двух обычных. */
    private fun learn(channel: String, confidence: Double) {
        val low = channel.trim().removePrefix("@").lowercase()
        if (low.length < 5) return
        val n = (channelHits[low] ?: 0) + 1
        channelHits[low] = n
        if ((n >= 2 || confidence >= 0.85) && addLearned(channel)) {
            toast("AI Blocker: канал «${channel.trim()}» добавлен в блок-лист", long = false)
        }
    }

    private val stopNames = setOf("youtube", "shorts", "music", "канал", "unknown", "none", "n/a")

    /** Добавляет канал в «выученные». Возвращает true, если добавлен новый. */
    private fun addLearned(channel: String): Boolean {
        val c = channel.trim().removePrefix("@").trim()
        val low = c.lowercase()
        if (c.length < 5 || low in stopNames) return false
        if (allow.any { low.contains(it) || it.contains(low) }) return false
        if (channels.any { it == low }) return false
        val cur = Rules.get(this, Rules.K_LEARNED)
        Rules.set(this, Rules.K_LEARNED, if (cur.isBlank()) c else cur + "\n" + c)
        return true
    }

    // ---------------- страница видео: описание -> Groq ----------------

    private val labelRe = Regex(
        "(altered|synthetic)( or | and )(synthetic|altered)|измен[её]нн\\w+ или (синтетическ|искусственн)\\w+",
        RegexOption.IGNORE_CASE
    )
    private val watchMarkers = listOf(
        Regex("подписаться|subscribe", RegexOption.IGNORE_CASE),
        Regex("нравится|\\blike\\b", RegexOption.IGNORE_CASE),
        Regex("поделиться|\\bshare\\b", RegexOption.IGNORE_CASE),
        Regex("комментари|\\bcomments?\\b", RegexOption.IGNORE_CASE),
        Regex("скачать|сохранить|\\bdownload\\b|\\bsave\\b", RegexOption.IGNORE_CASE)
    )

    /** topOnly: только верхняя часть экрана (плеер, название, канал), без списка рекомендаций ниже. */
    private fun collectTexts(root: AccessibilityNodeInfo, topOnly: Boolean = false): List<String> {
        val out = ArrayList<String>()
        val limit = (resources.displayMetrics.heightPixels * 0.55).toInt()
        val b = Rect()
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var n = 0
        while (n < 500) {
            val node = queue.poll() ?: break
            n++
            if (!node.isVisibleToUser) continue
            if (topOnly) {
                node.getBoundsInScreen(b)
                if (b.top > limit) continue
            }
            val t = node.text?.toString()?.trim().orEmpty()
            val d = node.contentDescription?.toString()?.trim().orEmpty()
            if (t.isNotEmpty()) out.add(t.take(600))
            if (d.isNotEmpty() && d != t) out.add(d.take(600))
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return out
    }

    private fun looksLikeWatchPage(texts: List<String>): Boolean {
        val joined = texts.joinToString("\n")
        return watchMarkers.count { it.containsMatchIn(joined) } >= 3
    }

    private val channelTabs = listOf(
        Regex("^(главная|home)\\b", RegexOption.IGNORE_CASE),
        Regex("^(видео|videos)\\b", RegexOption.IGNORE_CASE),
        Regex("^shorts\\b", RegexOption.IGNORE_CASE),
        Regex("^(плейлисты|playlists)\\b", RegexOption.IGNORE_CASE),
        Regex("^(сообщество|community)\\b", RegexOption.IGNORE_CASE),
        Regex("^(о канале|about)\\b", RegexOption.IGNORE_CASE),
        Regex("^(трансляции|прямые трансляции|live)\\b", RegexOption.IGNORE_CASE)
    )

    /** Страница канала: несколько вкладок (Главная, Видео, Shorts, ...) и кнопка «Подписаться». */
    private fun looksLikeChannelPage(texts: List<String>): Boolean {
        val tabs = channelTabs.count { re -> texts.any { re.containsMatchIn(it.trim()) } }
        return tabs >= 3 && texts.any { watchMarkers[0].containsMatchIn(it) }
    }

    /** 0 = другое, 1 = страница видео, 2 = страница канала. */
    private fun pageKind(texts: List<String>): Int = when {
        looksLikeChannelPage(texts) -> 2
        looksLikeWatchPage(texts) -> 1
        else -> 0
    }

    private fun probeWatch(root: AccessibilityNodeInfo) {
        if (!watchOn || !llmOn || watchScheduled) return
        val now = System.currentTimeMillis()
        if (now < pauseUntil || now - lastProbeAt < 2000 || now - lastWatchAt < 20_000) return
        lastProbeAt = now
        val texts = collectTexts(root)
        if (pageKind(texts) == 0) { lastWatchKey = null; return }
        val longest = texts.maxByOrNull { it.length } ?: return
        if (keyOf(longest) == lastWatchKey) return
        watchScheduled = true
        handler.postDelayed({ analyzeWatch() }, 2500) // даём странице догрузить описание
    }

    private fun analyzeWatch() {
        watchScheduled = false
        if (!watchOn || !llmOn) return
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        if (pkg !in llmPackages || pkg in ignoredPackages) return
        val texts = collectTexts(root)
        val kind = pageKind(texts)
        if (kind == 0) return
        val longest = texts.maxByOrNull { it.length } ?: return
        lastWatchKey = keyOf(longest)
        lastWatchAt = System.currentTimeMillis()

        val page = texts.distinct().joinToString(" | ").take(1800)
        val low = page.lowercase()
        if (allow.any { low.contains(it) }) return
        if (localLevel(page, low) == 2) return // уже закрывается локальными правилами
        val label = kind == 1 && labelRe.containsMatchIn(page)
        val watchKey = lastWatchKey
        val prefix = when {
            kind == 2 -> "[СТРАНИЦА КАНАЛА: оцени канал целиком по описанию и последним видео] "
            label -> "[YouTube показывает метку «изменённый или синтетический контент»] "
            else -> ""
        }
        val item = prefix + page

        val apiKey = Rules.get(this, Rules.K_API_KEY).trim()
        if (apiKey.isEmpty()) return
        val model = Rules.get(this, Rules.K_MODEL).trim()
        val prompt = Rules.get(this, Rules.K_PROMPT)
        try {
            executor.execute {
                val res = GroqClient.classify(apiKey, model, prompt, listOf(item), 1800)
                handler.post { onWatchResult(res, label, watchKey) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "watch executor: $e")
        }
    }

    private fun onWatchResult(res: Map<Int, GroqClient.Verdict>?, label: Boolean, key: String?) {
        if (res == null) { pauseUntil = System.currentTimeMillis() + 60_000; return }
        val v = res[0] ?: return
        val base = Rules.get(this, Rules.K_THRESHOLD).toDoubleOrNull() ?: 0.65
        val threshold = if (label) minOf(base, 0.4) else base // с меткой YouTube порог ниже
        if (v.category == "normal" || v.confidence < threshold) return
        if (addLearned(v.channel)) {
            toast("AI Blocker: канал «${v.channel.trim()}» добавлен в блок-лист")
        } else if (v.channel.isBlank()) {
            toast("AI Blocker: видео похоже на ИИ, но имя канала определить не удалось")
        }
        // закрываем видео, только если пользователь всё ещё на той же странице
        if (closeOpen && key != null && key == lastWatchKey &&
            rootInActiveWindow?.packageName?.toString() == YOUTUBE) {
            lastBackAt = SystemClock.uptimeMillis()
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    /** Если открыто видео/шортс из блок-листа (по названию и каналу в верхней части экрана), выходим назад. */
    private fun checkOpenedBlocked(root: AccessibilityNodeInfo) {
        if (!closeOpen) return
        val now = SystemClock.uptimeMillis()
        if (now - lastOpenCheckAt < 700 || now - lastBackAt < 2500) return
        lastOpenCheckAt = now
        if (pageKind(collectTexts(root)) == 0) return
        val top = collectTexts(root, true).joinToString(" | ")
        val low = top.lowercase()
        if (allow.any { low.contains(it) }) return
        if (localLevel(top, low) != 2) return
        lastBackAt = now
        performGlobalAction(GLOBAL_ACTION_BACK)
        toast("AI Blocker: страница из блок-листа закрыта")
    }

    private fun toast(msg: String, long: Boolean = true) =
        Toast.makeText(this, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

    // ---------------- автоскрытие: «Не рекомендовать канал» ----------------

    private val menuRe = Regex(
        "меню действий|ещё|ещё действия|дополнительные действия|more options|more actions|action menu|more",
        RegexOption.IGNORE_CASE
    )
    private val dontRecommendRe = Regex(
        "не рекомендовать.*канал|don.?t recommend.*channel", RegexOption.IGNORE_CASE
    )
    private val notInterestedRe = Regex("^(не интересует|not interested)$", RegexOption.IGNORE_CASE)
    private val sheetRe = Regex(
        "добавить в очередь|сохранить в плейлист|add to queue|save to playlist|не интересует|not interested",
        RegexOption.IGNORE_CASE
    )

    private fun tryAutoHide(hits: List<Hit>, root: AccessibilityNodeInfo) {
        if (!autoHide || hideBusy) return
        val now = SystemClock.uptimeMillis()
        if (now - lastHideAt < 4000) return
        val h = hits.firstOrNull { it.auto && it.key !in hideDone } ?: return
        hideDone.add(h.key)
        if (hideDone.size > 300) hideDone.remove(hideDone.first())
        val btn = findMenuButton(root, h.rect)
        if (btn == null) { Log.d(TAG, "menu button not found for ${h.key}"); return }
        hideBusy = true
        lastHideAt = now
        val gen = ++hideGen
        handler.postDelayed({ if (hideGen == gen) hideBusy = false }, 7000) // предохранитель
        if (!clickNode(btn)) { hideBusy = false; return }
        handler.postDelayed({ pickMenuItem(0) }, 600)
    }

    private fun pickMenuItem(attempt: Int) {
        val root = rootInActiveWindow
        if (root == null) { finishHide(); return }
        var target = findByText(root, dontRecommendRe)
        if (target == null && attempt >= 3) target = findByText(root, notInterestedRe)
        if (target != null && clickNode(target)) {
            Log.d(TAG, "hidden via menu")
            handler.postDelayed({ finishHide() }, 800)
            return
        }
        if (attempt < 5) {
            handler.postDelayed({ pickMenuItem(attempt + 1) }, 400)
            return
        }
        // не нашли нужный пункт: если меню открыто, закрываем его
        if (findByText(root, sheetRe) != null) performGlobalAction(GLOBAL_ACTION_BACK)
        val now = SystemClock.uptimeMillis()
        if (now - lastFailToastAt > 60_000) {
            lastFailToastAt = now
            toast("AI Blocker: в меню карточки нет пункта «Не рекомендовать канал»")
        }
        finishHide()
    }

    private fun finishHide() {
        hideBusy = false
        lastHideAt = SystemClock.uptimeMillis()
    }

    private fun findMenuButton(root: AccessibilityNodeInfo, rect: Rect): AccessibilityNodeInfo? {
        val cands = ArrayList<AccessibilityNodeInfo>()
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var n = 0
        while (n < 700) {
            val node = queue.poll() ?: break
            n++
            if (!node.isVisibleToUser) continue
            val d = node.contentDescription?.toString()?.trim()
            if (d != null && menuRe.matches(d)) cands.add(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        var best: AccessibilityNodeInfo? = null
        var bestDist = Int.MAX_VALUE
        val b = Rect()
        for (c in cands) {
            c.getBoundsInScreen(b)
            val dist = if (rect.contains(b.centerX(), b.centerY())) 0 else abs(b.centerY() - rect.centerY())
            if (dist < bestDist) { bestDist = dist; best = c }
        }
        val limit = (resources.displayMetrics.density * 160).toInt()
        return if (bestDist <= limit) best else null
    }

    private fun findByText(root: AccessibilityNodeInfo, re: Regex): AccessibilityNodeInfo? {
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var n = 0
        while (n < 700) {
            val node = queue.poll() ?: break
            n++
            val t = node.text?.toString()?.trim()
            val d = node.contentDescription?.toString()?.trim()
            if ((t != null && re.containsMatchIn(t)) || (d != null && re.containsMatchIn(d))) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = node
        var i = 0
        while (cur != null && i < 5) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent
            i++
        }
        return false
    }

    // ---------------- обход экрана и плашки ----------------

    private fun scanScreen() {
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString()
        if (root == null || pkg == null || pkg in ignoredPackages) {
            clearOverlays(); return
        }
        lastPkg = pkg
        val hits = mutableListOf<Hit>()
        walk(root, pkg, hits)
        render(hits.map { it.rect })
        if (pkg == YOUTUBE) {
            tryAutoHide(hits, root)
            checkOpenedBlocked(root)
        }
        if (pkg in llmPackages) probeWatch(root)
    }

    private fun walk(node: AccessibilityNodeInfo, pkg: String, out: MutableList<Hit>) {
        if (out.size >= maxOverlays || !node.isVisibleToUser) return
        val text = buildString {
            node.text?.let { append(it).append(' ') }
            node.contentDescription?.let { append(it) }
        }
        if (text.isNotBlank()) {
            val d = decide(text, pkg)
            when (d) {
                Decision.BLOCK, Decision.SOFT -> {
                    val (rect, container) = blockBounds(node)
                    if (out.none { it.rect == rect }) {
                        out.add(Hit(rect, container, keyOf(text), d == Decision.BLOCK))
                    }
                    return
                }
                Decision.PASS -> return // карточка уже проверена или ждёт проверки
                Decision.NONE -> {}
            }
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, pkg, out) }
    }

    private fun blockBounds(node: AccessibilityNodeInfo): Pair<Rect, AccessibilityNodeInfo> {
        val screenH = resources.displayMetrics.heightPixels
        val r = Rect()
        var cur = node
        for (i in 0 until 5) {
            val p = cur.parent ?: break
            p.getBoundsInScreen(r)
            if (r.height() > screenH * 0.8) break
            cur = p
        }
        return Pair(Rect().also { cur.getBoundsInScreen(it) }, cur)
    }

    private fun render(rects: List<Rect>) {
        while (overlays.size < rects.size) addOverlay()
        while (overlays.size > rects.size) wm.removeView(overlays.removeAt(overlays.lastIndex))
        rects.forEachIndexed { i, r ->
            val lp = overlays[i].layoutParams as WindowManager.LayoutParams
            lp.x = r.left; lp.y = r.top
            lp.width = r.width().coerceAtLeast(1)
            lp.height = r.height().coerceAtLeast(1)
            wm.updateViewLayout(overlays[i], lp)
        }
    }

    private fun addOverlay() {
        val tv = TextView(this).apply {
            text = getString(R.string.overlay_text)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(30, 30, 30))
            gravity = Gravity.CENTER
            textSize = 16f
        }
        val lp = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        ).apply { gravity = Gravity.TOP or Gravity.START }
        wm.addView(tv, lp)
        overlays.add(tv)
    }

    private fun clearOverlays() {
        if (!::wm.isInitialized) return
        overlays.forEach { runCatching { wm.removeView(it) } }
        overlays.clear()
    }

    private companion object {
        const val TAG = "AiBlocker"
        const val YOUTUBE = "com.google.android.youtube"
    }
}
