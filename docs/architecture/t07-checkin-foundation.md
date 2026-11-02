# T07: текущая независимая основа grants/check-in

T06 ещё ожидает организаторского smoke; пользователь прямо выбрал независимую часть T07. Здесь описаны реализованные domain/fake/persistence и чистая модель состояния. Scanner/Volunteers routes, Hilt ViewModel/Screen, системное разрешение CAMERA, CameraX/analyzer и полный UI-путь ещё не подключены. Приёмки и числа проверок — в [отчёте](../21-t07-validation.md).

## Ответственность и зависимости

| Владелец | Текущее поведение |
|---|---|
| [CheckInModels](../../core/domain/src/main/kotlin/ru/eventpass/core/domain/checkin/CheckInModels.kt) | VolunteerRepository/CheckInRepository, typed scope/access/outcomes, QrToken отдельно от ManualCode, record первого прохода и временная manual-code policy |
| [ScannerController](../../core/domain/src/main/kotlin/ru/eventpass/core/domain/checkin/ScannerController.kt) | Чистый Kotlin/coroutines entry-owned state machine на одном dispatcher. Не Android ViewModel и не камера |
| [FakeCheckInOperations](../../data/fake/src/main/kotlin/ru/eventpass/data/fake/product/FakeCheckInOperations.kt) | Guarded owner grant/revoke/list; access/checkIn в том же state/Mutex/IO/storage, что auth/tickets/editor |
| [FakeEventPassEnvironment](../../data/fake/src/main/kotlin/ru/eventpass/data/fake/product/FakeEventPassEnvironment.kt) | Composition root новых портов, общие grants/checkIns и управляемые typed failures; никакого второго backend |
| [ProductSnapshotCodec](../../data/fake/src/main/kotlin/ru/eventpass/data/fake/product/ProductSnapshotCodec.kt) | Schema 4, чтение v1/v2/v3, manual credentials/first records; прежние writers сохраняют новые данные |
| [DemoModule](../../app/src/demo/kotlin/ru/eventpass/app/di/DemoModule.kt) | Bindings новых портов; обычные UI/ViewModels их пока не вызывают |

Пять модулей сохранены. Domain runtime уже использовал coroutines; добавлен только test dependency на ту же зафиксированную coroutines-test, которая была в app/fake. Platform dependencies и manifest CAMERA не менялись. Decoder ещё не выбран/не подключён: его решение остаётся Android T07 после gate.

## Права и операция

Actor — текущий подтверждённый аккаунт из общей session. Все private mutations захватывают session generation до очереди и проверяют её, expiry, eventId и EventAccessPolicy после получения общего Mutex на IO. Owner сканирует без self-grant, volunteer — только с active grant. Право владения не исчезает при revoke собственной связи. Participant не становится scanner. List/grant/revoke доступны только owner; missing target не создаёт аккаунт/приглашение/письмо. Grant по существующему email/ID и revoke идемпотентны. MyEvents наблюдает тот же grants list; participation/tickets и история не удаляются.

Check-in: initialize → captured generation → IO/Mutex → session/access → credential/принадлежность E/status → следующий state → полный snapshot write → StateFlow → typed outcome. Storage failure не публикует Used/Admitted или grant. Queue cancellation до принятия lock не мутирует данные и освобождает поток caller. Уже выполненный commit не обещает rollback при поздней отмене/потере ответа.

Active → Used + один CheckInRecord(event/ticket/checker/time/name/display number). Event Clock — существующий отдельный Clock событий; auth expiry использует auth Clock. Used → AlreadyUsed с тем же record; не добавляет запись/место. Cancelled/WrongEvent/Unknown → Invalid без PII/изменений. Ошибка связи/access/storage — Failed, не Invalid или успешный допуск. CheckIn не меняет число занятых мест; Used не отменяется существующим cancel. История сохраняется после revoke. Demo setTicketStatus не может обратить уже записанный check-in.

Текущий обратимый default согласован с Ticket.isCurrent: закрытие регистрации/startsAt не запрещают check-in. Active на Draft/Cancelled/Finished/после endsAt не допускается. Used остаётся AlreadyUsed с прежней историей, даже если событие позже закончилось. Production time/credential semantics ещё требует backend.

