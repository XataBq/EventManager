# T04 — auth UI, session gate и навигация

## Ручная приёмка T04 — 29 октября 2026

**T04 — DONE.** На вопрос по auth smoke APK `0.4.3-t04-demo` / code 7 пользователь ответил **«Проверил, замечаний нет»**. Устройство/Android не сообщены. Подтверждение относится к T04, не закрывает H2. После этого выполнена T05; её текущий APK/проверки/H2 — [19-t05-validation.md](19-t05-validation.md). Ниже сохранены отчёты T04 до приёмки, включая прежние READY_FOR_HUMAN/ожидание smoke.

## UI-интеграция T04 — 29 октября 2026, READY_FOR_HUMAN

H1 подтверждена пользователем 29 октября; T03 — DONE. Оставшаяся T04 реализована после этого подтверждения. **T04 — READY_FOR_HUMAN; auth smoke и H2 не отмечены пройденными.** Следующая задача после приёмки auth — T05/H2.

Текущий [app-demo-debug.apk](../app/build/outputs/apk/demo/debug/app-demo-debug.apk): **0.4.3-t04-demo**, code **7**, **33155884 байт**, applicationId `ru.eventpass.android.demo`, minSdk 28, compile/target 37. SHA-256: `aed6b318515dc0ffb7ee438aa2bdc4d83ede65a46db74d14cc5667d135f5eeac`. Debug-подпись v2 и метаданные APK проверены. Seeds `t02-v1` / `t01-v1`, snapshot schema 1; зависимости и toolchain не обновлялись. Пользовательский `gradle-daemon-jvm.properties` сохранён вне коммита, его критерий — JDK 21.

### Что доступно в UI

- Гостевой старт — Login без нижних табов, SignUp, подсказка о ссылке организатора. Имя любой письменности, email, пароль и согласие; дополнительных обязательных полей нет. Typed errors выбирают русские string resources. SignUp и подтверждение email не открывают сессию; после подтверждения показан Login с email и сообщением о ручном входе.
- Восстановление: email → код → новый пароль → ручной Login. Пароли, код и grant остаются в памяти ViewModel текущей entry; на диск, в route и SavedStateHandle не попадают. При восстановлении нового процесса секреты потеряны, reset начинается с email. После изменения пароля старый не подходит. Повтор отправки имеет cooldown, его UI-таймер и таймер сессии работают только при STARTED.
- После Login — Билеты/Мероприятия/Профиль; Profile показывает email и SignOut. Tickets/MyEvents честно остаются незавершёнными поверхностями T05+. Ссылка `eventpass-demo://demo/e/open-registration` открывает минимальный приёмник eventId. Вход с него возвращает к E без анкеты и автоматического submit; назначение потребляется один раз, поворот его не дублирует.
- SessionCoordinator принадлежит Activity ViewModel. Каждый PrivateScope и каждый таб получают отдельный parent ViewModelStore; все стеки декорируются и в скрытых табах. При logout/expiry/смене личности stores очищаются сразу, композиция/saveable state отделены ключом сессии. Root и hidden owners проверены на устройстве: viewModelScope отменены, новый аккаунт получает новые экземпляры; переключение табов/поворот действующей сессии сохраняют состояние.
- Проверка сессии на foreground и один таймер до expiresAt; без polling и сервиса. Demo Clock двигает только auth-время. Общая AuthNavigationPolicy подключена: Unauthenticated → Login с назначением, Forbidden → сообщение/удаление недоступного маршрута без повторного Login. Demo-проверка canScan для `alex-owned` использует access из того же EventRepository, что и fake; scanner/dashboard не реализованы.
- Cold `onCreate(ACTION_VIEW)` и warm `onNewIntent` передают доставку AuthLinkHandler через Activity ViewModel. URI очищается из Intent, transient delivery/retry не сериализуется; поворот не отменяет обработку. Ошибка оставляет явный retry с прежним requestId; успех очищает секрет. Разделение inFlight/handled и все восемь прежних auth-регрессий сохранены. Legacy `eventpass-demo://demo/demo` открывает только технический счётчик, без обхода auth shell.

