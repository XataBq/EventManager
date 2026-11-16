# Карта ответственности и точек изменения

M01 auth/session: [актуальная карта](t10-session-transport.md); продуктовые editor/check-in/files вне M01 пока demo.

T01–T06 UI сверено по `c569152`; добавлена независимая T07 основа. Это реестр архитектурно значимых объектов, не каталог всех классов. Текущий объём и зависимости — [02](../02-architecture.md), сроки жизни — [state-ownership](state-ownership.md), запись данных — [05](../05-data-contracts.md).

## Границы модулей

| Модуль / source set | Отвечает за | Не должен брать на себя |
|---|---|---|
| `app/src/main` | Activity, Navigation 3, feature Route/Screen/ViewModel, UI mapping и orchestration | Concrete fake, transport DTO, самостоятельное присвоение прав, disk IO из composable |
| `app/src/demo` | Hilt wiring fake, demo tools/outbox/Clock, Android storage adapter | Неявный production fallback, обход auth в обычном продуктовом UI |
| `core/domain` | Чистые модели, порты, validation/access/edit policies | Android Context, Resources, Parcelable, Hilt, DTO |
| `core/designsystem` | Токены, Inter, компоненты, визуальные состояния | Repository, роли/сессия, навигация приложения |
| `core/platform` | Parser ссылок, QR encoder/decoder, CameraX preview/analyzer, import/preview managed cover | Решение о выдаче билета или разрешении прохода |
| `data/fake` | Один локальный backend, операции, общий state, codec и fixtures | UI state/guest lookup, Android platform API, обещание production security |

`feature/*` — пакеты внутри `app`, а не Gradle-модули. Новый модуль требует конкретной причины: изоляция зависимости, отдельное владение или измеренная проблема сборки. Количество классов само по себе такой причиной не является.

## UI, навигация и lifecycle

| Объект / исходник | Ответственность и владелец | Куда не переносить эту ответственность |
|---|---|---|
| [MainActivity](../../app/src/main/kotlin/ru/eventpass/app/MainActivity.kt) | Android lifecycle, cold/warm Intent delivery, очистка обработанного intent | Не хранит бессрочный список билетов/аккаунтов |
| [AppSessionViewModel](../../app/src/main/kotlin/ru/eventpass/app/navigation/auth/AppSessionViewModel.kt) | Activity owner: coordinator, transient queue/retry links, public owner, private parent stores по табам, безопасное pending destination | Не редактор/анкета/универсальная ViewModel всех фич |
| [SessionCoordinator](../../app/src/main/kotlin/ru/eventpass/app/navigation/auth/SessionCoordinator.kt) | Наблюдает сессию, выдаёт Loading/Login/Shell, управляет переходом scope и callback очистки | Не владеет файлами и Compose stacks; callback связывает с host |
| [AuthLinkHandler](../../app/src/main/kotlin/ru/eventpass/app/navigation/auth/AuthLinkHandler.kt) | Allowlisted parse → repository confirm; inFlight/handled по доставке, явный retry после failure | Не сериализует URI/token и не логинит после confirm |
| [AppHost](../../app/src/demo/kotlin/ru/eventpass/app/navigation/AppHost.kt) | Выбор public/private host, auth и event routes, lifecycle refresh/expiry | Не проверяет пароль/билет вместо repository |
| [PrivateShell](../../app/src/demo/kotlin/ru/eventpass/app/navigation/EventPassApp.kt) | Три стека/decorators, переходы фич, owner(tab), dirty editor Back, выбор выданного билета | Не сохраняет приватные owners между разными PrivateScope |
| [NavigationState](../../app/src/main/kotlin/ru/eventpass/app/navigation/NavigationState.kt) | Типы ключей, выбор таба, операции со стеками | Очистка списков не равна уничтожению root ViewModels |
| [Auth ViewModels](../../app/src/main/kotlin/ru/eventpass/app/feature/auth/AuthViewModels.kt) | Entry-owned формы login/signup/verification/reset и transient secrets | Пароль/mail token не идут в SavedStateHandle/route; code/grant шаги удалены |
| [EventViewModel](../../app/src/main/kotlin/ru/eventpass/app/feature/event/EventViewModel.kt) | Один EventKey: публичное E, transient форма, submit, receipt, fullscreen/action | Не становится аккаунтным ticket cache и не выполняет регистрацию после login автоматически |
| [GuestReceiptState](../../app/src/main/kotlin/ru/eventpass/app/feature/event/GuestReceiptState.kt) | Компактный receipt в SavedStateHandle конкретного event flow | Не читает ticket из диска/repository по произвольному ID |
| [TicketsViewModel](../../app/src/main/kotlin/ru/eventpass/app/feature/tickets/TicketsViewModel.kt) | Приватный root Tickets: наблюдение своих данных, выбор/history/cancel | UI не фильтрует общий чужой список по введённому email |
| [DashboardViewModel](../../app/src/main/kotlin/ru/eventpass/app/feature/dashboard/DashboardViewModel.kt) | DashboardKey owner: query/pages/refresh/confirmation, owner/request guards, visible/RESUMED lifetime | Не хранит PII в saved state и не знает fake/DTO |
| [ScannerViewModel](../../app/src/main/kotlin/ru/eventpass/app/feature/checkin/ScannerViewModel.kt) | Entry-owned adapter существующего ScannerController; transient input и camera failure | Не второй state machine/репозиторий; no credential saved state |
| [VolunteersViewModel](../../app/src/main/kotlin/ru/eventpass/app/feature/volunteers/VolunteersViewModel.kt) | Owner list/grant/revoke, busy/generation; foreground command lifetime | Не создаёт аккаунты/приглашения и не хранит список чужого owner |
| [QrCameraPreview](../../core/platform/src/main/kotlin/ru/eventpass/core/platform/QrCameraPreview.kt) | CameraX bind/unbind, latest frame, close ImageProxy; QrFrameDecoder | Не знает actor, repository, fake или результат допуска |
| [MyEvents](../../app/src/main/kotlin/ru/eventpass/app/feature/myevents/MyEventsScreen.kt) | MyEventsViewModel/Route/Screen, три выборки одного repository и сохранённая вкладка | Нет копий списка по глобальным ролям |
| [EditorViewModel](../../app/src/main/kotlin/ru/eventpass/app/feature/editor/EditorViewModel.kt) | EditorKey: raw input, dirty/busy, expected revision, save/publish/close/finish, exit/result | Не делает autosave, не решает права только выключением кнопки |
| [CoverPreviewViewModel](../../app/src/main/kotlin/ru/eventpass/app/feature/editor/CoverPreviewViewModel.kt) | Entry-owned асинхронный preview текущего asset | Не глобальный bitmap cache |