## Credentials и schema 4

ManualCode — 12 символов алфавита `23456789ABCDEFGHJKLMNPQRSTUVWXYZ`, case-insensitive ввод с пробелами/дефисами-разделителями. Только demo: SHA-256 от domain-separated непрозрачного QR payload, по 5 bits из первых 12 bytes. Не производный от печатного номера/публичного ticketId, не production security contract. Область lookup — только после проверки права на выбранное E; WrongEvent не раскрывает ФИО. Credential wrappers и record/snapshot diagnostics редактируют секреты/PII в toString. Примеры — только отдельный локальный demo-артефакт, не production-логи.

V4 сохраняет старый v3 product payload и добавляет проверяемый ticketId/manualCode mapping + first records. Reader валидирует уникальность codes/records, ссылки на существующий Used ticket/checker/event. V1/v2/v3 не меняют QR/ID/статус/answers/auth: manual code выводится из того же opaque payload, mapping/records сохранены и в текущей schema 5; [единые migration/writers](../05-data-contracts.md). Нельзя восстановить неизвестные checker/time для старого debug/legacy Used: AlreadyUsed.first = null, без выдуманного прохода. Текущие writers auth/editor/ticket/grants/check-in используют один codec и сохраняют соседние поля. Corrupt/truncated/unsupported snapshot не затирается seed-ом. Обратной миграции v4 → v3 нет.

GuestReceiptState format не менялся. Старый saved guest receipt конструирует новый compact snapshot с тем же детерминированным code из имеющегося payload; никаких lookup по ticketId/email. Manual code ещё не показан на билетах UI — это часть интеграции после T06, не объявленная готовой поверхность.

## Владение чистой моделью scanner

ScannerController создаётся будущим владельцем entry и получает port + CoroutineScope; явный close удаляет observer/operation/result. State не сериализуется, credentials только в локальном параметре suspend call. После нового процесса начинается новый scan; прошлый Admitted не восстанавливается по bool. Изменение event/session scope и revoke удаляют result/PII, отменяют pending call и увеличивают generation.

Состояния: access loading/denied, ready, permission required, scanning, submitting, result, error. Permission и foreground — явные inputs, а не системные вызовы. Manual доступен после отказа камеры. CameraActive — производное от foreground/current access/granted/scanning. Background/hidden tab в будущей Route обязан вызвать foreground(false), stop binding — close; Android use cases пока отсутствуют, их освобождение этим кодом не доказано.

Пустой/non-decoded кадр ничего не делает; при нескольких строках одна лексикографически первая за цикл. camera generation и submitting блокируют повтор/старые callbacks. Result останавливает анализ до next; result Back → ready, не новая result entry. Network/Timeout/Storage не Invalid/успех, retry только явным новым вводом. После suspend проверяются cancellation/generation и свежий access; поздний finally не меняет новую отправку. Утрата доступа от command также останавливает scanner, даже если observer ещё не обновился.

## Указатели на доказательства

- [CheckInTest](../../data/fake/src/test/kotlin/ru/eventpass/data/fake/product/CheckInTest.kt): owner/manual → Used → repeat, grant/revoke/списки/participation, invalid/time/access/storage, один record, queued cancel/generation, migration и все writers.
- [ScannerControllerTest](../../core/domain/src/test/kotlin/ru/eventpass/core/domain/checkin/ScannerControllerTest.kt): single-flight frames, permission/manual, foreground, next/Back, denied scope, late result/finally, отсутствие автоматического retry/restored admission.
- [GuestManualCompatibilityTest](../../app/src/test/kotlin/ru/eventpass/app/feature/event/GuestManualCompatibilityTest.kt): старый JSON receipt, тот же credential без repo lookup и нового saved-state формата.
- [LegacyV3Fixture](../../data/fake/src/test/kotlin/ru/eventpass/data/fake/product/LegacyV3Fixture.kt): замороженный writer прежнего формата, не текущий encoder.

Это JVM/contract доказательства, не проверка CameraX/ImageProxy, фокуса/экспозиции, Android permission lifecycle, real QR с бумаги или полного owner→grant→scanner UI. Эти шаги разрешены после организаторского smoke T06 и перечислены в отчёте T07. H3/H4 не закрыты.