**Демо-инструменты** доступны из Login и корневых экранов: UI-кит, счётчик, имитация почты, следующий Network/Timeout, auth-время +31 минута и reset. Всё concrete fake/debug UI — только src/demo. **Имитация почты** показывает email, кнопку подтверждения или reset-код; это память процесса, реальной отправки нет. После рестарта outbox пуст; accounts/proofs/challenge hashes сохранены в `noBackupFilesDir/demo-auth-v1.bin`, сессия — Guest. Повтор после cooldown создаёт новое письмо. Искусственные seed-аккаунты: `alex@example.com`, `host@example.com`, пароль **DemoPass123!**.

Поддерживаются строгие пути `/e/<eventId>` и `/confirm/<challengeId>?token=<secret>` на `eventpass-demo://demo`; reset остаётся по коду. В manifest уже был фильтр этого scheme/host, расширять его не понадобилось. Проверка путей/query выполняется существующим parser, реального домена/assetlinks нет.

### Проверки и границы доказательства

В этой итерации фактически исполнены **56 JVM-тестов**: domain 7, fake 25, app 22, platform 2; failures/errors/skipped = 0. После последних изменений app-тесты повторены. Две новые проверки ViewModel покрывают busy/retry, signup/reset через общий backend, ручной вход и отсутствие секретов в SavedStateHandle. Собраны app/test APK, Hilt/DI, demo isolation и lint: **0 ошибок / 4 прежних уведомления о версиях**, baseline/исключений нет.

Все **13 device-тестов** прошли на изолированном Pixel 7 AVD / Android 13 / API 33 / три системные кнопки. Семь прежних проверок сохранены, их setup теперь выполняет явный вход. Шесть новых проверяют root/hidden owner cleanup и смену аккаунта; warm confirmation + Network/retry/rotation; cold Activity confirmation; возврат к E/rotation/foreground expiry; полный UI signup/outbox/confirm/manual Login/reset; Forbidden и Unauthenticated по event-scoped access. Нет нового прогона с жестами, OS process-death auth, API 28/37 или физического устройства. Cold Activity и восстановление backend из AtomicFile — разные проверки, они не объявляются смертью процесса.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
$env:ANDROID_SERIAL = 'emulator-5584'
.\gradlew.bat :core:domain:test --rerun :data:fake:test --rerun :app:testDemoDebugUnitTest --rerun :core:platform:testDebugUnitTest --rerun :app:assembleDemoDebug :app:assembleDemoDebugAndroidTest :app:lintDemoDebug :app:verifyDemoIsolation --offline --console=plain --max-workers=2
.\gradlew.bat :app:testDemoDebugUnitTest :app:assembleDemoDebug :app:lintDemoDebug :app:verifyDemoIsolation :app:connectedDemoDebugAndroidTest --offline --console=plain --max-workers=2
```

Логи: `.gradle/t04-ui-full-build.log`, `t04-ui-final.log`, `t04-ui-release-check.log`; итоговые XML/lint/metadata — `.gradle/t04-ui-evidence`. После установки итогового APK выполнен cold MAIN-запуск и осмотрен Login без нижних табов; снимок — `.gradle/t04-ui-evidence/t04-ui-login.png`. Это ограниченная проверка стартового экрана, не ручная приёмка всех новых поверхностей. Начальные `t04-ui-slice-device.log`, `slice2.log`, `slice3.log`, `full-device.log` содержат неуспешные прогоны: ActivityScenario терял изменённый launch Intent, probe после recreate требовал повторного подключения, две проверки не ждали IO/выбирали несколько писем. Найдена и исправлена ошибка UI: email после confirmation не обновлялся в уже существующем Login; теперь он StateFlow, device-тест требует предзаполнение. Эти промежуточные результаты не подменены успехом.

Свежие чтения Figma `create_session` и `get_design_context` завершились ошибкой соединения MCP. G03 `51:284`, G04 `28:50`, G05 `28:93` не сверены с живыми макетами/скриншотами. Использованы локальный `sources/figma-2026-10-21.json`, docs/04 и готовый core:designsystem/Inter; устаревшее обещание сохранения анкеты из G03 исключено по B04. Новых bitmap assets нет. Проверка новых экранов человеком, TalkBack, IME/ширины/font scale остаётся auth smoke; H1 не переоткрывается. Основания lifecycle/ownership: [Navigation 3 state](https://developer.android.com/guide/navigation/navigation-3/save-state), [repeatOnLifecycle](https://developer.android.com/topic/libraries/architecture/coroutines); реальная совместимость проверена на установленном стеке.

### Короткий ручной auth smoke на code 7

1. На новом запуске проверить Login без табов. Ввести новую почту/имя/пароль, отметить согласие, создать аккаунт. Открыть «Имитация почты», нажать подтверждение: Login с email/сообщением, пароль вводится вручную. Проверить неверный пароль и неподтверждённую почту.
2. Войти; открыть «Профиль», сверить email. Через «Демо-инструменты» включить Network/Timeout для следующего auth-запроса и проверить ошибку/ручной повтор. Для confirmation отказ включать перед открытием письма; повтор доступен в диалоге.
3. Выйти → «Забыли пароль?» → email → код из demo outbox (через Back вернуться к коду) → новый пароль → ручной Login. Старый пароль не подходит, reset не логинит и не подтверждает email сам.
4. Открыть `eventpass-demo://demo/e/open-registration`, нажать «Войти»; после Login вернуться только к E, без формы/submit. Повернуть до и после входа. Из «Демо-инструменты» проверить доступ `alex-owned` под alex и host: права различаются по E, Forbidden не вызывает Login.
5. Открыть счётчик, изменить его, переключить табы и пересоздать Activity: значение сохраняется. После logout/Back/recreation, входа другим аккаунтом и auth-времени +31 минута прежние приватные данные не возвращаются. При новом процессе сессии и outbox нет.
6. Проверить поля/ошибки/клавиатуру, прокрутку, обе альбомные ориентации, системные панели и крупный текст. Галерея: «Демо-инструменты → Открыть UI-кит», доступна также гостю. Проверить TalkBack. Записать сборку, устройство/Android и замечания.

