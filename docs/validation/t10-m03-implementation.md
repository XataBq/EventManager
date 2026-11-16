# T10 M03 — регистрации, билеты и серверный PDF

## STATUS / SCOPE

**IMPLEMENTED / REVIEW_PENDING, 16 ноября 2026.** База `a5daf87`, ветка `codex/t10-api-integration`. Target — коммит, содержащий этот отчёт и [source manifest](t10-m03-target-manifest.json). M03 выполнен одним агентом по прямой команде пользователя; субагенты не запускались. M02 fix-review остаётся открытым, не объявляется пройденным этим отчётом. M04–M09 не реализованы. Ручная приёмка после всего цикла.

При начале tracked дерево чистое; единственный untracked `gradle/gradle-daemon-jvm.properties` не изменён и не включается в коммит. CODE_REVIEW.md в реализационном проходе не менялся. Стек/версии зависимостей не обновлялись; включена генерация стандартного BuildConfig для flavor UI, versionCode=23, versionName=0.10.3-m03. Неподключённые remote editor/scanner/attendance/profile mutation/XLSX операции не выдают ложный успех.

## REQUIREMENTS → IMPLEMENTATION → EVIDENCE

| Критерий | Реализация | Доказательство |
|---|---|---|
| Guest/auth POST и typed answers, consent, optional phone | RemoteRegistrations, AuthHttp Idempotency-Key, существующий session gateway | RemoteTicketsTest: boolean/string[]/phone empty/key/no local formVersion; staging authenticated registration |
| Guest autoaccount без login, EMAIL_TAKEN | accountCreated/accessToken в компактном receipt, отдельный passwordless fake account/setup-link | RemoteTicketsTest, M03GuestRegistrationTest, изменённые auth/ticket persistence tests; TokenStore не получает guest token |
| Неизменный retry, cooldown, неизвестный исход | EventViewModel pending command в памяти; saved boolean; adapter fingerprint/24h guard | RemoteTicketsTest lost response/changed body/429/late login, M03RegistrationStateTest; нет фонового retry/сохранения анкеты |
| ALREADY_REGISTERED без выдуманного ticketId | Auth adapter проверяет собственные upcoming/past tickets по eventId | RemoteTicketsTest.alreadyRegisteredWithoutTicketIdResolvesOnlyThroughOwnTickets |
| Server scopes/counters/summary, status/EVT/Used/Cancelled | TicketEventSummary, nullable registrationId/Event, общий TicketsRoute/VM | RemoteTicketsTest scopes/cancel/Used, M03TicketsStateTest; server status не заменяется client clock |
| Обновление только видимого ресурса, private cleanup | Scoped list refresh single-flight; selected card own GET; owner/session guards | RefreshOwnersTest, M03TicketsStateTest; Forbidden убирает QR/карточку |
| Guest capability lifetime/recreation/close | GuestReceiptState текущего EventKey; manual code/accountCreated/capability в saved state | M03RegistrationStateTest; прежние receipt/finally/accepted submit tests сохранены. OS process kill отдельно NOT_RUN |
| PDF/SAF, MIME/size/owner, без DTO/bytes в VM | DocumentSnapshot.Remote/DocumentSource, RemoteTicketDocuments, AndroidDocumentFiles | RemoteTicketsTest guest query without bearer/MIME; DocumentsViewModelTest; настоящий staging PDF |
| Fake совместим с новыми правилами и старым файлом | Passwordless proof отсутствует в EPA2, schema6/manual proof сохранены; bounded fake replay | 102 fake tests; v1–v5/codec/migration/check-in proofs сохранены; fixture downgrade описан ниже |

## ARCHITECTURE / MIGRATIONS

[Фактическая архитектура M03](../architecture/t10-registration-tickets.md), ADR-T10-08 в [плане](../34-api-migration-plan.md). Обновлены 02/03/05, ownership/responsibilities/operations/invariants, navigation/mapping/AGENTS/status entries. Server TicketEventSummary не превращается в Event с вымышленным owner; registrationId=null отражает отсутствие поля. Scope/counters принадлежат server response. Неполные данные не заполняются now/1/fake IDs.

Guest success не меняет session; capability не сохраняется в TokenStore/Room/DataStore/NavKey. Saved metadata неизвестного POST не содержит body. После process restore без тела предлагается сверка tickets/письмо/setup, новый POST не отправляется автоматически. Existing email очищает анкету до login; autosubmit отсутствует.

Schema6/EPA2 не повышены: nullable password proof и отдельное поле manualCode уже были в формате. Новые fake записи имеют EVT-code; старые QR/manual/check-in proofs не переписаны. Новый v2 migration fixture явно создаёт legacy manual proof перед искусственным downgrade нового ticket: формат v2 не содержит EVT manual поля. Отзыв reset links при legacy restore — прежняя политика M01, verification/password proofs проверяются полностью. Snapshot writer требует настоящий fake registrationId, remote ticket projection не записывается в этот файл.

PDF buffer ограничен 16 MiB в data; VM получает transient source, не bytes. Platform сначала пишет private temporary, проверяет owner и затем SAF, удаляет temporary в finally. HTTP PDF ошибки передаются typed DocumentFailure; 401 текущего bearer отзывает локальную session, guest capability не становится bearer. Remote ticket resend скрыт; полное удаление legacy demo resend остаётся M08.

## CHECKS

PowerShell, JAVA_HOME=`C:\Program Files\Android\Android Studio1\jbr`. Локальный ignored `m01-verified-google.init.gradle` использован как прежний exact-artifact offline workaround; TLS/версии не менялись. Итоговая команда:

```powershell
.\gradlew.bat :core:domain:test :data:fake:test :data:remote:testDebugUnitTest :app:testDemoDebugUnitTest --tests '*EventViewModelTest' --tests '*M03*Test' --tests '*RefreshOwnersTest' --tests '*DocumentsViewModelTest' :app:assembleDemoDebug :app:assembleRemoteDebug :app:lintDemoDebug :app:lintRemoteDebug :app:verifyDemoIsolation -I .gradle/m01-verified-google.init.gradle --offline --console=plain --max-workers=2
```

**Exit 0, BUILD SUCCESSFUL 2m08s, 243 tasks (112 executed/131 up-to-date).** XML: domain 29 passed; fake 102 passed; remote 38 tests, 35 passed/3 opt-in staging skipped; app demo targeted 24 passed. Всего **190 passed / 3 skipped / 0 failures/errors** (193 invocations). Весь app suite/все flavors test suites не запускались; перечислены только реально выполненные. Remote Auth/Public tests включены, поскольку расширен общий HTTP/session gateway. Оба APK собраны; lint demo 21 warnings, remote 44 warnings, **0 errors**; notices/unused resources оставшихся flavor surfaces не исправлялись. verifyDemoIsolation успешно: fake отсутствует в main/remote runtime.

Лог `.gradle/m03-final-verification.log`, отдельные копии XML/summary до фильтрованного smoke — `.gradle/m03-evidence/joint-results` и `joint-summary.json`. Промежуточные проверки были адресными: compile domain/data/platform 8s, обе app compile 22s, VM tests 9s. Полный fake прогон сначала выявил 7 старых guest/signup ожиданий; они заменены новым setup/manual login поведением с сохранением ownership/migration assertions. После нового EVT-кода искусственный v2 downgrade fixture исправлен как описано выше; финальный fake прогон 102/102 зелёный. Это не пропущенные failures и не удаление проверок ради зелёного результата.

### Реальный staging

HTTPS OpenAPI version 1.1.1; M03 paths и схемы Ticket/TicketEvent/Registration/RegistrationRequest/MyTickets структурно совпадают с локальным backend snapshot. Проверка — 16 ноября.

`EVENTPASS_STAGING_SMOKE=1` + `:data:remote:testDebugUnitTest --tests ru.eventpass.data.remote.StagingTicketsSmokeTest` с теми же offline/init параметрами: **exit0, 1 passed/0 skipped, Gradle 7s**, повторён на окончательном коде после совместного прогона. Сценарий: synthetic signup → own live event → authenticated registration → upcoming list → own card → настоящий PDF → cancel → past list → finally DELETE /me + local logout. Созданы только собственные синтетические ресурсы; cleanup успешен. Это не UI/device E2E и не human acceptance. XML сохранён отдельно в `.gradle/m03-evidence/final-staging-results`, лог `m03-final-staging.log`. Эту одну invocation не прибавлять к пропущенному staging case как будто он запускался в local suite.

### APK

- [demo 0.10.3-m03-demo, code23](../../artifacts/apk/eventpass-0.10.3-m03-demo-code23.apk): 44735603 bytes; SHA-256 `796e58dd96c7afcea08a2ff112f8eca4332ab30ecfb22d9ecaea186c764d1364`.
- [remote 0.10.3-m03-remote, code23](../../artifacts/apk/eventpass-0.10.3-m03-remote-code23.apk): 45658662 bytes; SHA-256 `8fd1104830fea3d814a336efa6f3caffe83fa94cfb3a281deefc8829a047d147`.

Локальные APK/evidence ignored и не являются частью Git. Прежние code20–22 не перезаписывались.

После совместного прогона адаптирован старый RegistrationTicketsTest: guest setup вместо повторного signup, отсутствие auto-session, возврат к receipt и ручной login. Выполнена только компиляция `:app:compileDemoDebugAndroidTestKotlin :app:compileRemoteDebugAndroidTestKotlin`: exit0, 6s, 112 задач. Device-тесты не запускались; APK приложения от этих test-only правок не изменился. Deprecation warnings старого Compose test rule оставлены вне M03.

## NOT_RUN / RISKS

- Независимое ревью M03 ещё не выполнено; повторное ревью исправления M02 также не закрыто. Самопроверка автора не выдаётся за независимую.
- Device/UI/физический телефон/OS process death/SAF provider matrix/TalkBack/fontScale/Figma сверка не запускались. Saved metadata/recreation invariants подтверждены unit/state tests, не новым OS-kill. Ручная приёмка только в конце M09.
- Реальный guest staging submit/setup/mail не запускался: без доступа к почте невозможно завершить setup и удалить созданный passwordless account; не оставлялись неочищаемые гостевые fixtures. Guest protocol/PDF capability проверены MockWebServer, account/setup — fake. Authenticated server путь проверен отдельно с cleanup.
- App Links отложены; legal URLs/test mail и multi-device conditional PATCH остаются прежними внешними условиями. M04–M09 не покрываются этим срезом.
- Bounded fake server replay живёт только в памяти среды; durable replay сервера после cold-start не обещается. Android guest cache не добавлен.

## HANDOFF

Следующий шаг — [отдельное последовательное ревью M03](../prompts/t10-m03-review.md) по base a5daf87 и implementation commit/manifest, затем разрешённое исправление находок и повторное ревью. Только после технического принятия M03 — M04. Без субагентов и межэтапного человеческого smoke; CODE_REVIEW.md не переписывать. Push без отдельной команды не выполнять.
