# T08: owner dashboard и общая явка

Текущая demo-реализация поверх T07. Backend/API-предложение ещё не согласованы; remote cursor/polling/security protocol не утверждены. T07 camera/UI и общая H3 ожидают человека. Результаты исходного среза — [26](../26-t08-validation.md), исправления refresh — [27](../27-t08-refresh-validation.md).

## Ответственность и данные

| Исходник | Ответственность |
|---|---|
| [AttendanceModels](../../core/domain/src/main/kotlin/ru/eventpass/core/domain/attendance/AttendanceModels.kt) | AttendanceRepository, header/summary, bounded page/cursor, Attendee, owner cancel outcomes/errors |
| [FakeAttendanceOperations](../../data/fake/src/main/kotlin/ru/eventpass/data/fake/product/FakeAttendanceOperations.kt) | Guarded проекции из общего FakeProductState, snapshot-consistent totals, search/pages/refresh, owner cancel |
| [TicketCancellation](../../data/fake/src/main/kotlin/ru/eventpass/data/fake/product/TicketCancellation.kt) | Общий status-only переход ACTIVE→CANCELLED, CANCELLED idempotent, USED denied; self/owner guards отдельно |
| [DashboardViewModel](../../app/src/main/kotlin/ru/eventpass/app/feature/dashboard/DashboardViewModel.kt) | Entry-owned state, query/owner/request generations, read/refresh/page jobs и confirmation/cancel error |
| [DashboardRoute/Screen](../../app/src/main/kotlin/ru/eventpass/app/feature/dashboard/DashboardScreen.kt) | Visible/RESUMED lifecycle, stateless LazyColumn со stable ticket keys, modal error/retry, callbacks |

AttendanceRepository использует тот же state/Mutex/IO/storage/Clock/auth, что auth/tickets/editor/grants/check-in. Второго store/counters нет. T08 проекции не расширяют persisted payload; текущая schema 5/profile migration описана в [05](../05-data-contracts.md): header/summary/rows/revision/counts вычисляются, faults/read errors transient. Self cancel и owner cancel используют один status transition, но разные actor/resource guards. Registrations/answers/checkIns не удаляются; questionsLocked после первой Registration остаётся.

Owner-only guard через EventAccessPolicy.canViewAttendees: current verified/nonexpired account на каждом observe snapshot/page/refresh/cancel. Actor не передаётся UI. Страница повторно проверяет текущий access/auth generation перед возвратом. Owner другого E, volunteer, participant и guest не получают PII. Вложенный registrationId обязан принадлежать eventId, затем выбирается ticket той же регистрации/E. Generation/status/access перепроверяются под общим Mutex до мутации.

`registered = ACTIVE + USED`, `checkedIn = USED`, `notCheckedIn = ACTIVE`; Cancelled исключены из totals, но сохранены в списке с явным статусом. Remaining = null при unlimited, иначе max(0,capacity−registered). Percent при registered=0 равен 0, иначе целый процент, без overflow Int. До завершения label «Ещё не пришли», после lifecycle FINISHED/endsAt — «Не пришли». Сам startsAt даёт started status, не finished.

Attendee содержит registration/ticket ID, имя/email/displayNumber/status, alias registeredAt из существующего consentAcceptedAt и необязательные first checkedAt/checkerId. Это временный demo timestamp регистрации; новый persisted факт времени не создан. Нет QR/manual, phone или answers. Legacy/debug Used входит в checkedIn, но время/проверявший не выдумываются. Volunteer.checkedInCount — число реальных первых CheckInRecord на E с checkerId этой личности; repeat/invalid не растят, revoke/regrant историю не стирают. Owner без self-grant не добавляется в volunteer list.

## Paging и ревизия

Сортировка consentAcceptedAt DESC, registrationId ASC, ticketId ASC. На запрос максимум 20 строк. Поиск trim + lowercase(Locale.ROOT) substring по имени/displayNumber, не по credentials/email. TotalMatches относится ко всему поиску (включая Cancelled), totals dashboard от него не зависят.

Demo revision — SHA-256 length-prefixed полей проекции (event metadata, auth generation и полный ordered список безопасных row fields). В token нет QR/manual, raw PII строк участников; query остаётся пользовательской строкой поиска. Cursor привязан к eventId/revision/normalized query/offset. Чужой ресурс сначала проходит owner guard; несогласованный cursor → StalePage, отрицательный/выходящий offset → InvalidInput. Это consistency token, не production capability и не согласованный HTTP cursor.

