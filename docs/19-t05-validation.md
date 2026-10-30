# T05 — мероприятие, регистрация и билеты

**30 октября:** актуальная запись пользователя подтверждает H2/T05 DONE; точный build/устройство приёмки не сообщены. На этом основании UI T06 завершён и передан на отдельный организаторский smoke ([code 11 / отчёт](20-t06-validation.md)); H3/H4 не закрыты. Прежние отметки ожидания ниже — история.

**Сохранённая сборка для H2:** после начала независимой T06 APK code 9 сохранён отдельно в `artifacts/apk/eventpass-0.5.1-t05-demo-code9.apk`, с прежним SHA-256 ниже. Основной output теперь code 10 foundation; H2 пользователь ещё не проверял. Этот отчёт относится к T05/code 9, не к новой T06. Schema 3 новой сборки не предназначена для чтения старым APK; H2 code 9 проверяется до установки foundation. [T06 и остаток](20-t06-validation.md).

## Исправление P2 ревью 1e54b80 — 29 октября 2026

**T05 — READY_FOR_HUMAN; H2 ожидается на исправленной сборке.** Текущий [app-demo-debug.apk](../artifacts/apk/eventpass-0.5.1-t05-demo-code9.apk): **0.5.1-t05-demo**, versionCode **9**, **33593307 байт**. SHA-256: `7012bf6f5733c9e5e0f0ad48d34bc3244618454e281c19839d577c5153dc51d7`. ApplicationId/minSdk/target/toolchain, зависимости, seeds `t02-v1`/`t01-v1` и server snapshot schema 2 прежние. Метаданные APK и debug-подпись v2 проверены. T03/T04 остаются DONE по прежним подтверждениям пользователя.

В EventViewModel завершение отменённой заявки A безусловно снимало submitting. После leaveForm и новой отправки B поздний finally A разблокировал поля/кнопку и позволял третий параллельный вызов C. Теперь finally изменяет submitting только при совпадении захваченного поколения формы с текущим — как и проверка результата. leaveForm сначала увеличивает поколение и отменяет предыдущую работу, поэтому её позднее завершение не трогает B. Ожидание завершения отменённого IO, блокировки main и автоматический retry не добавлены; актуальная B по-прежнему снимает busy после собственного завершения.

Добавлены две регрессии EventViewModelTest: для guest и authenticated submit. Реальные EventRepository/validation и тестовый RegistrationRepository с управляемыми continuations воспроизводят отложенное окончание отменённого вызова. Без sleep: A → leaveForm → B → завершить A → попытаться изменить поле/отправить ещё раз. Требуется, чтобы B оставалась busy, форма не менялась, вызовов было два и старый результат не публиковал ошибку/receipt/action. Затем завершается B: busy снимается, её Network отображается, новый явный retry разрешён. Проверяется UI-блокировка и число repository-вызовов; двойная выдача серверного билета этим не заявляется.