T05, анкета/QR, tickets persistence, камера, профиль CRUD, production API/почта/signing/App Links не входят в этот срез. CODE_REVIEW.md и посторонние изменения сохранены; подагенты и полное ревью не запускались. Ниже — история foundation и её прежние APK/результаты, не описание текущего UI.

## История foundation

28 октября 2026. Основание: `docs/prompts/t04-auth-navigation.md`, HEAD до работы `2c035a8`, дополнение CODE_REVIEW.md от 28 октября. H1 в сообщениях и документации не подтверждена. Выполнена разрешённая независимая часть. **T04 — IN_PROGRESS; T03 — READY_FOR_HUMAN; H1/H2 не закрыты.**

## Исправление P2 после ревью 055c14b — текущая сборка

Текущий [app-demo-debug.apk](../app/build/outputs/apk/demo/debug/app-demo-debug.apk): **0.4.2-t04-foundation-demo**, code **6**, **32334566 байт**. SHA-256: `54c078050e87905d2cea567196249bbf1fbc39c13ea62eb68d3fafe994e43ce0`. ApplicationId `ru.eventpass.android.demo`, minSdk 28, compile/target 37; aapt metadata и debug-подпись v2 проверены. Toolchain/библиотеки и auth snapshot schema 1 прежние. Seeds: product `t02-v1`, counter `t01-v1`.

В `055c14b` принятие подготовленной сессии на caller захватывало Java-монитор, который другая auth-операция удерживала во время записи snapshot. Новый тест воспроизвёл блокировку потока вызова. Исправление:

- Все мутации общего FakeEventPassEnvironment, включая auth, guest submit и ScenarioController, используют **один coroutine Mutex**. Нет разных механизмов блокировки одного state. Ожидание Mutex приостанавливает корутину и освобождает поток caller для следующей задачи; поведение suspend lock описано в [официальной документации Mutex.lock](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.sync/-mutex/lock.html). Реально проверена версия coroutines **1.10.2** из проекта, без обновления на версию online-документации.
- Сессия по-прежнему принимается на caller после отменяемого возврата из IO. После получения Mutex повторно проверяются активность, generation, аккаунт и срок сессии; после публикации до возврата нет suspend/смены dispatcher. Отмена в очереди ожидания не публикует Authenticated, не открывает Shell. Уже выполненные другими операциями изменения state сохраняются.
- Snapshot и PBKDF2 остаются в IO. Изменяющие debug/test-методы ScenarioController стали `suspend`; reset сам переключается в IO. Синхронные чтения accounts/outbox и domain-порты не изменились. Все существующие вызовы debug API компилируются в корутинах.