Каждая страница построена из одного захваченного immutable state. При изменении relevant revision VM удаляет старые страницы и загружает первую. Даже если старый запрос завершится позднее, owner/request/revision guards не дадут append к новой выдаче. Изменение query отменяет старую загрузку, сбрасывает страницы, не totals. Загруженные страницы могут накапливаться, layout остаётся LazyColumn со stable ticket IDs. Общий fake хранит всё в памяти и вычисляет проекцию линейным проходом; remote scaling/indexing — T10.

## Lifecycle, ошибки и отмена

DashboardKey(eventId,entryId) — private Nav3 entry в исходном табе. MyEvents owner → Dashboard → прежний Scanner/Volunteers → Back к той же entry/E/query. Camera binding/result routes T07 не менялись. Только visible/current + RESUMED держит observer/page jobs. Hidden/background/navigation away → stop, cancel jobs и очистка dashboard/rows/confirmation; безопасный query в SavedStateHandle остаётся. Возврат/recreation заново авторизует и читает первую страницу. Список/credentials/PII не persist.

Read/refresh error сохраняет доступный последний snapshot и отдельную ошибку; successful refresh показывает updatedAt из event Clock. В demo этот Clock фиксирован, поэтому timestamp может не измениться при повторном чтении. Это локальное обновление store, не синхронизация устройств и не polling/SSE. Access error немедленно очищает state и закрывает entry через host → MyEvents с сообщением. Session replacement/logout уничтожает private owners как раньше.

Observer и refresh согласованы через локальное поколение наблюдений: каждый результат observer (Ready/Failed) увеличивает счётчик. Refresh запоминает owner и счётчик до suspend, его результат принимается только при совпадении обоих после возврата. Поэтому поздний ответ refresh не откатывает уже наблюдавшиеся totals/rows/error. SHA-256 revision не сравнивается по порядку, фиксированный Clock не определяет новизну. Запросы повторного чтения во время refresh объединяются в один follow-up после завершения текущего; page mismatch не теряется за busy guard. Stop/новый owner очищает отложенный запрос; старый finally не снимает refreshing новой entry. Job записывается до старта (CoroutineStart.LAZY), чтобы немедленный ответ не затирал ссылку на follow-up и stop отменял актуальное чтение. Счётчики/флаг только в памяти VM, repository/schema не менялись.

Owner confirmation привязана к конкретному registration ID и хранится только в VM. Network/Timeout/Storage внутри Dialog; retry отправляет ту же отмену, busy блокирует повтор/dismiss. USED возвращает typed error, не меняет status. Поздний cancel result/finally старого owner не снимает busy новой операции. После IO write публикуется общий state: counters/own ticket/list наблюдают одну отмену. Отмена queued coroutine не мутирует; уже выполненный commit не обещает rollback при поздней отмене UI.

Owner XLSX подключён через DocumentsRepository/DocumentFiles T09, независимо от query/pages; [путь файлов](t09-profile-files.md); MAX/участнические ответы/profile/remote не добавлены.

## Доказательства и пределы

- [AttendanceTest](../../data/fake/src/test/kotlin/ru/eventpass/data/fake/product/AttendanceTest.kt): права и forged IDs/pages, guest/auth totals, Used/legacy/counter, cancel/self/race/storage/restart/schema lock, stable pages/search/revision, queued cancel/session, startsAt/end/expiry.
- [DashboardViewModelTest](../../app/src/test/kotlin/ru/eventpass/app/feature/dashboard/DashboardViewModelTest.kt): old query/finally, hidden/late cancel/new owner, denial/PII cleanup, refresh snapshot/freshness, revision reset/totals; delayed refresh A после observer B, ошибка observer vs старый refresh, follow-up чтение страницы, изоляция очереди нового owner и stop после немедленного ответа с запуском отложенного job.
- [DashboardIntegrationTest](../../app/src/androidTest/kotlin/ru/eventpass/app/DashboardIntegrationTest.kt): owner→scanner/manual→Back/явка→Volunteers→Back, query/recreation/modal errors/cancel/refresh/hidden/logout и forged volunteer route. Реальные результаты/ограничения — в отчёте, наличие теста не означает его запуск.

Физическая камера/QR с бумаги/слабый свет и полная ручная H3 не доказаны автоматизацией T08. API 28/37, landscape/gestures/accessibility и новый OS process-kill не объявляются проверенными по API 33 recreation. Следующий срез T09, backend integration T10.
