# Текущие порты, remote и fake storage

**17 ноября — M05 IMPLEMENTED/REVIEW_PENDING; M04 принят пользователем на `8da678a`.** Room-черновики, явная синхронизация, pending обложки и разрешение конфликтов подключены только к remote. [Архитектура M05](architecture/t10-room-drafts.md), [объём и ограничения проверки](validation/t10-m05-implementation.md). По последней команде пользователя проверена только компиляция remote; сквозные сценарии, lint, demo и APK не запускались. M02 fix-review открыт; M06–M09 TODO, ручная приёмка после M09.

## Реализованные порты

Точные типы/сигнатуры — [ProductRepositories](../core/domain/src/main/kotlin/ru/eventpass/core/domain/ProductRepositories.kt), [AuthRepository](../core/domain/src/main/kotlin/ru/eventpass/core/domain/auth/AuthRepository.kt), [CoverImporter](../core/domain/src/main/kotlin/ru/eventpass/core/domain/event/CoverImporter.kt). Ниже карта, не вторая копия Kotlin-интерфейсов.

| Порт | Чтение | Команды и исходы |
|---|---|---|
| AuthRepository | observeSession/currentSession | refreshSession, signUp/signIn/signOut, requestVerification/requestPasswordReset/completeEmailLink, consentDocuments; AuthResult<Auth Session/EmailDispatch/Unit>, AuthError; SessionState.Recovery |
| EventRepository | observePublicEvent(PublicEventReference), observeMyEvents, observeEditor | refreshPublicEvent/refreshMyEvents, saveDraft/publishDraft/updateEvent/finishEvent; typed read/edit outcomes |
| RegistrationRepository | Через event/tickets reads | submitGuest / submitAuthenticated, разные допустимые результаты |
| TicketRepository | observeMyTickets / observeOwnTicket | refreshMyTickets / cancelOwnTicket; own access и typed error |
| AttendanceRepository | owner-only observeDashboard / attendees(revision cursor) | refreshDashboard / cancelRegistration(eventId,registrationId); totals из tickets, без credentials |
| VolunteerRepository | observeVolunteers(eventId), refreshVolunteers(eventId), owner only | grant(emailOrId) / revoke(userId); Updated/typed failure |
| CheckInRepository | observeAccess / refreshAccess | checkIn(eventId, typed credential); Admitted/AlreadyUsed/Invalid/Failed |
| CoverImporter | Не читает repository | importCover(source) → EventCover descriptor либо typed failure |
| EventCoverPreview (platform interface) | Асинхронный preview managed asset | Android decode/EXIF вне domain |

Actor для private операции разрешается по текущей подтверждённой сессии **внутри реализации**. ID в аргументе не даёт доступа. Общие UI/ViewModels получают только порты через DI. Cover source identifier — временный вход platform adapter, а не произвольный path в persisted Event.

## Один общий backend и publish order

FakeEventPassEnvironment содержит один MutableStateFlow<FakeProductState> и один coroutine Mutex. FakeAuthRepository, FakeTicketOperations, FakeEventEditingOperations, FakeCheckInOperations и FakeAttendanceOperations используют этот же state/lock/storage. ScenarioController — только demo/test, не owner/auth API.

```mermaid
flowchart LR
    ports["Repository ports через demo DI"] --> ops["Auth, ticket, editor, grants, check-in и attendance operations"]
    ops --> shared["Initialize и общий Mutex на IO"]
    shared --> proposed["Следующий полный FakeProductState"]
    proposed --> codec["ProductSnapshotCodec schema 6"]
    codec --> atomic["AtomicFile adapter"]
    atomic --> published["StateFlow после успешной записи"]
    published --> observers["Общие event, tickets, editor и attendance observers"]
```

Изображён durable mutation path; refresh/read errors и transient session имеют отдельные пути. Ошибка записи преобразуется в typed storage failure без ложного durable success. Отмена после уже выполненного commit не означает rollback; не обещать, что запись отсутствует, если UI не получил успех. Особая acceptance signIn описана в [operations](architecture/operations.md): session не durable и публикуется на активном caller после отменяемого возврата/ожидания lock.