Route получает ViewModel и lifecycle-aware UiState, связывает действия с host. Screen получает state/callbacks. Короткие вспомогательные classes могут жить в одном Kotlin-файле; не дробить их механически ради соответствия таблице.

## Domain, data и platform

| Объект / исходник | Ответственность | Особые ограничения |
|---|---|---|
| [ProductRepositories](../../core/domain/src/main/kotlin/ru/eventpass/core/domain/ProductRepositories.kt) | Event/Registration/Ticket ports; check-in/grants — в CheckInModels | Только реализованные мобильные операции; будущие порты отдельно |
| [AuthRepository](../../core/domain/src/main/kotlin/ru/eventpass/core/domain/auth/AuthRepository.kt) | Сессия, команды auth, typed errors/input policy | Это не протокол JWT/OAuth/refresh настоящего сервера |
| [EventModels](../../core/domain/src/main/kotlin/ru/eventpass/core/domain/event/EventModels.kt) | ID, Event, availability, capabilities на eventId | Unknown/ошибка не Unlimited; startsAt не Finished |
| [EventEditing](../../core/domain/src/main/kotlin/ru/eventpass/core/domain/event/EventEditing.kt) | Incomplete draft, EventFields, revision, validation/edit policy | localSchemaRevision и revision — fake-only локальные preconditions; remote null |
| [RegistrationValidator](../../core/domain/src/main/kotlin/ru/eventpass/core/domain/registration/RegistrationValidator.kt) | Поля/consent/типизированные ответы, phone policy | Optional phone, Unicode-имя, без Gmail-like нормализации |
| [FakeEventPassEnvironment](../../data/fake/src/main/kotlin/ru/eventpass/data/fake/product/FakeEventPassEnvironment.kt) | Composition root общего fake: один StateFlow/FakeProductState и Mutex, порты, ScenarioController | Debug методы не owner API; reset не logout |
| [FakeAuthRepository](../../data/fake/src/main/kotlin/ru/eventpass/data/fake/product/FakeAuthRepository.kt) | Initialization/auth commands, proofs/challenges, session generation/acceptance | IO и durable commit отличаются от принятия transient session на caller |
| [FakeTicketOperations](../../data/fake/src/main/kotlin/ru/eventpass/data/fake/product/FakeTicketOperations.kt) | Public event/own tickets, issue/cancel, typed outcomes | Guest не получает старый QR по повторному submit |
| [FakeEventEditingOperations](../../data/fake/src/main/kotlin/ru/eventpass/data/fake/product/FakeEventEditingOperations.kt) | MyEvents/editor reads и guarded mutations | Actor/revision/form lock перепроверяются внутри мутации |
| [FakeAttendanceOperations](../../data/fake/src/main/kotlin/ru/eventpass/data/fake/product/FakeAttendanceOperations.kt) | Owner dashboard/страницы/cancel из общего state, revision-bound cursor, общий snapshot schema 6 | Не второй store/counters и не remote cursor protocol |
| [FakeCheckInOperations](../../data/fake/src/main/kotlin/ru/eventpass/data/fake/product/FakeCheckInOperations.kt) | Owner grants и event-scoped check-in в том же store/Mutex | Не camera/decoder/UI и не многосерверный протокол |
| [ScannerController](../../core/domain/src/main/kotlin/ru/eventpass/core/domain/checkin/ScannerController.kt) | Чистое entry-owned состояние, single-flight, scope/generation, permission/foreground inputs | Android binding принадлежит ScannerRoute/QrCameraPreview, а не controller |
| [CheckInModels](../../core/domain/src/main/kotlin/ru/eventpass/core/domain/checkin/CheckInModels.kt) | Новые порты/outcomes, ManualCode отдельно от QrToken/номера, первый record | Actor определяется внутри adapter, не caller ID |
| [ProductSnapshotCodec](../../data/fake/src/main/kotlin/ru/eventpass/data/fake/product/ProductSnapshotCodec.kt) | Общая server schema 6 и чтение старых версий | Это данные fake-сервера, не UI saved state |
| [AndroidFakeAuthStorage](../../app/src/demo/kotlin/ru/eventpass/app/di/AndroidFakeAuthStorage.kt) | AtomicFile adapter общего snapshot | Имя legacy, файл содержит не только auth |
| [DemoModule](../../app/src/demo/kotlin/ru/eventpass/app/di/DemoModule.kt) | Singleton environment, порты, два Clock, cover adapters | Изменение bindings здесь, не в ViewModel |
| [AndroidEventCoverStore](../../core/platform/src/main/kotlin/ru/eventpass/core/platform/AndroidEventCoverStore.kt) | Content validation/copy/preview/EXIF/GC managed assets | Нет произвольного disk path в domain, bytes вне snapshot |
| [TicketQrEncoder](../../core/platform/src/main/kotlin/ru/eventpass/core/platform/TicketQrEncoder.kt) | QR pixels из payload | Не решает доступ/валидность билета |

