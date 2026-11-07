package com.example.aiblocker

import android.app.Activity
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

class MainActivity : Activity() {

    private val fields = LinkedHashMap<String, EditText>()
    private lateinit var llmSwitch: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(Button(this).apply {
            text = getString(R.string.open_settings)
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })

        // ---- Groq ----
        title(root, "Проверка через Groq")
        llmSwitch = Switch(this).apply {
            text = "Включить (тексты карточек уходят на сервер Groq)"
            isChecked = Rules.get(this@MainActivity, Rules.K_LLM_ON) == "1"
        }
        root.addView(llmSwitch)
        single(root, Rules.K_API_KEY, "API-ключ Groq", password = true)
        single(root, Rules.K_MODEL, "Модель (если не работает, подставьте актуальную из консоли Groq)")
        single(root, Rules.K_THRESHOLD, "Порог уверенности (0..1, по умолчанию 0.8)")
        multi(root, Rules.K_PACKAGES, "Приложения, чьи тексты можно отправлять (package, по одному в строке)", 2)
        multi(root, Rules.K_PROMPT, "Промпт для модели (что считать нежелательным)", 8)

        // ---- Списки ----
        multi(root, Rules.K_ALLOW, "НЕ блокировать эти каналы (имеет приоритет над всем)", 3)
        multi(root, Rules.K_LEARNED, "Выученные каналы (добавляются автоматически; лишнее удалите)", 4)
        multi(root, Rules.K_CHANNELS, "Каналы/авторы вручную (регистр не важен)", 6)
        multi(root, Rules.K_STRONG, "Сильные шаблоны (regex, достаточно одного совпадения)", 6)
        multi(root, Rules.K_WEAK, "Слабые признаки (regex, закрываем при 2+ совпадениях)", 6)

        root.addView(Button(this).apply {
            text = "Сохранить"
            setOnClickListener {
                fields.forEach { (k, v) -> Rules.set(this@MainActivity, k, v.text.toString().trim()) }
                Rules.set(this@MainActivity, Rules.K_LLM_ON, if (llmSwitch.isChecked) "1" else "0")
                Toast.makeText(this@MainActivity, "Сохранено", Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(Button(this).apply {
            text = "Сбросить списки и промпт к умолчанию"
            setOnClickListener {
                listOf(Rules.K_PROMPT, Rules.K_CHANNELS, Rules.K_STRONG, Rules.K_WEAK).forEach { k ->
                    fields[k]?.setText(Rules.defaults.getValue(k))
                }
            }
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun title(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 40, 0, 8)
        })
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