## Результаты и политики

- Guest Issued(snapshot/email/accountCreated) создаёт passwordless account без session; EMAIL_TAKEN очищает анкету и ведёт во вход. Успешный remote результат содержит guestAccess только для текущего receipt; повторная заявка не является lookup произвольного QR. Unknown retry сохраняет body/key в памяти, process restore без тела не отправляет новый POST.
- Auth submit берёт actor/contact из проверенной session/server profile; name/phone в server body не отправляются. ALREADY_REGISTERED разрешается через собственные upcoming/past tickets, ID не угадывается. Нет переноса анкеты или autosubmit после login.
- Active cancel освобождает место один раз, Used отклоняется; новая заявка после cancel получает новый токен при доступной регистрации. Количество мест выводится из неотменённых tickets; серверную гонку Android не реализует.
- PublicEventResult/OwnTicketResult/MyTicketsResult различают отсутствие/доступ/typed read failure. Error не равен пустому списку или Unlimited. Refresh/observe используют общие данные, не отдельные числа на экран.
- SaveDraft допускает неполные fields; publish валидирует. Fake owner/session/local revisions проверяются под Mutex; remote PATCH preconditions API не даёт. Вопросы после регистрации редактируются, новые registrations сохраняют questionSnapshot; прежние answers/labels не переписываются.
- Event metadata update меняет server ticket snapshot title/date/venue/zone, не credential/status. Guest receipt остаётся снимком выдачи. LIVE update допускает даты/metadata по текущей policy; terminal update запрещён. Это текущий demo default, не серверный договор.
- Expected errors представлены отдельными семействами AuthError, ProductReadError, RegistrationError, TicketError, EventEditError и CoverImportResult; общего реализованного AppError-framework нет. CancellationException не превращать в Network; UI mapping — resources.

## Profile и документы T09

ProfileRepository.observeProfile/refreshProfile перечитывает профиль; saveProfile меняет name/optional Russian phone/organization/notifications в прежнем store. Identity/email read-only; имя в AuthData.names, детали — profileDetails. Новые RegistrationContact получают name/phone, старые registrations/tickets/checkIns не переписываются. DocumentsRepository.prepare/validate/resendTicket отдаёт typed outcomes и immutable DocumentSnapshot, File port не зависит от Android Uri/Context. PDF только свой current Active, XLSX owner E/all registrations+answers независимо от query/pages, без credentials. VM/Compose не знают fake/DTO. [Исходники и операции](architecture/t09-profile-files.md).

## Persistence и границы хранения

| Что | Где/срок жизни |
|---|---|
| Accounts/proofs/names/auth mail links и полный product state | Один AtomicFile `noBackupFilesDir/demo-auth-v1.bin`; legacy имя сохранено для совместимости |
| Events/registrations/tickets/status/scan grants/phone policy/nextId | ProductSnapshotCodec schema 6; данные fake-сервера |
| Manual codes / first check-in records | Legacy v4 → schema 6, в том же server snapshot; UI scanner result не persist |
| Drafts, publication receipts, revisions, cover descriptors | Та же schema 6; сохранённый draft переживает новый процесс |
| Cover bytes | Managed app-private files через AndroidEventCoverStore, не в snapshot/route |
| Session/auth mail/ticket outbox/fail-next/read errors/Clock offsets | Память; новый процесс не восстанавливает их как действующую сессию/очередь писем |
| Незавершённая анкета, raw auth secrets | Не сохраняются в постоянное хранилище/SavedStateHandle |
| Guest receipt | Только SavedStateHandle конкретной EventKey; исключение для временного UI восстановления, не server lookup |

