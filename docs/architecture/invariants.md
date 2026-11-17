# Инварианты и указатели на доказательства

M01 auth/session: [актуальная карта](t10-session-transport.md); продуктовые editor/check-in/files вне M01 пока demo.

Сфера таблиц ниже — **реализованный demo до миграции**, не требования к remote. Guest без account, verified-only, reset-code, Unlimited/started, questions lock и локальные revision заменены целевыми решениями [34](../34-api-migration-plan.md); на M01–M08 соответствующие тесты/таблицы изменяются вместе с кодом. Ревью T10 должно проверять новые правила, а не требовать сохранения заменённых demo-инвариантов.

UI T01–T06: `c569152`; добавлены независимые T07 contract/state доказательства. Таблица связывает правило с кодом и существующими tests; наличие test-файла **не означает**, что он заново исполнен при редактировании документации. Команды/числа/build/device и ограничения запусков — только в актуальном [отчёте T07](../21-t07-validation.md), история — в отчётах [T04](../18-t04-validation.md)/[T05](../19-t05-validation.md).

| Правило | Реализация | Где проверяется |
|---|---|---|
| Нет глобальной роли, owner/grant/ticket независимы | EventAccessPolicy, reads общего fake | [RegistrationRulesTest](../../core/domain/src/test/kotlin/ru/eventpass/core/domain/RegistrationRulesTest.kt), [EventEditingTest](../../data/fake/src/test/kotlin/ru/eventpass/data/fake/product/EventEditingTest.kt): `relationshipsOverlapAndRevocationLeavesOwnTicket` |
| Guest submit создаёт passwordless Account без session; setup → ручной login → собственные tickets | FakeTicketOperations, FakeAuthRepository | [FakeEventPassEnvironmentTest](../../data/fake/src/test/kotlin/ru/eventpass/data/fake/product/FakeEventPassEnvironmentTest.kt), [TicketPersistenceTest](../../data/fake/src/test/kotlin/ru/eventpass/data/fake/product/TicketPersistenceTest.kt) |
| Signup создаёт session без verify gate; verify сохраняет session, reset mail link подтверждает email и отзывает session | AuthRepository и auth flow VM | [FakeAuthRepositoryTest](../../data/fake/src/test/kotlin/ru/eventpass/data/fake/product/FakeAuthRepositoryTest.kt), [AuthViewModelsTest](../../app/src/testDemo/kotlin/ru/eventpass/app/feature/auth/AuthViewModelsTest.kt), [AuthUiIntegrationTest](../../app/src/androidTestDemo/kotlin/ru/eventpass/app/AuthUiIntegrationTest.kt) |
| Отмена signIn/занятый Mutex не открывают старую session и не блокируют caller | FakeAuthRepository acceptance, coordinator | [AuthFoundationRegressionTest](../../app/src/testDemo/kotlin/ru/eventpass/app/navigation/AuthFoundationRegressionTest.kt), [SessionCoordinatorTest](../../app/src/testDemo/kotlin/ru/eventpass/app/navigation/SessionCoordinatorTest.kt) |
| Приватные root/hidden owners уничтожаются при смене session | AppSessionViewModel, PrivateScope/decorators | [AuthUiIntegrationTest](../../app/src/androidTestDemo/kotlin/ru/eventpass/app/AuthUiIntegrationTest.kt), [EntryOwnershipTest](../../app/src/androidTestDemo/kotlin/ru/eventpass/app/EntryOwnershipTest.kt) |
| Back карточки → тот же ticket list/сегмент; QR/confirmation закрываются прежде карточки; внутренний E → исходный таб/owner, внешний E → Tickets | TicketsRoute BackHandler, NavigationState.openEvent/openLinkedEvent | [ProductBackNavigationTest](../../app/src/androidTestDemo/kotlin/ru/eventpass/app/ProductBackNavigationTest.kt), [NavigationStateTest](../../app/src/test/kotlin/ru/eventpass/app/navigation/NavigationStateTest.kt); [результат исправления](../23-navigation-back-validation.md) |
| Auth secrets не сериализуются, URI retry transient | Auth ViewModels, AuthLinkHandler, Activity VM | [AuthDemoLinkParserTest](../../core/platform/src/test/kotlin/ru/eventpass/core/platform/AuthDemoLinkParserTest.kt), AuthFoundationRegressionTest, [AuthColdActivityTest](../../app/src/androidTestDemo/kotlin/ru/eventpass/app/AuthColdActivityTest.kt); дополнительно читать storage/route wiring |
| Анкета не переносится через login; нет autosubmit | EventViewModel.leaveForm, host | [EventViewModelTest](../../app/src/testDemo/kotlin/ru/eventpass/app/feature/event/EventViewModelTest.kt): `existingAccountClearsAnswersAndDoesNotAutomaticallySubmitAfterLogin` |
| Старый result/finally A не разблокирует новую отправку B | Локальное поколение EventViewModel | EventViewModelTest: `cancelledGuestFinallyCannotUnlockAnotherSubmission`, `cancelledAuthenticatedFinallyCannotUnlockAnotherSubmission` |
| Receipt сохраняется до показа, удалённый flow не получает старый QR | GuestReceiptState, EventKey owner | EventViewModelTest: `receiptIsSavedBeforePublicationAndClosingItDoesNotRestoreFromRepository`, [RegistrationTicketsTest](../../app/src/androidTestDemo/kotlin/ru/eventpass/app/RegistrationTicketsTest.kt), [process script](../../scripts/verify-t05-process-state.ps1) |
| Optional phone/Unicode name/consent/answers | RegistrationValidator, RegistrationPolicy | RegistrationRulesTest, FakeEventPassEnvironmentTest |
| M02: опубликованная capacity положительная, LIVE допускает запись, окончание по времени/вручную | Event.registrationAvailability, Clock; локальные проверки M02 в implementation report; независимое ревью ожидается | RegistrationRulesTest, EventEditingTest: `liveRegistrationAndManualFinishUseSharedClockAndCannotReopen` |
| Active cancel освобождает один раз, Used не отменяется | FakeTicketOperations, TicketStatus | TicketPersistenceTest, RegistrationTicketsTest |
| M02: вопросы после регистрации редактируемые; прежние ответы сохраняются после edit/cancel/restart | Registrations history, questionSnapshot; локальные проверки M02 в implementation report; независимое ревью ожидается | EventEditingTest: `guestAnswersSurviveQuestionEditsCancellationAndRestart` |
| Неполный draft сохраняется, publish валидирует, результат стабилен | EventDraft/EventFields, editing operations | [EventEditingPolicyTest](../../core/domain/src/test/kotlin/ru/eventpass/core/domain/event/EventEditingPolicyTest.kt), EventEditingTest, [EditorViewModelTest](../../app/src/testDemo/kotlin/ru/eventpass/app/feature/editor/EditorViewModelTest.kt) |
| Локальные fake revisions; remote PATCH не обещает lost-update protection | Editor expected versions, dirty input | EventEditingTest: `revisionsPreventLostUpdatesAndSchemaVersionChangesOnlyForQuestions`; EditorViewModelTest: `dirtyEditorDoesNotAdoptNewRevisionAndOverwriteExternalChange` |
| Поздний save/finally не меняет новую editor entry | Editor generation/identity | EditorViewModelTest: `cancelledOldSaveAndFinallyCannotPublishOrUnlockNewEntry` |
| Storage failure не публикует ложный успех, все writers сохраняют общий state | IO + Mutex + codec + AtomicFile | EventEditingTest: `failedWritesAndTransientFailuresPublishNoFalseDraftOrEvent`, `authAndTicketWritesRetainSavedDraftAndPublishedMetadata`; TicketPersistenceTest |
| Schema v1/v2 читается, corruption не сбрасывает demo молча | ProductSnapshotCodec, initialization health | EventEditingTest: `v2MigrationPreservesTicketsAnswersProofsAndPendingChallengeThenWritesV3`, `v1MigrationKeepsAuthAndCorruptSnapshotNeverOverwritesData`, `schemaV3TruncationAndTrailingDataAreRejectedWithoutReset` |
| Managed cover не зависит от временного source URI, размер ограничен | AndroidEventCoverStore, CoverImporter/Preview | [EventEditorIntegrationTest](../../app/src/androidTestDemo/kotlin/ru/eventpass/app/EventEditorIntegrationTest.kt); настоящий picker/share target остаются ручной проверкой |
| QR кодируется из непрозрачного payload | TicketQrEncoder | [TicketQrEncoderTest](../../core/platform/src/test/kotlin/ru/eventpass/core/platform/TicketQrEncoderTest.kt), [VerifyTicketQr](../../scripts/VerifyTicketQr.java) |
| Fake не входит в main implementation/release | Build variant, demo bindings | [app build](../../app/build.gradle.kts): `verifyDemoIsolation`; это не проверенная production API конфигурация |