**RED:** на исходном EventViewModel оба новых теста упали: `expected:<2> but was:<3>`, 2 tests / 2 failures. XML сохранён до исправления. **GREEN:** все 7 EventViewModel-тестов проходят, затем повторно исполнены **73 JVM-теста**: domain 7 / fake 34 / app 29 / platform 3, failures/errors/skipped = 0. В app сохранены все восемь auth-регрессий. Собраны app/test APK, Hilt/DI, demo isolation и lint: **0 ошибок / 4 прежних предупреждения о версиях**, без baseline/исключений.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
.\gradlew.bat :app:testDemoDebugUnitTest --tests 'ru.eventpass.app.feature.event.EventViewModelTest.cancelled*FinallyCannotUnlockAnotherSubmission' --offline --console=plain --max-workers=2
# Выше — ожидаемый RED до исправления. После него:
.\gradlew.bat :app:testDemoDebugUnitTest --tests 'ru.eventpass.app.feature.event.EventViewModelTest' --offline --console=plain --max-workers=2
.\gradlew.bat :core:domain:test --rerun :data:fake:test --rerun :app:testDemoDebugUnitTest --rerun :core:platform:testDebugUnitTest --rerun :app:assembleDemoDebug :app:assembleDemoDebugAndroidTest :app:lintDemoDebug :app:verifyDemoIsolation --offline --console=plain --max-workers=2
```

Доказательства: `.gradle/t05-submit-race-red.log`, `t05-submit-race-green.log`, `t05-submit-race-check.log`; XML/lint/metadata/signature/hash — `.gradle/t05-submit-fix-evidence`. CODE_REVIEW.md и диагностические программы ревью не менялись; полное ревью и подагенты не запускались.

**Границы:** нового device/process-death/визуального прогона для code 9 нет. Прежние 18 device + 6 повторов и process/QR proof ниже относятся к code 8 и сохранены как история. Изменены управление busy и версия; UI-геометрия, repositories, persistence, DI/навигация/сохранение QR не менялись. Точный порядок поздних завершений доказан JVM-регрессиями, частота гонки на физическом устройстве не измерялась.

Для ручного H2 использовать **code 9 / hash выше**, demo-аккаунты и сценарии из исторического чек-листа ниже. Дополнительно: отправка → уход/возврат → новая отправка; пока актуальный запрос ожидает ответ, поля и повторная отправка остаются заблокированными. Точную задержку старой операции задаёт автоматический тест, обычный demo-запрос может завершиться слишком быстро для ручного наблюдения. H2 агентом не отмечена пройденной; после неё — T06.

## История T05 — 0.5.0-t05-demo / code 8, до исправления P2

Ниже сохранены прежние build/hash/команды/результаты. Их device/process доказательства не относятся к новому code 9.

29 октября 2026. Исходный HEAD `646a1e2`, задание `prompts/t05-registration-tickets.md`. **T05 — READY_FOR_HUMAN; H2 ожидается.** Во время работы пользователь ответил на вопрос по auth smoke APK `0.4.3-t04-demo` / code 7: **«Проверил, замечаний нет»**. T04 — DONE по этому подтверждению; устройство/Android не сообщены. H1 не переоткрывалась. Следующий этап после H2 — T06.

## Сборка для H2

[app-demo-debug.apk](../artifacts/apk/eventpass-0.5.1-t05-demo-code9.apk): **0.5.0-t05-demo**, versionCode **8**, **34121262 байт**, applicationId `ru.eventpass.android.demo`, minSdk 28, compile/target 37. SHA-256: `d631ac0839624986f103e99cdfa2f6c1031e1d2a0f5f1ee9f665800d37662337`. Debug-подпись v2 и aapt metadata проверены. Базовый seed `t02-v1`, технический счётчик `t01-v1`; фикстуры T05 дополнены описанием/программой и названиями событий. Seed-аккаунты `alex@example.com`, `host@example.com`, пароль **DemoPass123!**; новые аккаунты создаются отдельно, без автоматического входа.

Стек T04 сохранён, добавлена только `com.google.zxing:core:3.5.4` в core:platform. Сначала offline подтвердил отсутствие jar, затем штатная загрузка Gradle с TLS/certificate verification и успешный offline build. [POM](https://github.com/zxing/zxing/blob/zxing-3.5.4/core/pom.xml), [QRCodeWriter](https://github.com/zxing/zxing/blob/zxing-3.5.4/core/src/main/java/com/google/zxing/qrcode/QRCodeWriter.java), [лицензия Apache 2.0](https://github.com/zxing/zxing/blob/zxing-3.5.4/LICENSE). Лицензия и происхождение находятся в `core/platform/src/main/assets/licenses` и включены в APK. Камера/check-in не добавлены. Реально проверен текущий demoDebug/API 33, а не произвольные платформы или будущий production API.

## Реализованный путь и состояние

- Cold/warm `eventpass-demo://demo/e/open-registration` открывает общий EventDetails для гостя и участника. Повтор ссылки возвращает к существующему event flow, также из соседнего auth-экрана. Неизвестный ID — NotFound. Root Tickets работает без каталога и четвёртого таба; MyEvents/T06 и профиль CRUD остаются вне T05.
- Чтение/обновление/ошибка и отправка разделены. При ошибке refresh сохраняются последние видимые данные, отправка блокируется; Unknown не объявляется Unlimited. Full/Closed/Started/Finished/NotFound/StaleForm и транспортные/дисковые ошибки различимы. Отправка требует явного действия, автоматического повторения команды нет. Серверная гонка за последнее место не моделировалась.
- Имя любой письменности, email, российский optional phone, три вида вопросов и consent проверяются общим RegistrationValidator. Required/Optional/Hidden меняются в demo tools через одну policy. Email вошедшего аккаунта read-only; actor и его email берутся репозиторием из подтверждённой сессии. Available profile name подставляется, недостающий ввод не выдумывается.
- Guest submit создаёт registration/ticket и имитацию письма без аккаунта. Существующая почта — существующий Login с email/E; ответы очищаются до перехода. После входа — E с новой формой без submit. Уход с формы, на другой таб/профиль и удаление entry также очищают ответы; process saved state формы нет.
- G02 сразу отображает настоящий QR из выданного payload, номер/метаданные, сообщение об имитации письма и отдельные Login/SignUp. Компактный JSON GuestReceiptState записывается в SavedStateHandle владельца EventKey **до публикации receipt**. В route только eventId/flowId/entryId. Bitmap/payload не пишутся в route/URL/обычный лог; bitmap строится на Default dispatcher и остаётся временным.
- Receipt/fullscreen принадлежат одной EventViewModel; fullscreen — модальное окно без табов, его brightness override удаляется вместе с окном. Соседние auth entries оставляют исходный receipt в стеке; Back показывает прежний QR. Явное закрытие очищает snapshot, удаление owner освобождает его, новый EventKey имеет UUID и не наследует данные уходящей entry при pop. После окончания задачи гостевого доступа нет. Guest lookup по email/ticketId отсутствует.
- PublicNavigation остаётся у одного registry во время Loading → Guest. AppSessionViewModel не очищает публичный receipt при первичном разрешении гостевой сессии; прежние приватные stores по-прежнему очищаются. На входе в private scope публичные owners удаляются. Root/hidden cleanup, expiry, link retry и исходные insets assertions сохранены.
- Authenticated submit создаёт или возвращает собственный действующий/Used билет и выбирает его в Tickets. Отдельный SignUp → confirm → ручной Login показывает ранее выданные билеты на точный email. Один билет/E, доступ только к своим данным. Grant/ownership не присваивают чужие билеты; отзыв grant не удаляет участие.
- Tickets содержит текущие/историю, выбор, собственный QR, общий E и отмену с подтверждением. Active отменяется с освобождением места один раз; Used не отменяется. Cancelled/Used/завершённые события не показывают действующий QR. StartsAt не переводит Active билет в историю до endsAt/Finished. Новая заявка после cancel даёт другой токен. Выбор хранит ID и перечитывает данные через guard; logout/смена аккаунта уничтожают приватные owners.