Фактический список полей encode/decode — [ProductSnapshotCodec](../data/fake/src/main/kotlin/ru/eventpass/data/fake/product/ProductSnapshotCodec.kt); не считать все поля FakeProductState persisted. Session/outbox/управляемые ошибки намеренно не сериализуются. В файле server ticket есть payload, но guest UI не получает к нему произвольный доступ: это разные уровни хранения.

Writer — ProductSnapshotCodec v6: bounded UTF-8, optional local revisions, explicit section/slug IDs, all Question/Answer formats/metadata, price/remote cover descriptors и historical questionSnapshot. Полный snapshot ≤32 MiB; строка ≤200000 bytes, questions/options/sections ≤50. Android reader ограничен тем же общим размером до неограниченного чтения файла. Все writers используют один codec/Mutex/IO; session/outbox/cooldown/faults не persist.

| Вход | Миграция / инвариант |
|---|---|
| v1 / auth EPA2 | Auth proofs/names/accounts сохраняются, legacy reset grants отозваны; product seed |
| v2–v3 | Event/ticket/answers/QR сохраняются; стабильный manual code выводится из прежнего opaque payload, неизвестный first checker/time не выдумывается |
| v4–v5 | Mapping manual codes/first check-ins/profile сохраняются; historical question labels без snapshot не выдумываются |
| v6 | UTF-8/metadata/all formats/local references/nullable local versions/history round-trip; downgrade отсутствует |
| Известный несовместимый legacy | Только после полного корректного decode: cancelled/null-or-zero published capacity/single option/новые field bounds. Явный reset сначала архивирует original, затем пишет seed6; backup failure запрещает reset |
| Corrupt/truncated/oversized/unsupported/trailing | Unknown/StorageUnavailable; не классифицировать как разрешённый legacy reset, не перезаписывать автоматически |

Политика, точные файлы и регрессии — [M02](architecture/t10-public-event.md). Старый QR/Used proof не меняется из-за расширения формата; исходные APK и исходники v1–v5 fixtures остаются evidence, а не текущим writer.

AndroidFakeAuthStorage — синхронный adapter AtomicFile; вызывающие backend operations делают IO вне main. JVM использует изолированный/volatile storage. DataStore/Room для текущего snapshot не применяются. Auth proofs salted, secrets/hash/deadlines — demo contract, не production storage design.

## Clock, assets и outbox

DemoModule инъектирует auth DemoClock (system UTC + temporary offset) и event DemoEventClock (fixed fixture instant + отдельный offset). Это предотвращает зависимость демонстрационного E от даты запуска; offset не persist. Policy обязательно использует соответствующий Clock, а не случайный now устройства внутри UI/репозитория. Auth expiry refresh работает в STARTED; event/ticket reads обновляются соответствующим refresh.

Demo OpenDocument import немедленно копирует проверенные PNG/JPEG до 5 MiB в managed file. Domain хранит opaque EventCover. Preview sampled/EXIF на worker dispatcher. GC перед первым import нового процесса удаляет только unreferenced assets persisted records; при unhealthy snapshot пропускается. Это не atomic transaction вместе с snapshot, и несохранённая editor форма после process death не гарантируется.

Demo письмо — запись прежнего outbox, не настоящая рассылка. DocumentsRepository.resendTicket защищён own/current Active guard, auth generation и централизованным cooldown по auth Clock; retryAt возвращается UI, recipients не вводятся. PDF/XLSX генерируются platform adapter за DocumentFiles после авторизованного snapshot и проверок до передачи/после close; это реальные demo-документы, а серверная генерация/доставка — T10. Guest download/recovery не добавлены. [Точный путь](architecture/t09-profile-files.md).

## S01–S14 — целевой реестр сценариев

Реестр включает реализованные check-in/camera/dashboard сценарии и будущие файлы/профиль. Реализованные доказательства и границы — [invariants](architecture/invariants.md), результаты сборки — отчёты этапов; наличие строки ниже не означает готовность всех S-сценариев.


