package com.example.aiblocker

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/** Подтягивает список каналов по ссылке (txt, одно название в строке) и добавляет новые в «Каналы/авторы». */
object ChannelSync {
    const val DEFAULT_URL = "https://raw.githubusercontent.com/Anonimman01/cc/main/channels_auto.txt"
    private const val MIN_LEN = 6                 // короткие названия ищутся как подстрока и дают ложные срабатывания
    private const val EVERY_MS = 20L * 3600 * 1000
    private val busy = AtomicBoolean(false)

    /** Блокирующий вызов (только из фонового потока). Возвращает число добавленных или -1 при ошибке. */
    fun update(ctx: Context): Int {
        val url = Rules.get(ctx, Rules.K_SYNC_URL).trim()
        if (url.isEmpty()) return -1
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 20_000
            }
            if (conn.responseCode !in 200..299) {
                Dbg.add(ctx, "каналы по ссылке: HTTP ${conn.responseCode}")
                return -1
            }
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().take(2_000_000) }
            val loaded = Rules.lines(text.removePrefix("\uFEFF"))
                .filter { it.length >= MIN_LEN && !it.startsWith("<") && !it.startsWith("#") }
            val cur = Rules.lines(Rules.get(ctx, Rules.K_CHANNELS))
            val seen = cur.map { it.lowercase() }.toMutableSet()
            val added = loaded.filter { seen.add(it.lowercase()) }
            if (added.isNotEmpty()) Rules.set(ctx, Rules.K_CHANNELS, (cur + added).joinToString("\n"))
            Rules.set(ctx, Rules.K_SYNC_AT, System.currentTimeMillis().toString())
            Dbg.add(ctx, "каналы по ссылке: +${added.size} (в файле ${loaded.size})")
            added.size
        } catch (e: Exception) {
            Dbg.add(ctx, "каналы по ссылке: ${e.javaClass.simpleName}")
            -1
        }
    }

    /** Фоновое обновление раз в ~сутки, если включено. onDone вызывается из фонового потока. */
    fun maybeAuto(ctx: Context, onDone: ((Int) -> Unit)? = null) {
        if (Rules.get(ctx, Rules.K_SYNC_ON) != "1") return
        val last = Rules.get(ctx, Rules.K_SYNC_AT).toLongOrNull() ?: 0L
        if (System.currentTimeMillis() - last < EVERY_MS) return
        run(ctx, onDone)
    }

    fun run(ctx: Context, onDone: ((Int) -> Unit)? = null) {
        if (!busy.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        Thread {
            try { onDone?.invoke(update(app)) } finally { busy.set(false) }
        }.start()
    }
}