## Как выбрать место изменения

| Изменение | Сначала проверить | Затем |
|---|---|---|
| Новое поле E | EventFields/Event/EventEditing, публичное представление | Codec/migration, editor/EventDetails/ticket metadata, contracts tests |
| Новая команда repository | Domain port/outcome и actor policy | Shared state → storage → Flow → ViewModel/result; без fake-знаний в UI |
| Новая entry/переход | Public/private доступ и owner lifetime | Key/host/decorators/Back, saved-state и cleanup test |
| Изменение схемы вопросов | Stable IDs/type/options и исторический questionSnapshot | Совместимые answers остаются; несовместимые объясняются, принятый submit не теряется |
| Новый тип persisted data | Codec/read validation/compatibility | Старые fixtures, auth/ticket/editor writes сохраняют всё состояние |
| Камера/check-in | Независимые contracts/state реализованы; Android часть после T06 | Отдельный platform lifecycle: CameraX/permission/decoder ещё отсутствуют |

Критерий разделения класса: в нём появляются разные владельцы состояния, разные причины изменения или невозможность изолированно проверить поведение. Перед разделением описать перемещаемую ответственность и зависимые вызовы; число строк не является самостоятельным дефектом.

## T09: профиль и файлы

[Текущая карта T09](t09-profile-files.md): ProfileViewModel/Route владеют root-формой, DocumentsViewModel — transient file/resend состоянием entry. FakeProfileDocumentsOperations использует прежние store/Mutex/codec; AndroidDocumentFiles и PDF/XLSX writers — platform. Profile.name остаётся в AuthData.names, Account/session identity не меняется.

## Refresh T09a

EventPassRefresh (core:designsystem) отвечает за nested-scroll/indicator/custom semantic action. Feature Route выбирает callback своего видимого owner, ViewModel — single-flight/поколения/read-model retention. ProfileRepository.refreshProfile и VolunteerRepository.refreshVolunteers реализованы в прежних FakeProfileDocumentsOperations/FakeCheckInOperations; новый store/navigation dispatcher не создаётся. [T09a](t09a-refresh.md).

## M02: public resource и типизированная анкета

Реализованы отдельные EventSlug/PublicEventReference, RemoteEventRepository public GET, scoped session/owner read guards, совместимые typed answers и bounded UTF-8 schema6/legacy reset policy. Фактические владельцы/последовательности/migration bounds/test pointers — [M02 public E](t10-public-event.md); статус и запущенные проверки — [implementation](../validation/t10-m02-implementation.md). Guest/idempotency/manual-code/tickets/PDF мигрированы в M03; оставшиеся editor/scanner/attendance/profile mutations/XLSX/resend cleanup относятся к M04–M09.

## M03: регистрации, билеты и PDF

[Текущие модели, owners, операции, guards, storage и test pointers](t10-registration-tickets.md), [evidence/ограничения](../validation/t10-m03-implementation.md). RemoteRegistrations/RemoteTickets/RemoteTicketDocuments отвечают за protocol/actor/transport; EventViewModel — immutable pending command/receipt; TicketsViewModel — scope/card/visible refresh; DocumentsViewModel + AndroidDocumentFiles — transient SAF и отмена. После ревью 3277e90 исправлены две P2; [проверки исправлений](../validation/t10-m03-p2-fixes.md), повторное независимое ревью ожидается (FIX_REVIEW_PENDING).
