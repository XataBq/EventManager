# T04 до H1 — независимая T02.2 и модель auth-навигации

28 октября 2026. Основание: `docs/prompts/t04-auth-navigation.md`, HEAD до работы `2c035a8`, дополнение CODE_REVIEW.md от 28 октября. H1 в сообщениях и документации не подтверждена. Выполнена разрешённая независимая часть. **T04 — IN_PROGRESS; T03 — READY_FOR_HUMAN; H1/H2 не закрыты.**

## Исправления P2 после ревью cfc0624

Текущий [app-demo-debug.apk](../app/build/outputs/apk/demo/debug/app-demo-debug.apk): **0.4.1-t04-foundation-demo**, code **5**, 32318182 байта. SHA-256: `518e3c5b3fcd88bae3c339083e9858c1b802d5b0e38f847438fd504031257f46`. ApplicationId/SDK прежние, aapt metadata и debug-подпись v2 проверены. Версии библиотек и snapshot schema 1 не менялись. H1 не подтверждена, T04 остаётся IN_PROGRESS; нового auth UI/Activity wiring нет.

Исправлены две находки пользовательского ревью:

- **Отмена signIn:** IO вычисляет результат и записывает snapshot без публикации подготовленной сессии. Сессия принимается в контексте вызывающей корутины после отменяемого возврата из withContext; под общей блокировкой ещё раз проверяются активность, generation, аккаунт и срок сессии. Между публикацией и возвратом нет suspend/смены dispatcher. Это точка принятия: отмена до неё (во время write или пока продолжение caller стоит в очереди) не публикует Authenticated и не открывает Shell. Последующая отмена уже принятого входа сама по себе не означает logout.
- **Retry ссылки:** handler разделяет `inFlight` и ограниченный набор `handled`. Параллельный дубль отклоняется без второго вызова репозитория. Успех/локальный Invalid помечаются обработанными; любой AuthLinkResult.Failed не фиксируется как обработанный. `finally` освобождает inFlight также при исключении/отмене, поэтому тот же requestId допускает явный повтор. Автоматического retry нет; после успеха сохраняется Duplicate, а одноразовость challenge контролирует backend.

Отмена не преобразуется в Network. Уже записанные server-side изменения аккаунта/challenge продолжают публиковаться в общем fake, чтобы память и snapshot не расходились. Например, отменённый во время записи signUp может уже создать неподтверждённый аккаунт, но не сессию; холодный backend видит то же состояние. SignIn не сохраняет сессию на диск, поэтому её принятие отложено отдельно. Семантика отменяемого возврата соответствует [withContext](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/with-context.html); регрессии реально исполнены на зафиксированной в проекте coroutines 1.10.2, без обновления на версию текущей online-документации.

`AuthFoundationRegressionTest` добавляет 6 наблюдаемых проверок: отмена во время блокирующей записи с живым collector координатора (в том числе отсутствие промежуточного Authenticated); отмена после IO до возобновления caller; logout в этой же паузе; согласованность долговечной регистрации при отмене; Network/Timeout/StorageUnavailable → явный retry того же delivery → success → Duplicate без повторной мутации; параллельный дубль и retry после cancellation. Latch и управляемая очередь dispatcher задают момент отмены без sleep/ожидания реальных минут.

