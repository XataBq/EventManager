# T01 — каркас и техническая проверка

Дата: 27 октября 2026. Статус: **READY_FOR_HUMAN**. Ручная приёмка пользователем **не проводилась**. Этот отчёт относится только к T01; проверки A/M/H продуктового MVP из 07 не отмечаются пройденными.

APK: [app-demo-debug.apk](../app/build/outputs/apk/demo/debug/app-demo-debug.apk), build `0.1.0-t01-demo` / versionCode `1`, seed `t01-v1`.

SHA-256: `e62e947615e56292abafa1dfc2c1fe742727b8009bb025c7c7a108806497c6f0`.

## Фактические результаты

| Проверка | Результат |
|---|---|
| assembleDemoDebug | Успешно, APK создан и установлен на эмулятор |
| lintDemoDebug | Успешно: 0 ошибок, 6 уведомлений о более новых Gradle/AGP/Coroutines/Serialization; baseline и отключение проверок не применялись |
| JVM unit tests | 7/7: 2 ViewModel, 3 навигация/сериализация, 1 parser с допустимыми/недопустимыми ссылками, 1 repository seed/not found |
| verifyDemoIsolation | Успешно: только demoDebug, fake отсутствует в main implementation |
| connectedDemoDebugAndroidTest | 3/3 на API 36.1: реальная Hilt factory и очистка entry ViewModel; вкладки/счётчик/recreation/system Back; warm/повторная/неизвестная/запрещённая ссылка |
| Реальный process death | Успешно: PID 16957 → отсутствует → 17113; восстановлены выбранный «Профиль», вложенный стек «Билеты» и счётчик 1; pop и новый запуск сбрасывают состояние |
| APK metadata/signature | aapt: package ru.eventpass.android.demo, minSdk 28, target/compile 37; apksigner verify успешен, debug-подпись v2 |
| Wrapper | Проверены SHA-256 JAR и закреплённого дистрибутива |
| Ручная приёмка | Ожидается, не отмечена пройденной |

Локальные логи: `.gradle/t01-build-verified.log`, `.gradle/t01-smoke.log`, `.gradle/t01-process-state.log`. Отчёты: `app/build/reports/lint-results-demoDebug.html`, `app/build/reports/androidTests/connected/debug/flavors/demo/index.html`; JVM XML — в `build/test-results` соответствующих модулей. Build/log outputs игнорируются Git.

## Реализованный объём

- Пять модулей: `app`, чистые Kotlin `core:domain`/`data:fake`, Android `core:designsystem`/`core:platform`.
- Одна Activity; Compose с начальными цветами блока 06; три корневые вкладки «Билеты / Мероприятия / Профиль» и один вложенный demo-экран.
- `DemoRoute` получает Hilt ViewModel и lifecycle-aware StateFlow; `DemoScreen` получает только состояние/callbacks. Domain содержит `DemoRepository`/`DemoRecord`, demo-адаптер выбирается в Hilt-модуле app/src/demo.
- `Loading / Content / NotFound`; seed `t01-v1`; демонстрационный счётчик хранится в SavedStateHandle записи. Это не гостевой билет и не продуктовый кэш.
- Три rememberNavBackStack; выбранная вкладка в rememberSaveable. Для каждого стека отдельно, включая скрытые, вызван rememberDecoratedNavEntries с saveable-state и ViewModel-store decorators. При удалении записи её ViewModel очищается. Новый визит имеет отдельный UUID ключа.
- Back удаляет вложенный экран, с корня вторичного таба возвращает в «Билеты», с корня «Билеты» отдаётся Activity. Повторный tab click возвращает к корню, обычное переключение сохраняет стек. Повтор той же ссылки не сбрасывает счётчик и не дублирует экран.
- Demo deep link `eventpass-demo://demo/{id}`: cold start и onNewIntent, строгий parser схемы/host/path, без query/fragment, с безопасным родителем «Билеты».
- Только `demoDebug`, fake в `demoImplementation` и DI в `src/demo`; release отключён. `verifyDemoIsolation` проверяет состав вариантов и отсутствие fake в main implementation. Production-конфигурация и remote-адаптер не созданы.

## Среда и выбор версий

