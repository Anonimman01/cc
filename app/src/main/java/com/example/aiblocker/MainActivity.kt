package com.example.aiblocker

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.net.Uri

class MainActivity : Activity() {

    private val fields = LinkedHashMap<String, EditText>()
    private val switches = LinkedHashMap<String, Switch>()
    private val infos = LinkedHashMap<String, TextView>()
    private val reqKeys = LinkedHashMap<Int, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Rules.migrate(this)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(Button(this).apply {
            text = getString(R.string.open_settings)
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })

        // ---- Скрытие ----
        title(root, "Скрытие постов")
        toggle(root, Rules.K_AUTO_HIDE, "Убирать пост через меню YouTube («Не рекомендовать канал»)")

        toggle(root, Rules.K_CLOSE, "Закрывать открытое видео из блок-листа")
        toggle(root, Rules.K_TAP_REDIRECT, "Нажатие по плашке «ИИ-контент скрыт» сразу открывает случайную ссылку из списка ниже")
        fileList(root, Rules.K_REDIRECT, 201, "Ссылки для перехода вместо запрещённого видео (файл .txt, по одной в строке; каждый раз берётся случайная; пусто = просто «Назад»)", "ссылок")
        single(root, Rules.K_REDIRECT_DELAY, "Через сколько секунд после открытия запрещённого видео переходить (0 = сразу)")

        // ---- Groq ----
        title(root, "Проверка через Groq")
        toggle(root, Rules.K_LLM_ON, "Включить (тексты карточек уходят на сервер Groq)")
        toggle(root, Rules.K_WATCH, "Читать страницу открытого видео (название, канал, описание) и запоминать каналы")
        single(root, Rules.K_API_KEY, "API-ключ Groq", password = true)
        single(root, Rules.K_MODEL, "Модель (если не работает, подставьте актуальную из консоли Groq)")
        single(root, Rules.K_API_KEY2, "Запасной API-ключ Groq (необязательно; пусто = использовать основной)", password = true)
        single(root, Rules.K_MODEL2, "Запасная модель (включается, если основная вернула ошибку или лимит; пусто = отключить)")
        single(root, Rules.K_THRESHOLD, "Порог уверенности (0..1, по умолчанию 0.65)")
        multi(root, Rules.K_PACKAGES, "Приложения, чьи тексты можно отправлять (package, по одному в строке)", 2)
        multi(root, Rules.K_PROMPT, "Промпт для модели (что считать нежелательным)", 8)