| ID | Fixture / действие | Ожидаемый результат |
|---|---|---|
| S01 | u-alex: owner A, volunteer B, participant C, participant+volunteer D | Все связи видны одновременно; права не переносятся между событиями |
| S02 | Anonymous + submit → G02 → соседний экран/Back → закрытие flow → signUp/confirm/signIn | QR доступен в сохранённом flow и после пересоздания Activity, но не после закрытия/нового запуска; User не создан submit-ом; после входа билет доступен по email |
| S03 | Anonymous + существующая почта | RequiresLogin; ответы теряются, после входа форму заполняют заново; автоотправки нет |
| S04 | Published capacity>0, remaining=0/full, LIVE/FINISHED | Server status/counts определяют доступность; LIVE допускает submit, full/finished — нет. Null только draft, ошибки не unlimited |
| S05 | Active / Used / Cancelled / чужой event / неизвестный QR | Пять детерминированных исходов проверки |
| S06 | Отзыв grant при открытой камере | Следующая операция Forbidden, выход из сканера; свой билет остаётся |
| S07 | Двойной тап во время отправки | Один клиентский запрос, кнопка в loading; протокол server retry не выдумывать |
| S08 | Ошибка сети / timeout при запросе | Честная ошибка связи; без ложного успеха и автоматического повторного submit |
| S09 | Пустые tickets/owned/volunteer/drafts, прошедшие | Правильные подсказки; без кнопки каталога |
| S10 | Подтверждение аккаунта / код сброса / expired session | После подтверждения ручной login; reset по коду без отдельного verify email; недоступная сессия ведёт во вход |
| S11 | Нет разрешения камеры / постоянный отказ | Ручной ввод остаётся доступен |
| S12 | Email retry failure, export failure, grant user missing | Честная ошибка, билет не исчезает, повтор возможен |
| S13 | Длинные данные, font scaling, большие списки | Нет обрезания действий; поиск работает |
| S14 | Rename/type/option change до/после регистрации и во время submit | Совместимые ответы остаются, несовместимые объясняются; вопросы редактируемы, исторические snapshots сохраняются; принятый submit не теряет ticket из-за local schema change |

Debug ScenarioController умеет выбрать seed, сменить тестовую личность, изменить сетевой сценарий и воспроизвести внешнее событие (отзыв, проход другим пользователем). Не включать его в production release. Смена debug-личности не равна глобальному переключению роли.


## Граница оставшейся миграции M05–M09

Backend snapshot 1.1.1 и решения 13 ноября приняты; [mapping](api/migration-mapping.md) и [34](34-api-migration-plan.md) задают остаток. Remote M01–M04 auth/profile/public E/registration/tickets/PDF/online editor/MyEvents/covers реализованы. Offline drafts/Room подключены в M05; scan/grants/attendance/profile mutations/XLSX/delete/resend cleanup — следующие M. Demo guest уже создаёт passwordless account без session и использует EVT display/manual code, legacy proofs сохранены; ticket resend остаётся demo-only до M08. Старые 22/OpenAPI v0.1.0 не определяют текущий HTTP.

Реализованный независимый check-in: Active → Used, повтор AlreadyUsed, WrongEvent/Cancelled/Unknown → Invalid; связь/Timeout/Forbidden не Invalid/успех. Только после ответа repository можно разрешать проход. Offline admission не предусмотрен. Remote 24h idempotency/EVT-code приняты в 34, адаптация соответствующих команд — M03/M06; текущий demo manual credential отдельно от displayNumber/qrPayload. Сохранение/ошибки/владение state и ограничения — [независимая основа T07](architecture/t07-checkin-foundation.md). UI и CameraX/ZXing подключены к прежним портам; успешный JVM contract не означает допуск без ответа repository или production API.

Продолжение: отдельное ревью M05 → gate M06; историческое M02 fix-review остаётся открытым. Финальная человеческая проверка только после M09; локальные tests/optional live smoke не закрывают её.

История прежних контрактов — [архив](history/2026-10-30-architecture/README.md). При новом порте/snapshot изменить эту страницу и [операции/инварианты](architecture/operations.md) в том же изменении.