До исправления первые 5 проверок дали **4 failures / 1 success** на коде cfc0624: два поздних входа и два заблокированных retry; тест долговечной записи уже проходил. Red: `.gradle/t04-p2-red.log` и `.gradle/t04-p2-red.xml`. После исправления эти 5 прошли (`t04-p2-green.log`), затем добавлена шестая проверка generation в точке принятия и выполнен полный прогон:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
.\gradlew.bat :core:domain:test --rerun :data:fake:test --rerun :app:testDemoDebugUnitTest --rerun :core:platform:testDebugUnitTest --rerun :app:assembleDemoDebug :app:assembleDemoDebugAndroidTest :app:lintDemoDebug :app:verifyDemoIsolation --offline --console=plain --max-workers=2
$env:ANDROID_SERIAL = 'emulator-5584'
.\gradlew.bat :app:connectedDemoDebugAndroidTest --offline --console=plain --max-workers=2
```

Оба вызова **BUILD SUCCESSFUL (34s / 46s)**. Все **52 unit-теста** заново исполнены: domain 7, fake 25, app 18, platform 2; failures/errors/skipped = 0. Lint **0 errors / 4 version warnings**, demo isolation прошла. Все **7 device-тестов** прошли на отдельном EventPass_Insets_API33 / Pixel 7 / API 33 / три кнопки: Android auth storage/DI contract и прежние nav/entry/gallery/insets проверки. Это не auth UI smoke, не новый gesture/process-death прогон и не H1/H2.

Итоговые логи: `.gradle/t04-p2-build.log`, `.gradle/t04-p2-device.log`; отдельная копия XML/logcat — `.gradle/t04-p2-evidence`. CODE_REVIEW.md не изменён; новое полное ревью не запускалось. Созданные гостевые tickets по-прежнему не persist, outbox UI и оставшийся auth UI/guards/Activity integration ждут H1. Ниже сохранена история foundation cfc0624; её версия/hash/результаты не являются текущим output APK.

## Сборка cfc0624 — история

Исходный APK cfc0624: **0.4.0-t04-foundation-demo**, versionCode **4**, applicationId `ru.eventpass.android.demo`, minSdk 28, compile/target 37. Размер 32483393 байта; SHA-256 `f65c299c266351317ce03ef39958dc373e27eca2c177048323f375d8440d9f95`. Метаданные aapt и debug-подпись v2 проверены, `apksigner verify` успешен. Toolchain/версии зависимостей не менялись, сборка offline на Quail 2026.1.2/JBR 21.0.10.

В APK остаётся прежняя техническая оболочка и галерея: **Билеты → Открыть UI-кит**. Это не авторизованный продуктовый кабинет. Новый guest startup/Login, auth UI и подключение coordinator к оболочке не включены до H1. По этой сборке нельзя заявлять выполненный пользовательский auth smoke.

## Реализованный срез

- `core:domain/auth`: AuthRepository, Session/Unknown/Guest/Authenticated, generation, типизированные AuthResult/AuthError, EmailChallenge и AuthSecret с redacted toString. Нет Android/DTO/R.string или сериализации секретов.
- Один FakeEventPassEnvironment: auth, события, регистрации и билеты используют общие accounts/сессию. SignUp не создаёт билет или сессию. ConfirmEmail не логинит. Явный SignIn после подтверждения открывает репозиторные билеты на этот email; другой аккаунт их не получает. ScenarioController остаётся debug/test-инструментом.
- Reset email → code → grant → новый пароль: неверный/истёкший/потреблённый challenge различим; resend имеет cooldown и заменяет старый challenge. Grant одноразовый, действует до истечения исходного challenge. Reset не подтверждает email и не логинит; сбрасывает текущую demo-сессию. Новый пароль действительно используется при следующем входе, включая новый экземпляр backend.
- Отказы Network/Timeout управляются `scenarios.failNextAuth`, задержка/отмена — инъектируемым suspend hook, Clock и генератор ID/challenges также инъектируются. Нет автоматических retry; CancellationException не преобразуется в сетевую ошибку. Generation не даёт позднему ответу войти после logout/reset/смены сессии. Snapshot сохраняет сроки без потери долей секунды и записывается перед публикацией успеха; IOException даёт StorageUnavailable без ложного письма/аккаунта.
- SessionCoordinator — чистая модель для будущего UI-хоста, вызывается на общем UI dispatcher. Начинает с Loading и очистки приватного состояния, наблюдает AuthRepository. При смене scope/generation очищает состояние через host callback; signIn защищён от параллельного вызова, поздний результат после logout отклоняется. `NavigationState.clearPrivateState` очищает все три стека, включая скрытые. Подключение callback к живым decorators и проверка logout/Back в Activity отложены до H1.
- ReturnDestination сериализует только валидный eventId или RootTab; pending destination потребляется после входа один раз, обычный вход выбирает Tickets. PrivateScope связан с userId и случайным ID текущей сессии, восстановленный маршрут не доказывает вход. Unknown/Guest/другой scope не допускают восстановление приватного состояния. Forbidden → RemoveForbidden без циклического Login; Unauthenticated → Login с безопасным назначением. Права на E остаются в существующей EventAccessPolicy, global role не добавлен.
- Подготовлены AuthDemoLinkParser/AuthLinkHandler: одноразовая обработка requestId, подтверждение без сессии, строгие пути. **В MainActivity/manifest новые auth/event пути пока не подключены.** Это проверка parser/handler и холодного backend snapshot, а не проверенная доставка новой ссылки в Activity.

## Временные demo-настройки и persistence

Это локальная симуляция, параметры не объявляются backend-контрактом:

| Настройка | Значение |
|---|---|
| Email | Точное совпадение после trim, без Gmail-подобной нормализации; базовая проверка синтаксиса, до 254 символов |
| Имя | 1–200 после trim, любая письменность, без управляющих символов |
| Пароль | 8–128 символов, без управляющих; сложность/регистры не навязываются |
| Challenge / resend / session | 15 минут / 60 секунд / 30 минут, задаются FakeAuthPolicy |
| Reset code / confirmation | 6 случайных цифр / случайный URL-safe token |
| Проверочное представление пароля | PBKDF2WithHmacSHA256, соль 16 байт, 120000 итераций, 256 бит. В unit/device contract — 1000 для скорости |
| Seed | Product `t02-v1`, counter `t01-v1`, auth snapshot schema `1` |
| Время | Auth в Android demo — system UTC; время мероприятий сохраняет прежний fixed Clock фикстур; в тестах оба Clock инъектируются; refreshSession нужен на foreground/проверке expiry, фонового таймера нет |

Android DI предоставляет `AndroidFakeAuthStorage` на AtomicFile, путь **context.noBackupFilesDir/demo-auth-v1.bin**, чтение/запись и проверка пароля выполняются в IO. JVM default — VolatileAuthStorage, тесты диска изолированы. Сохраняются accounts, имя, признак emailVerified, salted password proof, hashes challenges/grants, сроки и признаки потребления. До загрузки — Unknown, после нового процесса — Guest, требуется ручной вход. Неподдерживаемая/повреждённая схема блокирует auth через Unknown/StorageUnavailable до явного demo reset. `scenarios.reset()` синхронный debug/test reset; будущий debug UI должен вызывать его в IO.

**Не сохраняются:** активная сессия, raw пароли/коды/tokens, auth outbox, созданные guest registrations/tickets, ответы анкеты или клиентский guest QR-cache. Старую ссылку подтверждения можно обработать после нового процесса, если её секрет был получен до перезапуска: hash challenge сохранён. Outbox после рестарта пуст; повторная отправка после cooldown выдаёт новое письмо. Сохранение остальных fake-server данных перед H2/T05 остаётся частью T02.

Алгоритм доступен с API 26 по [SecretKeyFactory Android](https://developer.android.com/reference/javax/crypto/SecretKeyFactory); API 33 проверен фактически. Адаптер использует [AtomicFile](https://developer.android.com/reference/android/util/AtomicFile). Это не production auth/JWT/OAuth/refresh и не обещание криптографической защиты реальных аккаунтов.

## Outbox и ссылки

Outbox — **в памяти**, последние 64 auth-письма: `DemoBackendEntryPoint.environment().scenarios.authOutbox()`. Entry point находится только в `app/src/demo`. Письмо содержит challenge (id/email/deadlines), флаг reset и AuthSecret. В debugger/test разрешено прочитать `secret.value`; не выводить это значение в обычные логи. Экран/кнопка outbox в сборке пока не сделаны: это оставшаяся demo UI-интеграция после H1. Реальной отправки почты нет.

Только искусственные seed-аккаунты: `alex@example.com`, `host@example.com`, пароль **DemoPass123!**, оба email verified. Новые email создаются отдельным signUp, не guest submit. Имена и credentials не относятся к реальным пользователям.

Подготовленный allowlist: `eventpass-demo://demo/e/<eventId>` и `eventpass-demo://demo/confirm/<challengeId>?token=<secret>`. ID: `[a-zA-Z0-9_-]{1,64}`, token 16–128 URL-safe символов; другие host/port/userinfo/fragment/query/encoded paths отклоняются. Reset-ссылка не добавлена. Сейчас эти примеры применимы к parser/handler tests; запускать их как готовый auth flow в Activity нельзя. Прежний `eventpass-demo://demo/demo` для счётчика продолжает работать cold/warm.