## Независимые инварианты T07

| Правило | Реализация | Проверка |
|---|---|---|
| Owner/active grant и current authenticated session на каждой операции | FakeCheckInOperations, EventAccessPolicy | [CheckInTest](../../data/fake/src/test/kotlin/ru/eventpass/data/fake/product/CheckInTest.kt): queued revoke/session, expiry, owner/participant/grant |
| Active → Used и один first record, без изменения capacity | Общий state/Mutex/schema 4 | CheckInTest: owner/manual/repeat, concurrent calls, Used cancel, revoke/history, all writers |
| Неверный билет / transport / access различимы, storage не false success | Typed CheckInOutcome/VolunteerOutcome | CheckInTest: invalid/time, storage/network/timeout, missing target |
| Stale frame/result/finally не меняет новую отправку, нет PII после revoke | ScannerController scope/generation | [ScannerControllerTest](../../core/domain/src/test/kotlin/ru/eventpass/core/domain/checkin/ScannerControllerTest.kt) |
| V3/legacy Used/manual code не меняют QR, старый guest receipt без lookup | V4 reader и default ManualCodePolicy | CheckInTest migration; [GuestManualCompatibilityTest](../../app/src/test/kotlin/ru/eventpass/app/feature/event/GuestManualCompatibilityTest.kt) |

