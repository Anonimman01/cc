package com.example.aiblocker

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import java.util.concurrent.Executors

class AiBlockerService : AccessibilityService() {

    private enum class Decision { BLOCK, PASS, NONE }

    // ---- правила (перечитываются при изменении настроек) ----
    private var strong: List<Regex> = emptyList()
    private var weak: List<Regex> = emptyList()
    private var channels: List<String> = emptyList()
    private var allow: List<String> = emptyList()
    private var llmPackages: Set<String> = emptySet()
    private var llmOn = false
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

    private fun localMatch(text: String, low: String): Boolean {
        if (strong.any { it.containsMatchIn(text) }) return true
        if (channels.any { low.contains(it) }) return true
        return weak.count { it.containsMatchIn(text) } >= weakThreshold
    }

    private fun keyOf(text: String) =
        text.lowercase().replace(Regex("[0-9\\s]+"), " ").trim().take(120)

    private fun decide(text: String, pkg: String): Decision {
        val low = text.lowercase()
        if (allow.any { low.contains(it) }) return Decision.PASS
        if (localMatch(text, low)) return Decision.BLOCK
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
        executor.execute {
            val res = GroqClient.classify(apiKey, model, prompt, batch.map { it.second })
            handler.post { onResult(batch, res) }
        }
    }

    private fun onResult(batch: List<Pair<String, String>>, res: Map<Int, GroqClient.Verdict>?) {
        batch.forEach { inflight.remove(it.first) }
        if (res == null) { // ошибка сети, лимит или неверный ключ: пауза, чтобы не долбить API
            pauseUntil = System.currentTimeMillis() + 60_000
            return
        }
        val threshold = Rules.get(this, Rules.K_THRESHOLD).toDoubleOrNull() ?: 0.8
        batch.forEachIndexed { i, (key, _) ->
            val v = res[i]
            val blocked = v != null && v.category != "normal" && v.confidence >= threshold
            verdicts[key] = blocked
            if (blocked && v != null) learn(v.channel)
        }
        scanScreen()
    }

    /** Канал, помеченный моделью 2 раза, попадает в список «выученных» и дальше блокируется локально. */
    private fun learn(channel: String) {
        val c = channel.trim()
        if (c.length < 4) return
        val low = c.lowercase()
        if (allow.any { low.contains(it) || it.contains(low) }) return
        val n = (channelHits[low] ?: 0) + 1
        channelHits[low] = n
        if (n >= 2 && channels.none { it == low }) {
            val cur = Rules.get(this, Rules.K_LEARNED)
            Rules.set(this, Rules.K_LEARNED, if (cur.isBlank()) c else cur + "\n" + c)
        }
    }

    // ---------------- обход экрана и плашки ----------------

    private fun scanScreen() {
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString()
        if (root == null || pkg == null || pkg in ignoredPackages) {
            clearOverlays(); return
        }
        lastPkg = pkg
        val rects = mutableListOf<Rect>()
        walk(root, pkg, rects)
        render(rects)
    }

    private fun walk(node: AccessibilityNodeInfo, pkg: String, out: MutableList<Rect>) {
        if (out.size >= maxOverlays || !node.isVisibleToUser) return
        val text = buildString {
            node.text?.let { append(it).append(' ') }
            node.contentDescription?.let { append(it) }
        }
        if (text.isNotBlank()) {
            when (decide(text, pkg)) {
                Decision.BLOCK -> { out.add(blockBounds(node)); return }
                Decision.PASS -> return // карточка уже проверена или ждёт проверки
                Decision.NONE -> {}
            }
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, pkg, out) }
    }

    private fun blockBounds(node: AccessibilityNodeInfo): Rect {
        val screenH = resources.displayMetrics.heightPixels
        val r = Rect()
        var cur = node
        for (i in 0 until 5) {
            val p = cur.parent ?: break
            p.getBoundsInScreen(r)
            if (r.height() > screenH * 0.8) break
            cur = p
        }
        return Rect().also { cur.getBoundsInScreen(it) }
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
}