## Контракты и сохранение fake-сервера

Добавлены submitAuthenticated(AuthenticatedRegistrationCommand без доверенного userId/email), observeOwnTicket, refreshMyTickets и cancelOwnTicket. Исходы чтения/StorageUnavailable/AuthenticationRequired/SessionChanged и отмены типизированы. Ready списка содержит только события собственных билетов. Точные модели — ProductRepositories, EventModels, RegistrationModels. Domain остаётся чистым Kotlin.

Один FakeEventPassEnvironment/MutableStateFlow/Mutex для auth/events/registrations/tickets/grants. ProductSnapshotCodec **schema 2** атомарно заменяет тот же `noBackupFilesDir/demo-auth-v1.bin`; название файла сохранено для совместимости T04. Внутри — legacy auth v1 record с proofs/challenge hashes и минимальные events/registrations/tickets/status/grants/policy/nextId. Старый auth-only schema 1 читается без потери аккаунтов/proofs/challenges; первая следующая запись переводит файл в schema 2. И auth-, и ticket-команды записывают весь общий snapshot, не затирая соседние данные.

IO и сериализация вне main, ожидание одного coroutine Mutex не блокирует caller. Ticket/mail/place state публикуются после успешной атомарной записи; IOException оставляет прежнее состояние и типизированный отказ. Durable server mutation при поздней отмене caller не объявляется rollback; клиент не получает ложного успеха/сессии. Восемь auth cancellation/generation/Mutex регрессий проходят. Повреждённая/неизвестная схема не сбрасывается автоматически и не перезаписывается следующей командой; восстановление — явный demo reset. Submitted answers — серверные записи, незавершённая анкета и UI flow не persist. Сессия, raw auth secrets, outbox и клиентский GuestReceipt отсутствуют в этом файле. QR payload в серверной ticket-записи допустим; гостевой UI не читает её для восстановления.

Auth Clock остаётся system UTC + временный demo offset, event Clock — fixed `2026-10-27T09:00Z` + отдельный временный offset. Оба offset сбрасываются новым процессом. Поэтому открытый seed не зависит от реальной даты; сценарий Finished меняет сохраняемый lifecycle, а +2 дня проверяет время текущего процесса. Сценарии можно явно сбросить. Никаких глобальных clock/Java/Gradle настроек не менялось.