## Уровни доказательств

1. **Код/контракт:** описано и найдено в исходниках. Само по себе не подтверждает runtime.
2. **JVM test:** управляемое поведение state/операции; не доказывает реальную камеру, OS process death или системный picker.
3. **Instrumentation:** записать реальное device/API/build и сценарий; recreate/new backend не приравнивать к OS-kill.
4. **Ручная приёмка:** только прямое подтверждение человека с доступными сведениями о сборке/устройстве. Не закрывать другие gates по аналогии.

H1/auth smoke/H2/Back/T06 приняты пользователем. T07 camera/UI и H3/H4 ожидаются; приёмки — [07](../07-validation.md), статусы — [06](../06-delivery-plan.md).

## UI/lifecycle T07 и остаток T08

- [ScannerUiWiringTest](../../app/src/testDemo/kotlin/ru/eventpass/app/feature/checkin/ScannerUiWiringTest.kt): manual/очистка ввода/Back/revoke/camera failure за прежним controller.
- [VolunteersViewModelTest](../../app/src/testDemo/kotlin/ru/eventpass/app/feature/volunteers/VolunteersViewModelTest.kt): grant/revoke UI state, denied без PII, hidden/late finally.
- [CheckInUiIntegrationTest](../../app/src/androidTestDemo/kotlin/ru/eventpass/app/CheckInUiIntegrationTest.kt): routes/manual/results/permission, hidden/background/Back/revoke и CameraX unbind; настоящий decoder на контролируемом frame. Точные реально выполненные прогоны — [отчёт](../24-t07-ui-validation.md).
- [QrFrameDecoderTest](../../core/platform/src/test/kotlin/ru/eventpass/core/platform/QrFrameDecoderTest.kt): stride/crop/rotation/multiple/blank/truncated plane.
- Физический QR/фокус/слабый свет и полная H3 после T08 остаются человеку; dashboard/attendance подключены независимо; физическая camera/UI и общая H3 ожидаются.

Revoke failure Network/Timeout/Storage сохраняет confirmation и показывает error/retry внутри Dialog: VolunteersViewModelTest (3 случая) и CheckInUiIntegrationTest.revokeErrorsAreVisibleInDialogAndRetryRevokesTheSameVolunteer; [результат исправления](../25-t07-fixes-validation.md). Optional back-camera/autofocus requirements проверяются aapt упакованного code 15.

## T08: attendance/dashboard

