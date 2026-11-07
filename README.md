# AI Blocker (прототип)

1. Android Studio -> New Project -> Empty Views Activity, package `com.example.aiblocker`, Kotlin, minSdk 26.
2. Замените AndroidManifest.xml; добавьте MainActivity.kt, AiBlockerService.kt, Rules.kt, GroqClient.kt,
   res/xml/ai_blocker_service.xml; обновите res/values/strings.xml.
3. Запустите -> откройте приложение -> включите службу AI Blocker в настройках спец. возможностей.
4. В приложении: вставьте ключ Groq, включите переключатель, нажмите "Сохранить".

Порядок проверки карточки: allow-список -> локальные правила (шаблоны, каналы, слабые признаки)
-> кэш вердиктов Groq -> запрос в Groq (пачкой до 15 карточек) -> автодобавление канала после 2 срабатываний.