## Реально исполненные проверки

**71 JVM-тест:** domain 7, fake 34, app 27, platform 3; failures/errors/skipped = 0. Общий forced прогон — 70 до добавления одной проверки ошибки чтения собственного билета; после неё app повторно исполнен (27). Проверяются миграция v1 → v2, отсутствие ложного commit, сохранение Active/Used/Cancelled/grants, guard чужого аккаунта, signup/confirm/manual Login, optional phone/Unicode/questions, busy/явный retry/StaleForm, очистка формы и saved receipt. QRCodeReader независимо от encoder декодирует созданные пиксели в исходный payload, включая Unicode.

**18/18 device-тестов** прошли на изолированном Pixel 7 AVD, Android 13/API 33, три системные кнопки; новых физических/gesture/API 28/37 прогонов нет. Пять новых RegistrationTicketsTest проверяют guest receipt/fullscreen/Back/recreation/закрытие и безопасный повтор; authenticated submit/cancel/history; RequiresLogin с новой формой; receipt → signup/outbox/confirm/Back/manual Login → свои Tickets; повтор E-ссылки из auth-соседа и NotFound. Тринадцать прежних auth/entry/navigation/insets проверок сохранены. Их setup/ожидаемый рабочий EventDetails и прокрутка к demo actions адаптированы к T05, assertions очистки и системного нажатия не ослаблены.

Журналы: `.gradle/t05-foundation-first.log`, `t05-persistence.log`, `t05-jvm-lint.log`, `t05-final-check.log`, `t05-release-check.log`. Финальный полный device прогон — `t05-release-check.log` (18/18, BUILD SUCCESSFUL). Два ранних полных прогона завершились одним timeout в прежнем WindowInsetsRegressionTest после системного tap; все геометрические проверки проходили. Отдельный неизменённый insets test прошёл 1/1 (`t05-insets-recheck.log`), затем прошёл общий набор. Причина нестабильности системного ввода окончательно не установлена; это не заявляется исправлением кода приложения или новым подтверждённым перекрытием. Неуспешный XML сохранён в `.gradle/t05-device-red`, логи не заменены успехом.

После полного device набора удалены три неиспользуемые строки (`t05-resource-cleanup.log`), затем по снимку G01 исправлены ширина карточки метаданных и компактные горизонтальные строки программы. После этой последней правки повторены app unit (27), assemble app/test APK, lint/isolation и **6/6 device** (все пять RegistrationTicketsTest + WindowInsetsRegressionTest) — `t05-layout-check.log`. Остальные проверки полного набора относятся к предыдущей геометрии G01. Именно окончательный APK установлен и заново прошёл process proof ниже. Lint: **0 errors / 4 прежних version warnings**, без baseline/исключений. Hilt/DI, app/test APK и изоляция src/demo/fake успешны.

### Настоящая смерть процесса и QR

Исполнен `scripts/verify-t05-process-state.ps1` на **итоговом APK с hash выше**. До начала установлен APK; между submit/death/restore/new-task **нет установки, очистки данных или force-stop**. Скрипт вводит синтетическую почту через UI, получает receipt, открывает fullscreen, сохраняет screenshot, сворачивает приложение, выполняет `am kill` фонового demo-процесса и восстанавливает launcher-ом ту же task. PID меняется, taskId проверяется. `VerifyTicketQr.java` через отдельный decoder читает QR из обоих screenshot и требует тот же payload, не выводя секрет.

Результат: `PASS: real background process death (11103 -> 11561), same task 211, same QR; fresh task has no receipt; fake-server registration persists.`

После этого отдельный `NEW_TASK | CLEAR_TASK` только для demo-пакета удаляет исходную задачу: новый запуск — Login без guest receipt. Повтор по той же синтетической почте возвращает ExistingRegistration, подтверждая сохранение серверной записи без гостевого lookup. Это отдельный шаг, не замена OS-restoration. Портретный Pixel 7; на произвольном устройстве runtime не обещается. ActivityScenario.recreate из device-теста отдельно не считается смертью процесса.