- [AttendanceTest](../../data/fake/src/test/kotlin/ru/eventpass/data/fake/product/AttendanceTest.kt): owner/guest/participant/volunteer/foreign owner, forged resource/page, totals после submit/checkIn/cancel, legacy Used и реальные volunteer counters, search/revision/pages, cancel race/storage/restart/questions lock, queued session/cancel, expiry/startsAt/end.
- [DashboardViewModelTest](../../app/src/test/kotlin/ru/eventpass/app/feature/dashboard/DashboardViewModelTest.kt): query/cancel late result/finally, hidden/new owner, denied/PII, refresh/freshness/revision reset.
- [DashboardIntegrationTest](../../app/src/androidTestDemo/kotlin/ru/eventpass/app/DashboardIntegrationTest.kt): existing Scanner/Volunteers Back, owner cancellation/error внутри Dialog, query/recreation/refresh/hidden/logout и forged volunteer route. Реально исполненные проверки — [26](../26-t08-validation.md); H3 не выводится из наличия тестов.

Поздний refresh A после observer B не откатывает totals/rows/error; повторное чтение страницы не теряется за busy. DashboardViewModelTest: lateRefreshCannotRollBackObserverTotalsOrEmptySearchPage, lateRefreshFailureCannotReplaceNewerObserverError, pageRefreshRequestedWhileBusyRunsOnceAfterOldRefreshFinishes, oldRefreshFinallyCannotUnlockNewOwnerOrReplayItsQueuedRead, stopCancelsQueuedFollowUpAfterImmediateRefreshReply. [RED/GREEN](../27-t08-refresh-validation.md).

## T09: профиль, файлы и outbox

[ProfileDocumentsTest](../../data/fake/src/test/kotlin/ru/eventpass/data/fake/product/ProfileDocumentsTest.kt): one name source/session identity, new contacts без изменения истории, v1–v4→5 и все writers, corruption/storage/cancellation, own/current PDF, owner full snapshot/all statuses, resend/recipient/cooldown. [ProfileViewModelTest](../../app/src/test/kotlin/ru/eventpass/app/feature/profile/ProfileViewModelTest.kt) и [DocumentsViewModelTest](../../app/src/testDemo/kotlin/ru/eventpass/app/feature/documents/DocumentsViewModelTest.kt): dirty observer, late result/finally, single flight и old picker/session cleanup. [DocumentStreamsTest](../../core/platform/src/test/kotlin/ru/eventpass/core/platform/DocumentStreamsTest.kt): close/cancel/access до bytes. Реально исполненные device/reader/restart проверки, H3/H4/Q10 остаток — [28](../28-t09-validation.md).

Configuration rebind сохраняет pending и уже начатый writer; настоящий exit/hidden/смена ресурса игнорируют прежний URI. DocumentsViewModelTest: configurationRebindPreservesLaunchedPickerAndInFlightWriterForSameResource, realExitHiddenAndChangedResourceStillDiscardOldPicker; [SafRecreationTest](../../app/src/androidTestDemo/kotlin/ru/eventpass/app/SafRecreationTest.kt) и [RED/GREEN](../29-t09-saf-recreation-validation.md).

## T09a: адресация и сохранность чтения

[RefreshOwnersTest](../../app/src/testDemo/kotlin/ru/eventpass/app/feature/RefreshOwnersTest.kt) проверяет port/resource/owner/single-flight/late finally, формы/сегмент/выбор/profile baseline. [RefreshReadContractsTest](../../data/fake/src/test/kotlin/ru/eventpass/data/fake/product/RefreshReadContractsTest.kt) — actor/E guards, typed errors и отсутствие disk write/read-мутаций. Dashboard observer/page/follow-up регрессии сохранены. [RefreshRoutesTest](../../app/src/androidTestDemo/kotlin/ru/eventpass/app/RefreshRoutesTest.kt) и [PullRefreshComponentTest](../../app/src/androidTestDemo/kotlin/ru/eventpass/app/PullRefreshComponentTest.kt) — настоящий swipeDown и initiating spy calls, hidden parent/tab, empty/error/middle, overlay/guest/Scanner/dirty input. Точные выполненные проверки/пределы — [30](../30-t09a-refresh-validation.md); автоматизация не закрывает человеческую приёмку нового UX.

## M02: public resource и типизированная анкета

Реализованы отдельные EventSlug/PublicEventReference, RemoteEventRepository public GET, scoped session/owner read guards, совместимые typed answers и bounded UTF-8 schema6/legacy reset policy. Фактические владельцы/последовательности/migration bounds/test pointers — [M02 public E](t10-public-event.md); статус и запущенные проверки — [implementation](../validation/t10-m02-implementation.md). Guest/idempotency/manual-code/tickets/PDF мигрированы в M03; online editor/MyEvents/covers подключены в M04; Room/offline drafts подключены в M05; scanner/volunteers подключены в M06; attendance подключён в M07; profile mutations/XLSX/resend cleanup относятся к M08–M09.

