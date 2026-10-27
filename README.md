# EventPass — Android

Мобильный MVP по Figma. Состояние на **27 октября 2026**: реализованы каркас T01, первая часть T02 (domain/общий fake) и UI-кит T03 с интерактивной галереей и общей оболочкой. **T03 — READY_FOR_HUMAN, H1 ожидается.** Продуктовых экранов ещё нет; ручная приёмка T01 также ожидается. Границы T02.1 — в [отчёте](docs/16-t02-domain-fake.md).

Локальный Git: ветка `main`, remote `origin` — [XataBq/EventManager](https://github.com/XataBq/EventManager). Каркас содержит пять модулей: `app`, `core:domain`, `core:designsystem`, `core:platform`, `data:fake`.

## Запуск каркаса T01

**Текущая сборка T03:** [app-demo-debug.apk](app/build/outputs/apk/demo/debug/app-demo-debug.apk), `0.3.0-t03-demo`, versionCode 2, около 31 МБ. Открыть **Билеты → Открыть UI-кит**; тот же вход доступен из demo-счётчика. Семь прокручиваемых групп показывают тему/Inter, кнопки, поля/пароль/поиск, выбор, статусы/данные, состояния/диалоги и навигацию. Примеры интерактивны, реальных операций не выполняют.

Для T03 прошли assemble, lint (0 ошибок / 6 прежних уведомлений), 25 unit и 5 device-тестов API 36.1, DI/навигационные regressions и demo isolation. SHA-256 APK: `7912b8c741a3b9f8b88704604a5ded3e112acbf4c1b280a5fbb3864499172601`. Стек не обновлялся. [API/источники assets](docs/04-design-system.md), [проверки, ограничения и H1-чек-лист](docs/17-t03-validation.md). Кнопки/поля имеют ≥48dp touch area и рост текста; H1, полная TalkBack-проверка, все ширины/шрифты и физическое устройство остаются пользователю.

Открывать проект в **Android Studio Quail 2026.1.2** (`C:\Program Files\Android\Android Studio1`). Более старая Studio 2025.2.3, также установленная на компьютере, не поддерживает выбранный AGP. Установки и глобальные настройки среды не менялись.

| Компонент | Зафиксированная версия |
|---|---|
| Studio / Gradle JVM | Quail 2026.1.2 / её JBR 21.0.10 |
| Gradle wrapper / AGP | 9.5.0 / 9.3.3 |
| Kotlin / Compose compiler | 2.4.21 / 2.4.21; built-in Kotlin AGP, без kotlin-android |
| KSP2 / Hilt | 2.3.12 / 2.60.1 |
| Compose BOM | 2026.09.00 |
| Navigation 3 / Lifecycle | 1.2.0 / 2.11.0 |
| AndroidX Hilt / Activity | 1.4.0 / 1.13.0 |
| Coroutines / Serialization | 1.10.2 / 1.9.0 |
| AndroidX Test / Espresso | runner/core 1.7.0, ext.junit 1.3.0 / 3.7.0 |
| SDK / JVM bytecode | min 28, compile/target 37, build-tools 36.0.0 / Java и Kotlin 17 |

Все версии находятся в [каталоге](gradle/libs.versions.toml). Набор выбран после сверки официальных требований и проверяется на `demoDebug`, а не для будущего release/API. Основания — [T01: версии и проверка](docs/15-t01-validation.md).

PowerShell из корня проекта; Android SDK указывается через локальный `local.properties` (игнорируется Git) либо Android Studio:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
.\gradlew.bat :app:assembleDemoDebug
.\gradlew.bat :core:domain:test :data:fake:test :app:lintDemoDebug :app:testDemoDebugUnitTest :core:platform:testDebugUnitTest :app:verifyDemoIsolation
```

При повторении наблюдавшегося TLS-сбоя Maven можно добавить `'-Dhttps.protocols=TLSv1.2'`; сертификаты по-прежнему проверяются. Для ограниченной по памяти среды использовался `--max-workers=2`. Wrapper содержит SHA-256 дистрибутива, его JAR также сверен с официальной контрольной суммой.

APK: **[app-demo-debug.apk](app/build/outputs/apk/demo/debug/app-demo-debug.apk)**. Текущая версия `0.3.0-t03-demo`, applicationId `ru.eventpass.android.demo`, namespace `ru.eventpass.app`, seed счётчика `t01-v1`. Оболочка без входа: **Билеты · Мероприятия · Профиль**, demo-счётчик и галерея. Названия продуктовых вкладок сохранены; продуктовые операции ещё не связаны с UI.

Счётчик принадлежит Hilt ViewModel записи навигации и хранится в `SavedStateHandle`. Три стека и выбранная вкладка сохраняются отдельно; decorators продолжают владеть скрытыми записями. Back/закрытие удаляет demo-entry; повторное нажатие текущей вкладки возвращает к корню. Повтор одной ссылки не создаёт дубликат. Новый визит имеет отдельный ID записи, чтобы не наследовать состояние закрываемого экрана.

Fake подключён только в `src/demo` и `demoImplementation`. Вариант `demoRelease` отключён; production-варианта и `data:remote` нет. `verifyDemoIsolation` проверяет эту границу. Это не готовая release-конфигурация.

Для Android-эмулятора или подключённого тестового устройства:

```powershell
$env:ANDROID_SERIAL = 'emulator-5554' # заменить на нужное устройство
.\gradlew.bat :app:connectedDemoDebugAndroidTest
$adb = 'C:\Users\XataB\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb -s $env:ANDROID_SERIAL install -r app/build/outputs/apk/demo/debug/app-demo-debug.apk
.\scripts\verify-t01-process-state.ps1 -Adb $adb -Serial $env:ANDROID_SERIAL
```

Последний скрипт проверяет реальную смерть фонового процесса и восстановление той же задачи; он перезапускает только demo-пакет EventPass. Это техническая проверка, не визуальная приёмка.

Тестовая ссылка (cold/warm):

```powershell
& $adb -s $env:ANDROID_SERIAL shell am start -W -a android.intent.action.VIEW -d 'eventpass-demo://demo/demo' -n ru.eventpass.android.demo/ru.eventpass.app.MainActivity
```

Разрешена только схема `eventpass-demo`, host `demo`, один ID без query/fragment. `eventpass-demo://demo/missing` показывает отсутствие данных. Production App Links не настраивались.

**История T01:** assemble/lint, 7 unit и 3 device-теста API 36.1, DI/очистка ViewModel, navigation/recreation/process death; исходный hash APK в отчёте T01. **T02.1:** assemble/lint, 24 unit и isolation. **T03:** 25 unit, 5 device, assemble/lint/isolation; актуальные результаты и hash — выше и в отчёте T03.

Ручной smoke T01 — в [отчёте T01](docs/15-t01-validation.md), H1 — в [отчёте T03](docs/17-t03-validation.md). T01/T03 остаются **READY_FOR_HUMAN**. Физическое устройство, runtime API 28/37 и человеческие H1–H4 не проверены.

## Начать здесь

[Карта документации](docs/00-documentation-map.md) задаёт короткий порядок чтения. [Аудит готовности](docs/13-development-readiness.md) сохраняется как исторический документ до T01; текущие технические результаты — в отчёте T01.

Сначала прочитать [актуальные ответы B01–B08 от 27 октября](docs/12-backend-answers-2026-10-27.md): они меняют гостевую регистрацию и имеют приоритет над исходными макетами/ранними вопросами.

1. [Контекст, границы и продуктовые правила](docs/01-product.md).
2. [Модель данных, права и архитектура](docs/02-architecture.md).
3. [Мобильные экраны, переходы и deep links](docs/03-navigation.md).
4. [UI-кит и адаптация к Android](docs/04-design-system.md).
5. [Стабы, контракты и переход к API](docs/05-data-contracts.md).
6. [План задач для агентов](docs/06-delivery-plan.md).
7. [Автоматические проверки и ручная приёмка](docs/07-validation.md).
8. [Решения, допущения и вопросы человеку](docs/08-decisions.md).
9. [Стек и проверенные технические источники](docs/09-android-stack.md).
10. [Источники и полнота исследования](docs/sources/README.md).
11. [Вопросы и пожелания к backend — документ для передачи](docs/10-backend-brief.md).
12. [Как начинаем без API и что уже проверено в среде](docs/11-start-without-api.md).
13. [Первые шесть вопросов к бэкенду — отдельный файл для отправки](docs/backend-questions-B01-B06.md).
14. [Соглашения по MVVM, состоянию и готовности кода](docs/14-engineering-rules.md).

Один аккаунт может быть участником, волонтёром и организатором разных мероприятий. Отдельных приложений, типов аккаунта и глобального переключателя роли нет. Сканер общий для владельца и волонтёра; права всегда проверяются относительно `eventId`.

Разрабатываем только Android: блоки Figma 00–04 и 06. Блоки 05 и 99 исключены. Работа с API начнётся через те же интерфейсы репозиториев, что используют локальные стабы.

## Как сохранять контекст

Документация в репозитории — рабочий источник контекста для будущих агентов. После реализации задачи обновлять её статус, реальные команды проверки и связанные решения. Не превращать предложение в «реализовано» до проверки кодом. Исходный снимок Figma хранится отдельно и не переписывается при изменении решений.

Ответы пользователя 27 октября заменяют часть решений 21 октября. Требуемый сценарий: гостю QR сразу без автоаккаунта, saved state flow до закрытия, затем письмо/кабинет; без сохранения анкеты перед входом, телефон optional. Данные и первая часть операций реализованы в T02.1, показ QR и Android guest state ещё относятся к T05. Следующий шаг — ручная H1 UI-кита T03, затем T04/T05 и расширение T02 по нужным фичам.