        // ---- Списки ----
        multi(root, Rules.K_ALLOW, "НЕ блокировать эти каналы (имеет приоритет над всем)", 3)
        multi(root, Rules.K_LEARNED, "Выученные каналы (добавляются автоматически; лишнее удалите)", 4)
        fileList(root, Rules.K_CHANNELS, 202, "Каналы/авторы (файл .txt, одно название в строке)", "каналов")
        toggle(root, Rules.K_SYNC_ON, "Раз в сутки подтягивать новые каналы по ссылке (добавляются к списку выше)")
        single(root, Rules.K_SYNC_URL, "Ссылка на список каналов (txt, одно название в строке; должна открываться без входа)")
        root.addView(Button(this).apply {
            text = "Обновить каналы по ссылке сейчас"
            setOnClickListener {
                // ссылку берём из поля, даже если ещё не нажали «Сохранить»
                fields[Rules.K_SYNC_URL]?.let { Rules.set(this@MainActivity, Rules.K_SYNC_URL, it.text.toString().trim()) }
                ChannelSync.run(this@MainActivity) { n ->
                    runOnUiThread {
                        refreshInfo(Rules.K_CHANNELS)
                        Toast.makeText(this@MainActivity,
                            if (n >= 0) "Добавлено каналов: $n" else "Не удалось загрузить (см. журнал)",
                            Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
        multi(root, Rules.K_STRONG, "Сильные шаблоны (regex, достаточно одного совпадения)", 6)
        multi(root, Rules.K_WEAK, "Слабые признаки (regex, закрываем при 2+ совпадениях)", 6)

        root.addView(Button(this).apply {
            text = "Сохранить"
            setOnClickListener {
                fields.forEach { (k, v) -> Rules.set(this@MainActivity, k, v.text.toString().trim()) }
                switches.forEach { (k, s) -> Rules.set(this@MainActivity, k, if (s.isChecked) "1" else "0") }
                Toast.makeText(this@MainActivity, "Сохранено", Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(Button(this).apply {
            text = "Сбросить списки и промпт к умолчанию"
            setOnClickListener {
                listOf(Rules.K_PROMPT, Rules.K_STRONG, Rules.K_WEAK).forEach { k ->
                    fields[k]?.setText(Rules.defaults.getValue(k))
                }
                Rules.set(this@MainActivity, Rules.K_CHANNELS, Rules.defaults.getValue(Rules.K_CHANNELS))
                refreshInfo(Rules.K_CHANNELS)
            }
        })

        // ---- Журнал ----
        title(root, "Журнал работы")
        val logView = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
        }
        root.addView(Button(this).apply {
            text = "Показать журнал"
            setOnClickListener { logView.text = Dbg.read(this@MainActivity).ifBlank { "(пусто)" } }
        })
        root.addView(Button(this).apply {
            text = "Скопировать журнал"
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("aiblocker log", Dbg.read(this@MainActivity)))
                Toast.makeText(this@MainActivity, "Скопировано", Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(Button(this).apply {
            text = "Очистить журнал"
            setOnClickListener { Dbg.clear(this@MainActivity); logView.text = "" }
        })
        root.addView(logView)

        setContentView(ScrollView(this).apply { addView(root) })
        ChannelSync.maybeAuto(this) { runOnUiThread { refreshInfo(Rules.K_CHANNELS) } }
    }

    /** Список из файла: счётчик, «Загрузить из .txt» (добавляет без дублей) и «Очистить». Сохраняется сразу. */
    private fun fileList(parent: LinearLayout, key: String, req: Int, label: String, noun: String) {
        reqKeys[req] = key
        parent.addView(TextView(this).apply {
            text = label
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 32, 0, 8)
        })
        infos[key] = TextView(this).also { parent.addView(it) }
        parent.addView(Button(this).apply {
            text = "Загрузить из .txt (добавить к списку)"
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }, req)
            }
        })
        parent.addView(Button(this).apply {
            text = "Очистить список"
            setOnClickListener { Rules.set(this@MainActivity, key, ""); refreshInfo(key) }
        })
        refreshInfo(key)
    }

    private fun refreshInfo(key: String) {
        val n = Rules.lines(Rules.get(this, key)).size
        infos[key]?.text = "Сейчас в списке: $n"
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val key = reqKeys[requestCode] ?: return
        if (resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val loaded = Rules.lines(text.removePrefix("\uFEFF"))
            val cur = Rules.lines(Rules.get(this, key))
            val seen = cur.map { it.lowercase() }.toMutableSet()
            val added = loaded.filter { seen.add(it.lowercase()) }
            Rules.set(this, key, (cur + added).joinToString("\n"))
            refreshInfo(key)
            Toast.makeText(this, "Добавлено: ${added.size} из ${loaded.size}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось прочитать файл: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun title(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 40, 0, 8)
        })
    }

    private fun toggle(parent: LinearLayout, key: String, label: String) {
        val sw = Switch(this).apply {
            text = label
            isChecked = Rules.get(this@MainActivity, key) == "1"
            setPadding(0, 12, 0, 12)
        }
        switches[key] = sw
        parent.addView(sw)
    }

    private fun single(parent: LinearLayout, key: String, label: String, password: Boolean = false) {
        parent.addView(TextView(this).apply { text = label; setPadding(0, 24, 0, 4) })
        val et = EditText(this).apply {
            setSingleLine(true)
            inputType = if (password)
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(Rules.get(this@MainActivity, key))
        }
        fields[key] = et
        parent.addView(et)
    }

    private fun multi(parent: LinearLayout, key: String, label: String, lines: Int) {
        parent.addView(TextView(this).apply {
            text = label
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 32, 0, 8)
        })
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = lines
            gravity = Gravity.TOP
            textSize = 13f
            setText(Rules.get(this@MainActivity, key))
        }
        fields[key] = et
        parent.addView(et)
    }
}