На машине обнаружены **две** Studio. Для T01 используется **Quail 2026.1.2**, `C:\Program Files\Android\Android Studio1`, build `261.25134.95.2612.15914620`, JBR **21.0.10**. Старая Studio в соседней папке — 2025.2.3/JBR 21.0.8; её ограничения не применяются к выбранной Quail. Глобальные Git/Java/Gradle/Studio settings не менялись.

SDK: `C:\Users\XataB\AppData\Local\Android\Sdk`; minSdk **28**, compile/target **37**, build-tools **36.0.0**. Gradle **9.5.0**, AGP **9.3.3**, Kotlin и Compose compiler **2.4.21**, KSP2 **2.3.12**, Hilt **2.60.1**, Compose BOM **2026.09.00**, Navigation 3 **1.2.0**, Lifecycle **2.11.0**, AndroidX Hilt **1.4.0**, Activity **1.13.0**. Coroutines **1.10.2**, Serialization **1.9.0**. JVM-модули компилируются JDK 21; Java/Kotlin bytecode всех модулей — **17**. Android использует built-in Kotlin AGP, отдельный kotlin-android не подключён.

JUnit **4.13.2**, AndroidX Test runner/core **1.7.0**, ext.junit **1.3.0**, Espresso **3.7.0**, Compose Test из того же BOM, API `junit4.v2.createAndroidComposeRule`.

Сверены первичные источники и фактические Maven artifacts:

