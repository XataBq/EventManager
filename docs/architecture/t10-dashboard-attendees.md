# M07: remote dashboard и участники

**IMPLEMENTED/COMPILE_VERIFIED; поведение NOT_VERIFIED.** Контракт — локальный OpenAPI1.1.1 и [mapping](../api/migration-mapping.md). Одна итоговая remote compilation пройдена; [точный результат и NOT_RUN](../validation/t10-m07-implementation.md). Переход после M06 без review gate разрешён пользователем, старые review/H-пункты не закрыты.

## Владельцы и projection

[RemoteAttendanceRepository](../../data/remote/src/main/kotlin/ru/eventpass/data/remote/RemoteAttendanceRepository.kt) координирует owner header, Stats и первую страницу как один DashboardResult.Ready(firstPage). Header читается по internal ID и проходит существующий RemoteOrganizerRepository.mapEditor, без нового synthetic owner/Event. Remote DI использует один organizer и общие singleton RemoteTickets/RemoteCheckInRepository для адресной инвалидации; новых модулей/библиотек/Room schema нет.

[AttendanceModels](../../core/domain/src/main/kotlin/ru/eventpass/core/domain/attendance/AttendanceModels.kt): remote Attendee.registrationId/registeredAt/checkerId отсутствуют (null). ticketId — настоящий server ticket ID; present/expected/cancelled соответствуют USED/ACTIVE/CANCELLED. Historical answers хранятся transient как boolean/string/string[] через Answer.Checkbox/Text/MultipleChoice; текущие questions не используются для переинтерпретации истории. Неизвестные status/type/date — Protocol. QR/scan credential в projection нет.

AttendanceSummary содержит server registered/checkedIn/notCheckedIn/capacity; remaining — арифметическая разница capacity/registered, не length списка. Attendees.total — только server search/filter total. Volunteer count — размер успешного списка M06 данного E, ошибка/неизвестное остаются null с отдельной ошибкой, не нулём. fetchedAt — локальное время получения, server updatedAt не придуман. Remote header.statusAuthoritative не даёт UI определять live/finished по часам устройства.

## Согласованность и страницы

Ключ чтения — captured auth context + eventId + нормализованный query. Stats.revision и page1.revision обязаны совпадать до публикации Ready; частичные header/stats/page не публикуются как новый снимок. Header API не предоставляет revision, поэтому атомарность отдельного header GET со Stats не выдумывается. Network/Timeout/503 сохраняют у VM прежний согласованный snapshot с ошибкой, без новых totals поверх старых rows.

AttendanceCursor хранит event/query/opaque revision/следующий **server page**/pageSize. Offset оставлен только для legacy demo; remote не использует его как server cursor. GET attendees передаёт search (до300 code points), page с1, pageSize1..100 и revision до64. Page/size/total/unique ticket IDs и revision проверяются. Append разрешён только к прежнему revision/query/actor; выбранная строка никогда не вставляется в server list/counters как details.

409 **STALE_PAGE** распознаётся только в attendees read по machine code. Старую цепочку rows/next VM сбрасывает; snapshot read ограничен двумя попытками, автоматическое восстановление цепочки — одним follow-up до нового явного действия. Иные409 не становятся stale recovery; TICKET_USED обрабатывается только как owner cancel отказ. Бесконечного retry/polling нет.

[DashboardViewModel](../../app/src/main/kotlin/ru/eventpass/app/feature/dashboard/DashboardViewModel.kt) сохраняет guards aaca508: intervening observer отменяет право старого refresh результата заменить snapshot, opaque revision не сортируется. Query меняет observation/request/read owner, отменяет прежние read/page и чистит предыдущую цепочку. Новый Ready с firstPage принимается атомарно. Legacy adapter без firstPage очищает rows перед новым revision. finally старого запроса не снимает loading новой операции.

## Отмена, доступ, M06

UI/port `cancelTicket(eventId,ticketId)` отправляет POST `events/{id}/attendees/{ticket_id}/cancel`; ticketId/status=cancelled проверяются до успеха. Legacy cancelRegistration не используется remote, fake переводит ticketId в реальную существующую registration relationship, без подмены ID. Неопределённый ответ не объявляется отменой и не ставится в очередь; Used/403/404/429/transport/protocol различимы.

После подтверждения инвалидируется только E: VM перечитывает согласованный snapshot текущего query, RemoteTickets сигнализирует перечитать открытую карточку ticketId и загруженные scopes, содержащие это E; scanner cache этого E сбрасывается без HTTP скрытого родителя. Scanner visible/resume read получает свежий server access/history. Totals/status строк локально не угадываются. Ошибка read после известной отмены показывается отдельно от подтверждённого server command результата. Гонку cancel/scan решает backend.

401 текущего bearer проходит общий auth gateway; 403/NotFound/session change очищают private VM/selection/answers и запрещают команды, а не превращают event отказ в общий logout. Ответы проверяются по actor/version до commit, consumer edge скрывает queued чужой результат. Reads/mutations scoped, SQL/DTO/HTTP в VM/Compose не попадают.

## UI / lifecycle / предел проверки

Remote MyEvents organizer → прежний DashboardKey с internal eventId → те же ScannerKey/VolunteersKey → Back в Dashboard. Visible RESUMED перечитывает snapshot, hidden parent отменяет read/page/command и не поллится. Query сохраняется у живой entry VM через rotation/дочерний экран, но не в SavedState/Room: поиск может содержать имя/email. Process loss теряет query/selection/PII; credential/answers не persist и не логируются. Legacy сохранённый query marker удаляется при создании VM.

Общий DashboardScreen показывает серверные totals, неизвестный volunteer count, search/page/error, historical answer values без выдуманных question labels и owner confirmation. Серверный XLSX подключён в M08 через прежний SAF, без dashboard query/page и без local fallback. Существующие demo consumers адаптированы минимально, demo compilation/сценарии не запускались. Конкурентность, stale recovery, late actor, cancel/scan race, lifecycle и live API **NOT_VERIFIED** — compile не заменяет эти проверки.