## M03: регистрации, билеты и PDF

[M03RegistrationStateTest](../../app/src/testDemo/kotlin/ru/eventpass/app/feature/event/M03RegistrationStateTest.kt) проверяет повтор guest/auth с тем же body/key после добавления обязательного или удаления отвеченного вопроса; новая validation не блокирует replay уже отправленной команды. [M03TicketsStateTest](../../app/src/testDemo/kotlin/ru/eventpass/app/feature/event/M03TicketsStateTest.kt) проверяет server items/scope/counters при обоих порядках list/own-card, Back без примеси out-of-scope билета, восстановление selection до немедленного list и независимые status/now карточки при refresh. Forbidden по-прежнему удаляет приватные карточку и QR.

[Текущие модели, owners, операции, guards, storage и test pointers](t10-registration-tickets.md), [evidence/ограничения](../validation/t10-m03-implementation.md). RemoteRegistrations/RemoteTickets/RemoteTicketDocuments отвечают за protocol/actor/transport; EventViewModel — immutable pending command/receipt; TicketsViewModel — scope/card/visible refresh; DocumentsViewModel + AndroidDocumentFiles — transient SAF и отмена. После ревью 3277e90 исправлены две P2; [проверки исправлений](../validation/t10-m03-p2-fixes.md), повторное ревью cec5520 принято пользователем 17 ноября: [свидетельство](../validation/t10-m03-user-review-confirmation.md).

## M04–M05: owner editor, Room и обложки

RemoteEventRepository оставляет public reads и делегирует draft-операции LocalDraftRepository; RemoteOrganizerRepository обслуживает server MyEvents и online published edit/finish. data:local — Android Room library без HTTP/DTO; domain DraftStore задаёт узкую границу. App/remote DI открывает private noBackup DB и связывает auth purge, managed files и coordinator. В demo Room не подключён.

EventDraft.local ID, remoteEventId и slug различаются. DraftStore хранит account partition, incarnation/storage CAS, confirmed/base/pending, localRevision, неизменную create-команду и pending cover reference. LocalDraftState отличается от серверного успеха; localDrafts не входят в server items/counters. EditorKey.remoteDraftId адресует серверный draft, safe saved metadata сохраняет разрешённый local ID; fields/command восстанавливаются из Room после валидного входа того же account.

Save пишет только на устройство; sync — отдельная foreground команда. Room-транзакции короткие, HTTP/file IO вне них. ACK A сохраняет pending B; logout/deletion fence запрещает позднему ответу создать запись заново. После неизвестного PATCH — GET, затем ack или явный конфликт base/local/remote. Merge сохраняет stable IDs; выбор пользователя сначала локален. Publish/finish online. Pending cover копируется в private durable file, upload descriptor фиксируется до attach; release только после перехода DB/проверки ссылок. GET→PATCH не атомарен без server precondition.

[Таблица состояний, доступ, retention и точки commit](t10-room-drafts.md). [Проверки и NOT_RUN](../validation/t10-m05-implementation.md). M03 registration replay/server list scope не переписаны; их повторные регрессии в M05 не запускались по команде пользователя.

## M06: remote scanner/volunteers

Remote M06 compiled, но sparse/replay/24h/cooldown/concurrent scan/revoke/late result/camera поведение NOT_VERIFIED. Старые T07 tests описывают legacy demo и не являются новым evidence. Server authority и отсутствие offline допуска — реализованные guards, не доказанный сценарный результат. [Детальная текущая модель](t10-scanner-volunteers.md), [выполненная проверка/NOT_RUN](../validation/t10-m06-implementation.md).

## M07: dashboard/участники

M07 remote Kotlin/Java/DI compiled, но coherent snapshot при гонках, stale/query/page/actor/finally guards, cancel/scan и sparse historical answers сценарно NOT_VERIFIED. Прежние T08 tests/прогон aaca508 не выдаются за новые M07 результаты. [Текущая модель](t10-dashboard-attendees.md), [реальная проверка и ограничения](../validation/t10-m07-implementation.md).
