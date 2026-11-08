package com.example.aiblocker

import android.content.Context

/** Все настройки хранятся в SharedPreferences (списки - по одному элементу в строке). */
/** Небольшой журнал событий в отдельном файле настроек (чтобы не дёргать слушатель правил). */
object Dbg {
    private const val F = "aiblocker_log"
    private const val K = "lines"
    private const val MAX = 60

    @Synchronized
    fun add(ctx: Context, msg: String) {
        val sp = ctx.getSharedPreferences(F, Context.MODE_PRIVATE)
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val lines = (sp.getString(K, "") ?: "").lines().filter { it.isNotEmpty() }.takeLast(MAX - 1)
        sp.edit().putString(K, (lines + "$t $msg".take(400)).joinToString("\n")).apply()
    }

    @Synchronized
    fun read(ctx: Context): String =
        ctx.getSharedPreferences(F, Context.MODE_PRIVATE).getString(K, "") ?: ""

    @Synchronized
    fun clear(ctx: Context) {
        ctx.getSharedPreferences(F, Context.MODE_PRIVATE).edit().clear().apply()
    }
}

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
    const val K_API_KEY2 = "api_key2"  // запасной ключ (необязательно)
    const val K_MODEL2 = "model2"      // запасная модель (необязательно)
    const val K_THRESHOLD = "threshold"
    const val K_PACKAGES = "packages"  // в каких приложениях разрешено отправлять тексты в Groq
    const val K_PROMPT = "prompt"
    const val K_AUTO_HIDE = "auto_hide" // "1": нажимать «Не рекомендовать канал» в меню карточки YouTube
    const val K_WATCH = "watch_scan"    // "1": анализировать страницу видео (описание) через Groq
    const val K_CLOSE = "close_open"    // "1": нажимать «Назад», если открылось видео из блок-листа
    const val K_REDIRECT = "redirect_url"       // куда переходить вместо закрытой страницы (пусто = «Назад»)
    const val K_REDIRECT_DELAY = "redirect_sec" // через сколько секунд
    const val K_TAP_REDIRECT = "tap_redirect"   // "1": нажатие по плашке сразу открывает ссылку из списка
    private const val K_VER_REDIRECT = "ver_redirect"
    private const val K_VER_MODELS = "ver_models"
    private const val K_VER_RULES = "ver_rules"
    const val K_SYNC_ON = "sync_on"     // "1": раз в сутки подтягивать список каналов по ссылке
    const val K_SYNC_URL = "sync_url"   // ссылка на txt (одно название в строке)
    const val K_SYNC_AT = "sync_at"     // время последнего обновления (мс)
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

    /** Правила по названию: дорама/мини-драма вместе с типичным кликбейтом (в любом порядке). */
    private const val STRONG_ADD = """
(дорам|мини-?драм)[\p{L}]*.{0,150}(озвучк|миллиардер|\bceo\b|босс|президент|наследни)
(озвучк|миллиардер|\bceo\b|босс|президент|наследни).{0,150}(дорам|мини-?драм)"""

    /** «Китайские дорамы» в названии: два слабых признака вместе дают плашку (без автоскрытия и без обучения). */
    private const val WEAK_ADD = """
китайск[\p{L}]* (дорам|драм|сериал|мини)
русск[\p{L}]* озвучк"""

    private const val PROMPT = """Ты фильтр контента для YouTube. Для каждой карточки определи, относится ли она к категориям, которые пользователь хочет скрыть:
- ai_generated: видео или канал сделаны нейросетью. Признаки: ИИ-видео (Sora, Veo, Kling, Runway, Pika), ИИ-картинки и слайдшоу с синтетической озвучкой, ИИ-музыка (Suno, Udio), шаблонные «слоп»-ролики и шортсы (выдуманные истории про животных, детей, военных, фейковые новости, «невероятные» сцены), конвейерные каналы с однотипными заголовками, метки AI / ИИ / нейросеть, указание, что видео изменено или синтетическое.
- mini_drama_reupload: каналы-перезаливы коротких китайских мини-дорам с русской озвучкой и шаблонными кликбейтными заголовками (миллиардер, CEO, босс, «все пали на колени», месть, возвращение наследницы и т.п.).
- normal: всё остальное, включая обычные видео, новости, обучение, музыку, лицензионные полнометражные сериалы и честные обзоры.
Учитывай название, имя канала, описание, хэштеги, длительность и число просмотров. Если дано описание видео, его признаки важнее названия.
Видео, где ИИ лишь тема (обзоры нейросетей, новости об ИИ, уроки по ИИ, честные эксперименты с пометкой), относятся к normal.
Если сомневаешься, выбирай normal и ставь низкую уверенность."""

    val defaults: Map<String, String> = mapOf(
        K_STRONG to (STRONG.trim() + "\n" + STRONG_ADD.trim()),
        K_CHANNELS to CHANNELS.trim(),
        K_WEAK to (WEAK.trim() + "\n" + WEAK_ADD.trim()),
        K_ALLOW to "",
        K_LEARNED to "",
        K_LLM_ON to "0",
        K_API_KEY to "",
        K_MODEL to "openai/gpt-oss-120b",
        K_API_KEY2 to "",
        K_MODEL2 to "openai/gpt-oss-20b",
        K_THRESHOLD to "0.65",
        K_PACKAGES to "com.google.android.youtube",
        K_PROMPT to PROMPT.trim(),
        K_AUTO_HIDE to "1",
        K_WATCH to "1",
        K_CLOSE to "1",
        K_REDIRECT to "https://www.youtube.com/live/akBFTyfKTrA?si=G1UBehIjJyz3N5lq",
        K_REDIRECT_DELAY to "0",
        K_TAP_REDIRECT to "1",
        K_SYNC_ON to "1",
        K_SYNC_URL to ChannelSync.DEFAULT_URL,
        K_SYNC_AT to "0"
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
        // отдельная миграция: задержка перехода теперь 0 по умолчанию (остальные настройки не трогаем)
        if (sp.getInt(K_VER_REDIRECT, 0) < 1) {
            sp.edit().putString(K_REDIRECT_DELAY, "0").putInt(K_VER_REDIRECT, 1).apply()
        }
        // Groq закрыл llama-3.3-70b-versatile и llama-3.1-8b-instant (HTTP 404): меняем только модели
        if (sp.getInt(K_VER_MODELS, 0) < 1) {
            val ed = sp.edit().putInt(K_VER_MODELS, 1)
            if (sp.getString(K_MODEL, null)?.startsWith("llama") != false) ed.putString(K_MODEL, defaults.getValue(K_MODEL))
            if (sp.getString(K_MODEL2, null)?.startsWith("llama") != false) ed.putString(K_MODEL2, defaults.getValue(K_MODEL2))
            ed.apply()
        }
        // новые правила по названию добавляем к сохранённым спискам, пользовательские строки не трогаем
        if (sp.getInt(K_VER_RULES, 0) < 1) {
            val strong = (lines(get(ctx, K_STRONG)) + lines(STRONG_ADD)).distinct().joinToString("\n")
            val weak = (lines(get(ctx, K_WEAK)) + lines(WEAK_ADD)).distinct().joinToString("\n")
            sp.edit().putString(K_STRONG, strong).putString(K_WEAK, weak).putInt(K_VER_RULES, 1).apply()
        }
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