## Выполненные проверки

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
$env:ANDROID_SERIAL = 'emulator-5582'
.\gradlew.bat :core:domain:test --rerun :data:fake:test --rerun :app:testDemoDebugUnitTest --rerun :core:platform:testDebugUnitTest --rerun :app:assembleDemoDebug :app:assembleDemoDebugAndroidTest :app:lintDemoDebug :app:verifyDemoIsolation --offline --console=plain --max-workers=2
.\gradlew.bat :app:connectedDemoDebugAndroidTest --offline --console=plain --max-workers=2
```

Итог: оба вызова **BUILD SUCCESSFUL (16s / 47s)**. Все 46 JVM unit-тестов исполнены повторно: domain 7, fake 25, app 12, platform 2, failures/errors/skipped = 0. Проверены signUp/confirm/manual login, чужие билеты, reset/cooldown/expiry/reuse/resend, холодное восстановление proof/challenges/grants, повреждённая схема и IOException без мутации, отмена/поздний login, busy, разрешённое назначение/однократное потребление, скрытые стеки и guards на уровне модели. Lint **0 ошибок / 4 version warnings**; baseline/исключения не добавлялись. Isolation успешна: только demoDebug, fake вне main implementation.

Все **7 device-тестов** прошли на отдельном EventPass_Insets_API33 (Pixel 7 / Android 13, три системные кнопки): новый contract проверяет тот же экземпляр auth в Hilt и реальные AtomicFile/PBKDF2/восстановление в новом экземпляре backend без сессии; прежние 6 проверяют DI/entry cleanup, nav/recreation/deep links/галерею и safeDrawing/нажатие профиля во всех трёх ориентациях. Это не новый process-death тест и не auth UI smoke. Жестовый прогон T03 не выдаётся за новый результат T04. Существующий пользовательский AVD не изменялся.

Логи конечных исходников: `.gradle/t04-final-unit-build.log`, `.gradle/t04-final-device.log`; XML — test-results соответствующих модулей и `app/build/outputs/androidTest-results/connected`; connected HTML — `app/build/reports/androidTests/connected/debug/flavors/demo/index.html`. Предварительный device-прогон `.gradle/t04-verified.log` завершился failure нового test-only Hilt entry point: он не входил в target graph. После переноса entry point в src/demo адресный contract прошёл (`t04-storage-device.log`), затем прошёл полный итоговый прогон. Этот предварительный failure не подменён успехом.

## Остаток до полной T04 и ручная проверка

До H1 можно проверить прежнюю галерею по её чек-листу из 17. Новых auth-экранов в APK нет. После подтверждения H1 необходимо:

1. Прочитать G03 `51:284`, G04 `28:50`, G05 `28:93` через доступный Figma plugin; на этой независимой итерации новые чтения Figma не выполнялись.
2. Реализовать Route/Screen/Hilt ViewModel и UiState всех auth-шагов, локализованные ошибки, keyboard/loading/Back, служебные confirm/reset поверхности и demo outbox controls.
3. Подключить coordinator/guards к текущей Navigation 3, неизвестную сессию закрыть Loading, guest startup без табов, Login → shell; связать владельцев корневых NavEntry с PrivateScope (одна очистка списков стеков не уничтожает root ViewModel); foreground/expiry refresh и уничтожение приватных saved-state/owners после logout/смены сессии проверить в живой Activity.
4. Подключить cold/onNewIntent доставку новых ссылок на границе Activity, передавая секрет сразу репозиторию; не сериализовать URI/secret в route или SavedStateHandle. Добавить узкий device smoke guest → login → logout → Back/restoration и cold confirmation.

Человеческий auth smoke **после этой интеграции**, не результат текущего APK: новая почта → SignUp → имитация письма → confirm → ручной Login; неверный пароль и неподтверждённая почта; reset по коду → новый пароль → ручной Login; возврат только к E без анкеты/submit; logout/Back/скрытые табы и expiry; поворот/клавиатура/font scale. После нового процесса аккаунт/challenge сохранены, сессии и outbox нет; созданные guest tickets до T05 persistence потеряются. H1/H2 за человека не отмечены.

Нет runtime API 28/37, физического устройства, полной TalkBack/визуальной приёмки, production API/писем/signing/App Links. CODE_REVIEW.md и посторонние правки плана/prompts сохранены вне коммита. Подагенты и новое полное code review не запускались. Следующий шаг — H1 и оставшаяся UI-часть T04; затем T05/H2 с расширением T02.