## Адресные read-команды T09a

TicketRepository.refreshMyTickets и EventRepository.refreshMyEvents/refreshPublicEvent сохраняют прежний observer-путь. AttendanceRepository.refreshDashboard согласуется с attendees текущих E/query/revision. Добавлены VolunteerRepository.refreshVolunteers(eventId): VolunteersResult (authenticated owner E) и ProfileRepository.refreshProfile(): ProfileResult (текущий authenticated actor). Это чтение общего state под прежним Mutex/IO с проверкой session generation/cancellation; transient Network/Timeout/Storage не вызывает запись snapshot, save/grant/revoke или смену identity. Observer priority и UI owner generations защищают поздние replies. Порты не определяют новый HTTP API, schema 6 прежняя. [Адресное обновление T09a](architecture/t09a-refresh.md).

## Public GET M02

EventRepository.observe/refreshPublicEvent принимает Slug или явный Internal reference; remote Internal возвращает FeatureUnavailable без сети. Data-local stamped viewer/token context, single-flight, consumer-edge sanitize и cache release защищают logout/late/queued reply. Различаются notfound/network/timeout/forbidden/server/protocol. Auth bodies ≤1 MiB, public form bodies ≤32 MiB (включая escaped Unicode). DTO не выходят в VM/Compose. [M02 public E/schema6](architecture/t10-public-event.md).

## M03: server ticket/document projection

TicketRepository имеет scope-aware observe/refresh overloads (upcoming/past), сохраняет server counters. Legacy no-arg методы для demo consumers остаются совместимыми. Ticket.summary — TicketEventSummary, не полный Event; registrationId remote=null. OwnTicketResult.Found.event nullable для server summary. Remote ticket metadata/guestAccess не persist в fake snapshot; ProductSnapshotCodec требует настоящий registrationId для fake записи.

DocumentSnapshot.Remote/DocumentSource — transient PDF download; DocumentStamp.accountId=null только для живого guest capability request. AndroidDocumentFiles сохраняет источник через private temporary и SAF с generation/cancellation guards. Request/источник/bytes не сериализуются в VM; SavedState guest исключение содержит только компактный QR/capability текущего flow. [Подробные инварианты и ограничения](architecture/t10-registration-tickets.md).

## M04–M05: owner editor, Room и обложки

RemoteEventRepository оставляет public reads и делегирует draft-операции LocalDraftRepository; RemoteOrganizerRepository обслуживает server MyEvents и online published edit/finish. data:local — Android Room library без HTTP/DTO; domain DraftStore задаёт узкую границу. App/remote DI открывает private noBackup DB и связывает auth purge, managed files и coordinator. В demo Room не подключён.

EventDraft.local ID, remoteEventId и slug различаются. DraftStore хранит account partition, incarnation/storage CAS, confirmed/base/pending, localRevision, неизменную create-команду и pending cover reference. LocalDraftState отличается от серверного успеха; localDrafts не входят в server items/counters. EditorKey.remoteDraftId адресует серверный draft, safe saved metadata сохраняет разрешённый local ID; fields/command восстанавливаются из Room после валидного входа того же account.

Save пишет только на устройство; sync — отдельная foreground команда. Room-транзакции короткие, HTTP/file IO вне них. ACK A сохраняет pending B; logout/deletion fence запрещает позднему ответу создать запись заново. После неизвестного PATCH — GET, затем ack или явный конфликт base/local/remote. Merge сохраняет stable IDs; выбор пользователя сначала локален. Publish/finish online. Pending cover копируется в private durable file, upload descriptor фиксируется до attach; release только после перехода DB/проверки ссылок. GET→PATCH не атомарен без server precondition.

[Таблица состояний, доступ, retention и точки commit](architecture/t10-room-drafts.md). [Проверки и NOT_RUN](validation/t10-m05-implementation.md). M03 registration replay/server list scope не переписаны; их повторные регрессии в M05 не запускались по команде пользователя.
