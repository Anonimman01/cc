package com.example.aiblocker

import android.content.Context

/** Все настройки хранятся в SharedPreferences (списки - по одному элементу в строке). */
object Rules {
    const val PREFS = "rules"
    const val K_STRONG = "strong"      // regex: одного совпадения достаточно
    const val K_CHANNELS = "channels"  // каналы/авторы вручную
    const val K_WEAK = "weak"          // regex: нужно >= 2 разных совпадений
    const val K_ALLOW = "allow"        // каналы, которые НИКОГДА не закрываем
    const val K_LEARNED = "learned"    // каналы, добавленные автоматически по вердиктам Groq
    const val K_LLM_ON = "llm_on"      // "1" / "0"
    const val K_API_KEY = "api_key"
    const val K_MODEL = "model"
    const val K_THRESHOLD = "threshold"
    const val K_PACKAGES = "packages"  // в каких приложениях разрешено отправлять тексты в Groq
    const val K_PROMPT = "prompt"

    private const val STRONG = """
\bai[ -]?generated
\bmade with ai\b
\bcreated with ai\b
\bgenerated (by|with) ai\b
\bai[ -]?slop
\bai[ -]?art
\bai[ -]?(drama|драма)
#ai\w+
#midjourney
#stablediffusion
#dalle
#sora
#chatgpt
сделано с помощью (ии|ai|нейросет)
создано (ии|нейросетью)
сгенерировано (ии|нейросетью)
#нейросеть
#нейроарт"""

    private const val CHANNELS = """
Сладкая Дорама
Леди Драма
Дорамы судьбы
Путь Короля
Тайны Любви
Ангел Драма
Драма на Ладони
МиниДорамы
Tulip Drama RU
Остров Любви
Цветущая Драма
Театр Гигантов
Дорама Леди
Голубой Любитель
Ежедневный телесериал
Царь AI Драма
Сценка на миллион
Spell Drama
Утопия мини-дорам
Младшая сестра снимает фильм
Блин Драма
RoyalDramaLab
Театр Облаков
Вишня Драма
Не По Сценарию
Didala Drama
История Сильного
КлеверМини
Империя Любви и Драмы
Тайны судьбы
Театр Купидона
Искра Драмы
ДрамаДоза
Обзор дорам о президентах
Премьер-Театр Сердец
Дорамы о любви с боссами
DRAMA POWER
дорамарусский
Le Théâtre du Pigeon
TopLove
Коллекция Дорам
Русские Озвучки CDrama
Лучшие Китайские Драмы"""

    private const val WEAK = """
миллиардер
\bceo\b
\bбосс
дорам
мини-?драм
русск\w* озвучк
бросил[аи]? .*свадьб
унижал
наследни[кц]
бог войны
владыка драконов"""

    private const val PROMPT = """Ты фильтр контента для ленты YouTube. Для каждой карточки определи, относится ли она к категориям, которые пользователь хочет скрыть:
- ai_generated: видео или канал явно сделаны нейросетью (ИИ-видео, ИИ-озвучка, ИИ-арт, метки AI/ИИ).
- mini_drama_reupload: каналы-перезаливы коротких китайских мини-дорам с русской озвучкой и шаблонными кликбейтными заголовками (миллиардер, CEO, босс, «все пали на колени», месть, возвращение наследницы и т.п.).
- normal: всё остальное, включая обычные видео, новости, обучение, музыку, лицензионные полнометражные сериалы и честные обзоры.
Если сомневаешься, выбирай normal и ставь низкую уверенность."""

    val defaults: Map<String, String> = mapOf(
        K_STRONG to STRONG.trim(),
        K_CHANNELS to CHANNELS.trim(),
        K_WEAK to WEAK.trim(),
        K_ALLOW to "",
        K_LEARNED to "",
        K_LLM_ON to "0",
        K_API_KEY to "",
        K_MODEL to "openai/gpt-oss-20b",
        K_THRESHOLD to "0.8",
        K_PACKAGES to "com.google.android.youtube",
        K_PROMPT to PROMPT.trim()
    )

    fun get(ctx: Context, key: String): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key, null) ?: defaults.getValue(key)

    fun set(ctx: Context, key: String, value: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(key, value).apply()
    }

    fun lines(s: String): List<String> =
        s.lines().map { it.trim() }.filter { it.isNotEmpty() }
}
