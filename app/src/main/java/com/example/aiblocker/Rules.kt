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
    const val K_AUTO_HIDE = "auto_hide" // "1": нажимать «Не рекомендовать канал» в меню карточки YouTube
    const val K_WATCH = "watch_scan"    // "1": анализировать страницу видео (описание) через Groq
    const val K_CLOSE = "close_open"    // "1": нажимать «Назад», если открылось видео из блок-листа
    const val K_VERSION = "ver"         // версия набора значений по умолчанию

    private const val VERSION = 2

    private const val STRONG = """
\bai[ -]?generated\b
\bmade (with|by) ai\b
\bcreated (with|by) ai\b
\bgenerated (by|with) ai\b
\bai[ -]?slop\b
\bai[ -]?art\b
\bai[ -]?(drama|драма)\b
#ai(art|video|music|voice|generated|slop|drama|cover|image|images|animation|story)\b
#midjourney\b
#stablediffusion\b
#dalle\b
#sora\b
#veo3?\b
сделано с помощью (ии|ai|нейросет)
создано (ии|нейросетью)
сгенерировано (ии|нейросетью)
#нейроарт"""

    /** Значения версии 1: нужны, чтобы при обновлении заменить их, не трогая строки пользователя. */
    private const val OLD_STRONG = """
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

    private const val PROMPT = """Ты фильтр контента для YouTube. Для каждой карточки определи, относится ли она к категориям, которые пользователь хочет скрыть:
- ai_generated: видео или канал сделаны нейросетью. Признаки: ИИ-видео (Sora, Veo, Kling, Runway, Pika), ИИ-картинки и слайдшоу с синтетической озвучкой, ИИ-музыка (Suno, Udio), шаблонные «слоп»-ролики и шортсы (выдуманные истории про животных, детей, военных, фейковые новости, «невероятные» сцены), конвейерные каналы с однотипными заголовками, метки AI / ИИ / нейросеть, указание, что видео изменено или синтетическое.
- mini_drama_reupload: каналы-перезаливы коротких китайских мини-дорам с русской озвучкой и шаблонными кликбейтными заголовками (миллиардер, CEO, босс, «все пали на колени», месть, возвращение наследницы и т.п.).
- normal: всё остальное, включая обычные видео, новости, обучение, музыку, лицензионные полнометражные сериалы и честные обзоры.
Учитывай название, имя канала, описание, хэштеги, длительность и число просмотров. Если дано описание видео, его признаки важнее названия.
Видео, где ИИ лишь тема (обзоры нейросетей, новости об ИИ, уроки по ИИ, честные эксперименты с пометкой), относятся к normal.
Если сомневаешься, выбирай normal и ставь низкую уверенность."""

    val defaults: Map<String, String> = mapOf(
        K_STRONG to STRONG.trim(),
        K_CHANNELS to CHANNELS.trim(),
        K_WEAK to WEAK.trim(),
        K_ALLOW to "",
        K_LEARNED to "",
        K_LLM_ON to "0",
        K_API_KEY to "",
        K_MODEL to "llama-3.3-70b-versatile",
        K_THRESHOLD to "0.65",
        K_PACKAGES to "com.google.android.youtube",
        K_PROMPT to PROMPT.trim(),
        K_AUTO_HIDE to "1",
        K_WATCH to "1",
        K_CLOSE to "1"
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

    /**
     * Приложение сохраняет все поля при нажатии «Сохранить», поэтому старые значения по умолчанию
     * застряли бы в настройках. При смене версии заменяем модель, порог, промпт и обновляем
     * сильные шаблоны (строки, добавленные пользователем, остаются). Каналы и списки не трогаем.
     */
    fun migrate(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (sp.getInt(K_VERSION, 0) >= VERSION) return
        val old = lines(OLD_STRONG).toSet()
        val userStrong = lines(get(ctx, K_STRONG)).filter { it !in old }
        val merged = (lines(STRONG) + userStrong).distinct().joinToString("\n")
        sp.edit()
            .putString(K_STRONG, merged)
            .putString(K_PROMPT, defaults.getValue(K_PROMPT))
            .putString(K_MODEL, defaults.getValue(K_MODEL))
            .putString(K_THRESHOLD, defaults.getValue(K_THRESHOLD))
            .putInt(K_VERSION, VERSION)
            .apply()
    }
}
