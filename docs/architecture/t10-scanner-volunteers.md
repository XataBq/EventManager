# M06: remote Scanner и Volunteers

**IMPLEMENTED / COMPILE_VERIFIED; поведение NOT_VERIFIED.** Контракт — локальный OpenAPI1.1.1, [mapping](../api/migration-mapping.md), [команда и ограничения](../validation/t10-m06-implementation.md). M05/M02 не становятся TECH_ACCEPTED; переход без review gate прямо разрешён пользователем. Dashboard и его связи с M06 подключены в [M07](t10-dashboard-attendees.md), поведение также NOT_VERIFIED.

## Владельцы и модели

- [RemoteCheckInRepository](../../data/remote/src/main/kotlin/ru/eventpass/data/remote/RemoteCheckInRepository.kt) владеет GET scanner / POST scan, captured auth context, per-event read/write single-flight, scope/version guards, server counts/recent и transient fingerprints/24h/cooldown metadata.
- [RemoteVolunteersRepository](../../data/remote/src/main/kotlin/ru/eventpass/data/remote/RemoteVolunteersRepository.kt) владеет list/grant/revoke, отдельным read/write owner/version, типизированными отказами и обновлением только данного event.
- [CheckInModels](../../core/domain/src/main/kotlin/ru/eventpass/core/domain/checkin/CheckInModels.kt): Confirmed(decision, sparse ScanReceipt, replayed), ScannerInfo(summary/role/checkedByMe/recent), Volunteer с publicId/fullName/grantedAt/count. Legacy CheckInRecord/Admitted/AlreadyUsed и Updated(ScanGrant) сохранены для существующих fake consumers; remote не создаёт их из отсутствующих данных.
- [ScannerController](../../core/domain/src/main/kotlin/ru/eventpass/core/domain/checkin/ScannerController.kt) — entry owner неизменных credential/key и одного POST; payload не сохраняется в Room/DataStore/SavedState. ScanCommand хранит первый attempted-send timestamp в памяти, не продлевая окно при повторе. VM/Compose не знают DTO, HTTP, fake/remote классы.

Server checkedInBy — отображаемое имя. TicketId/checkerId/время не выдумываются, русский reason показывается как текст, а не распознаётся в машинную enum. Unknown/malformed DTO даёт безопасный Protocol; null optional поля остаются null. EventSummary переиспользуется, не превращается в полный Event с fake owner.

## Операции и доступ

GET `events/{id}/scanner` подтверждает event summary и viewerRole organizer/volunteer. Remote не выводит terminal из часов телефона: finished/finishedAt/revoke/403 закрывают доступ только к этому E. 401 проходит через общий RemoteAuthRepository cleanup. Actor/session capture проверяется до/после HTTP и на consumer edge; старый ответ другого scope не даёт result новому entry. Network refresh сохраняет ранее подтверждённый access с readError; это позволяет лишь новую проверку **сервером**, не offline admission. После cold process сначала загружается access/recent.

Manual принимает только `EVT-[A-Z0-9]{6}` с нормализацией регистра. QR остаётся непрозрачным code ≤300 code points; credential не выводится из другого credential. Legacy demo parser/derived proofs не менялись. POST `events/{id}/scan` содержит ровно `{code}` и Idempotency-Key; один camera cycle/двойное нажатие не создают несколько команд. Ответ allowed/already_used/invalid подтверждает сервер.

Network/Timeout/503/Protocol/cancel не доказывают rollback. Controller сохраняет команду в памяти, блокирует новые camera/manual отправки и предлагает явный повтор того же code/body/key. Первый attempted-send timestamp и 429 cooldown не сбрасываются на retry; после 24h/IDEMPOTENCY_CONFLICT — сверка истории без нового POST/key. Неизвестный outcome при потере owner/процесса не превращается в постоянную очередь или автоматический новый scan. Другой actor/event/revoked scope очищает команду.

`replayed=true` рисуется нейтрально как восстановленный ответ, не новый зелёный допуск; звука/вибрации не добавлено. Счётчики локально не увеличиваются: после ответа выполняется адресный GET scanner, доступно явное обновление истории. Гонку двух сканеров решает backend, клиент этого не обещает. Конкурентность reads/mutations сценарно не проверялась.

GET volunteers возвращает фактические userId/publicId/fullName/email/grantedAt/checkedInCount. POST `{identifier}` принимает email/publicId, ответ Granted содержит Volunteer; DELETE по **userId** подтверждает Revoked без разбора 204 как JSON и без искусственного grantId. Обновляется projection данного E и выполняется его read; старые GET ограничены version guard. Grant/revoke не меняют билеты и другие E. Errors/retry остаются внутри существующего confirmation; cooldown и access errors типизированы.

## UI / lifetime / границы

Remote MyEvents открывает прежние ScannerKey и VolunteersKey по internal eventId. Result остаётся состоянием Scanner VM; Result Back → ready, затем Back → родитель. ScannerAccessLostKey — только сообщение при возврате после отказа. Public slug для scanner не используется. Кнопки зависят от server tab/roles; окончательная авторизация каждого запроса серверная.

Visible RESUMED перечитывает access; hidden/background отменяет чтение/POST и очищает ввод/результат, сохраняя неизвестную команду у живой VM для явного retry. Permission/camera bind/unbind/manual fallback прежние. Observer binding, request generation и finally guard не дают позднему завершению менять новый entry. Ни скрытого polling, ни фоновой sync-очереди нет. Volunteers hidden/stop очищает transient UI и отменяет commands.

Room/draft/auth contracts M05, registration/guest/tickets M03, dashboard/attendance M07, profile/delete/export M08 не перерабатывались. Данные Scanner/Volunteers — transient private projection, не fake snapshot/Room migration. Compilation подтверждает связывание, **не** camera/retry/race/revoke поведение; эти проверки выполняются только по отдельной команде.