Две новые проверки `AuthFoundationRegressionTest` готовят signIn до возврата на caller, затем удерживают запись **другого signUp**. Продолжение входа и следующая задача выполняются через реальный однопоточный executor, моделирующий UI. Следующая задача обязана исполниться **до освобождения записи**, а вход ещё остаётся незавершённым/Guest. В варианте отмены signIn завершается отменой также до освобождения writer и сохраняет Guest/Login. В обычном варианте после writer вход принимается и открывает Shell; новый аккаунт конкурирующей операции сохранён. Порядок задают latch/очередь dispatcher, без sleep; ожидания ограничены таймаутами.

**До исправления:** обе новые проверки на production-коде `055c14b` завершились failure, диагностика в обоих случаях `state=BLOCKED`; 2 tests / 2 failures, `.gradle/t04-lock-red.log` и `t04-lock-red.xml`. **После исправления:** все 8 auth-регрессий (включая прежние отмену/retry/generation) и 25 fake-тестов прошли, `.gradle/t04-lock-green.log`. Затем выполнена итоговая проверка:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
.\gradlew.bat :core:domain:test --rerun :data:fake:test --rerun :app:testDemoDebugUnitTest --rerun :core:platform:testDebugUnitTest --rerun :app:assembleDemoDebug :app:assembleDemoDebugAndroidTest :app:lintDemoDebug :app:verifyDemoIsolation --offline --console=plain --max-workers=2
$env:ANDROID_SERIAL = 'emulator-5584'
.\gradlew.bat :app:connectedDemoDebugAndroidTest --offline --console=plain --max-workers=2
```

Оба вызова **BUILD SUCCESSFUL (32s / 47s)**. Все **54 JVM unit-теста** заново исполнены: domain 7, fake 25, app 20, platform 2; failures/errors/skipped = 0. Собраны app и test APK, Hilt/DI и demo isolation прошли. Lint **0 errors / 4 version warnings**, новых исключений/baseline нет. Все **7 device-тестов** прошли на изолированном EventPass_Insets_API33 / Pixel 7 / Android 13 / API 33 / три кнопки (navigation_mode=0): auth storage/DI contract и прежние nav/entry/gallery/insets проверки, включая нажатие профиля в трёх ориентациях. Пользовательский AVD не изменялся; тестовый эмулятор после проверки остановлен.

Логи: `.gradle/t04-lock-build.log`, `.gradle/t04-lock-device.log`; копии результатов — `.gradle/t04-lock-evidence`. Настоящий Android ANR не воспроизводился и этой JVM-регрессией не доказан; новый device-прогон не является проверкой auth UI, жестов или process death. CODE_REVIEW.md и посторонние plan/prompts сохранены. **T04 остаётся IN_PROGRESS, H1/H2 ожидаются**: нового auth UI/Activity wiring нет, человек проверяет прежнюю галерею через **Билеты → Открыть UI-кит** по [чек-листу H1](17-t03-validation.md). Остаток и будущий auth smoke перечислены ниже. Исторические версии/hash в следующих разделах не относятся к текущему output APK.

## Исправления P2 после ревью cfc0624 — история 055c14b

APK этапа `055c14b`: **0.4.1-t04-foundation-demo**, code **5**, 32318182 байта. SHA-256: `518e3c5b3fcd88bae3c339083e9858c1b802d5b0e38f847438fd504031257f46`. ApplicationId/SDK прежние, aapt metadata и debug-подпись v2 проверены. Версии библиотек и snapshot schema 1 не менялись. H1 не подтверждена, T04 остаётся IN_PROGRESS; нового auth UI/Activity wiring нет.

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

Android DI предоставляет `AndroidFakeAuthStorage` на AtomicFile, путь **context.noBackupFilesDir/demo-auth-v1.bin**, чтение/запись и проверка пароля выполняются в IO. JVM default — VolatileAuthStorage, тесты диска изолированы. Сохраняются accounts, имя, признак emailVerified, salted password proof, hashes challenges/grants, сроки и признаки потребления. До загрузки — Unknown, после нового процесса — Guest, требуется ручной вход. Неподдерживаемая/повреждённая схема блокирует auth через Unknown/StorageUnavailable до явного demo reset. После исправления P2 `055c14b` метод `scenarios.reset()` — suspend и сам переключается в IO; debug UI должен вызывать его из корутины.

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