Доказательства: `.gradle/t05-layout-process.log`, `.gradle/t05-layout-process-proof/before.png`, `after.png`, `event.png`; сводка XML/lint/metadata/signature — `.gradle/t05-evidence` (полные 18 в `device`, последние 6 в `device_layout`). Две ранние ошибки подготовки скрипта произошли до смерти процесса: клавиатурный Back и частично видимые координаты UI. Исправлена автоматизация (проверка IME, видимые bounds и новая UI-задача), приложение не подгонялось под неё. Их логи `.gradle/t05-process.log` и `t05-process-final.log` сохранены.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
$env:ANDROID_SERIAL = 'emulator-5584' # выбранный изолированный AVD
.\gradlew.bat :core:domain:test --rerun :data:fake:test --rerun :app:testDemoDebugUnitTest --rerun :core:platform:testDebugUnitTest --rerun :app:assembleDemoDebug :app:assembleDemoDebugAndroidTest :app:lintDemoDebug :app:verifyDemoIsolation :app:connectedDemoDebugAndroidTest --offline --console=plain --max-workers=2
# После последней функциональной правки повторены app unit/build/lint/isolation и весь device-набор.
.\scripts\verify-t05-process-state.ps1 -Adb 'C:\Users\XataB\AppData\Local\Android\Sdk\platform-tools\adb.exe' -Serial $env:ANDROID_SERIAL
```

## Дизайн и реальные ограничения

Свежий get_design_context со screenshot G01 `28:175` получен, затем создана file session `b1d9f8d3-ea78-4d11-a168-875bb7862768`. Следующие чтения G02 `51:193`, P01 `28:257`, P02 `14:8394` ограничены Starter rate limit. G03 `51:284` остаётся принятым auth T04; нового визуального сравнения G03 нет. Для недоступных frames использованы локальный `sources/figma-2026-10-21.json`, согласованные B02/B04 и core:designsystem/Inter. Нативная общая шапка/Back — существующая Android-оболочка. Brand/TicketAction/StatusDot переиспользуют UI-кит; макет G01 сам содержит placeholder обложки, нового bitmap asset нет. Обложки/программа — fixture data. Точное соответствие всех новых экранов, TalkBack, IME/font scale/ширины и яркость на физическом экране остаются H2.

Нет реальной почты/сервера, production signing/App Links, PDF/Wallet, камеры/check-in, каталога, редактора, глобального User.role, постоянного гостевого кабинета/guest cache. Подписки локальны для fake, два устройства не разделяют данные. T02 целиком не закрыта: editor/check-in и другие команды ещё впереди. CODE_REVIEW.md не изменялся; подагенты и полное ревью не запускались. Пользовательские docs/prompts/daemon config сохранены вне автоматического добавления в commit.

## Ручной H2 на code 8 — ожидается

1. Открыть `eventpass-demo://demo/e/open-registration` холодным и повторным/warm входом. Удобный путь: Login → **Демо-инструменты → Открыть demo-мероприятие**. Галерея/счётчик также в demo tools. Reset — там же; сбрасывает только demo backend и оба offset. Счётчик имеет отдельный seed.
2. Новый email + имя любой письменности, optional phone/российский номер, вопросы и consent → QR сразу, сообщение об имитации письма, аккаунт не создан. Открыть fullscreen/Back, Login/SignUp и Back, повернуть Activity/восстановить ту же задачу. Затем явно закрыть билет/задачу: нового guest QR нет. Повтор email не раскрывает старый QR.
3. Из receipt отдельно SignUp на тот же email → **Имитация почты** → подтверждение → ручной Login. В Tickets виден прежний билет. Выйти/запустить новый процесс/войти снова: билет остаётся. Под другим аккаунтом его нет.
4. Existing account (`alex@example.com`) в гостевой форме → Login с почтой → ручной вход → E с новой формой без автозаявки. Войти без назначения → Tickets. Отправить авторизованную форму, проверить выбор выданного билета, просмотр/историю, fullscreen и переход к тому же E.
5. Отменить Active с подтверждением: Cancelled/без QR/место освобождено один раз. Записаться вновь: другой билет/токен. **Демо-инструменты → Сделать первый свой билет Used**: Used виден в истории, без отмены/действующего QR. Полный/закрытый/начавшийся/завершённый E, stale/network/timeout/read/cancel error и phone policy задаются явными demo controls; после ошибки повтор вручную. Время событий +2 дня отдельно от auth +31 минута.
6. Logout/Back/другой аккаунт, истечение auth, скрытые табы, пустые Tickets/история, обе альбомные ориентации/системные панели, 360–412dp, font scale 1.3/2.0, клавиатура, TalkBack, чтение QR другим устройством и восстановление яркости. Записать build/hash, Android/устройство и замечания. **H2 агентом не пройдена.**