- [Studio/AGP и SDK](https://developer.android.com/build/releases/about-agp): Quail 2026.1.2 поддерживает AGP до 9.3; SDK 37 требует нового toolchain.
- [AGP 9.3 compatibility](https://developer.android.com/build/releases/agp-9-3-0-release-notes): Gradle 9.5.0, build-tools 36.0.0, JDK минимум 17, API до 37. Patch 9.3.3 содержит исправления, в том числе Windows/R8.
- [Kotlin Gradle compatibility](https://kotlinlang.org/docs/gradle-configure-project.html): 2.4.21 поддерживает Gradle до 9.7.0; верхняя полностью проверенная AGP-версия в таблице — 9.3.1. Сочетание с patch 9.3.3 проверяется нашим demoDebug, не объявляется проверкой всех режимов KGP/R8.
- [Built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin), [Compose compiler plugin](https://developer.android.com/jetpack/androidx/releases/compose-kotlin), [BOM mapping](https://developer.android.com/develop/ui/compose/bom/bom-mapping): compiler следует версии Kotlin, BOM отдельно.
- [Hilt setup](https://dagger.dev/hilt/gradle-setup.html), [Dagger 2.59: AGP 9 requirement](https://github.com/google/dagger/releases/tag/dagger-2.59), [KSP 2.3.12](https://github.com/google/ksp/releases/tag/2.3.12).
- [Navigation 3 stable 1.2.0](https://developer.android.com/jetpack/androidx/releases/navigation3), [state/decorators](https://developer.android.com/guide/navigation/navigation-3/save-state), [AndroidX Hilt](https://developer.android.com/jetpack/androidx/releases/hilt). AAR metadata navigation3-ui-android 1.2.0 требует compileSdk 37/AGP ≥ 9.1; AndroidX Hilt 1.4 требует AGP ≥ 9.2. Эти условия выполняются.
- [AndroidX Test](https://developer.android.com/jetpack/androidx/releases/test): Espresso 3.7.0 исправляет обращение к InputManager.getInstance. В device smoke обнаружена транзитивная 3.5.0 из Compose Test; добавлена явная стабильная 3.7.0, без alpha.

Wrapper создан установленным Gradle только при bootstrap; все дальнейшие сборки — через wrapper. SHA-256 дистрибутива зафиксирована в wrapper properties; SHA-256 wrapper JAR совпала с [официальной](https://services.gradle.org/distributions/gradle-9.5.0-wrapper.jar.sha256): `497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7`.

## Команды

PowerShell, рабочая папка `F:\ASProjects\MospolytechProject`:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
$env:ANDROID_SERIAL = 'emulator-5554'
.\gradlew.bat :app:assembleDemoDebug :app:lintDemoDebug :app:testDemoDebugUnitTest :core:platform:testDebugUnitTest :data:fake:test :app:verifyDemoIsolation '-Dhttps.protocols=TLSv1.2' --console=plain --max-workers=2
.\gradlew.bat :app:connectedDemoDebugAndroidTest :app:lintDemoDebug '-Dhttps.protocols=TLSv1.2' --console=plain --max-workers=2
```

Эмулятор: существующий `Medium_Phone_API_36.1`, Android 16, API 36/36.1, x86_64, build `14574095`, read-only, без сохранения изменений в snapshot. Первый запуск из снимка долго оставался offline; затем устройство загрузилось. Дополнительный холодный запуск Pixel_5 остановлен, его тесты не выполнялись.

При загрузке Maven-зависимостей наблюдались TLS handshake failures. Автоматические повторы ограничивались этой сетевой ошибкой; сертификаты/репозитории не подменялись. Исправлены ошибки первого каркаса: JVM targets, отсутствие BOM в androidTest, escaping local.properties, exported в overlay-манифесте, backup attributes и версия Espresso. Тесты Back синхронизируются с завершением recomposition и посылают системную клавишу, а не вызывают старый dispatcher напрямую. Для teardown warm-link теста исходный Intent возвращается только в harness: ActivityScenario 1.7 иначе игнорирует lifecycle events после setIntent(onNewIntent). Приложение проверяется с настоящими VIEW Intent.

Отдельная проверка реального process death:

```powershell
$adb = 'C:\Users\XataB\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb -s $env:ANDROID_SERIAL install -r app/build/outputs/apk/demo/debug/app-demo-debug.apk
.\scripts\verify-t01-process-state.ps1 -Adb $adb -Serial $env:ANDROID_SERIAL
```

Скрипт открывает cold link, меняет счётчик, уходит на «Профиль», отправляет приложение в фон, выполняет `am kill`, проверяет исчезновение PID и восстановление той же задачи с новым PID. Затем проверяет выбранный таб, прежний счётчик, удаление entry и свежий запуск. `force-stop` используется только для исходного/явно нового запуска, не как имитация системного убийства.

## Ручная проверка T01 — ожидается

Все пункты ниже имеют статус **не проверено человеком**. Передать APK из `app/build/outputs/apk/demo/debug/app-demo-debug.apk`; версия `0.1.0-t01-demo`, seed `t01-v1`.

1. Установить APK и открыть приложение. Видны пометка демонстрации и три вкладки. Нет входа, реальных билетов и каталога.
2. В «Билетах» открыть demo-экран и увеличить счётчик. Перейти в «Мероприятия», затем «Профиль» и обратно в «Билеты»: тот же экран и счётчик.
3. На вложенном экране проверить системный Back и кнопку «Назад»: корень «Билеты». Снова открыть: 0. Повторное нажатие выбранной вкладки тоже возвращает к корню; чужие табы не накапливаются в стеке.
4. Повернуть устройство на demo-экране и на «Профиле», свернуть/вернуть приложение. Счётчик, стек и выбранный таб сохраняются. После явного закрытия demo-entry новый визит начинается с 0.
5. Открыть `eventpass-demo://demo/demo` при закрытом и уже открытом приложении; повторить ссылку после увеличения счётчика. Родитель — «Билеты», дубликат не появляется, счётчик не сбрасывается. Для `/missing` — отсутствие данных; неизвестный host/query не принимаются.
6. Проверить читаемость, нижние/верхние системные отступы, масштаб шрифта 1.3 и поведение системного Back на конкретном устройстве. С корня «Профиль/Мероприятия» — «Билеты», затем выход.

## Ограничения и следующий этап

- Физическое устройство, API 28, Android 17/API 37 runtime и визуальная приёмка не проверены. compile/target 37 не означает прогон на Android 17.
- Это технический demo-spike, без auth, прав на события, регистрации, QR, backend, постоянного fake-store, outbox и продуктовых моделей. Проверка счётчика не означает реализацию GuestTicketSnapshot T05.
- В дизайне только начальные цвета и стандартные Material primitives; Inter и полноценные компоненты/иконки/превью — T03. Это не визуальное соответствие Figma.
- Production App Links, реальный домен, signing, release/R8, публикация и API остаются вне T01. `applicationId` временный; production fake не создавался.
- Следующая задача: первая предметная часть T02 и UI-кит T03. Подагенты не запускались. H1–H4 и продуктовые M/A-проверки не проводились.
