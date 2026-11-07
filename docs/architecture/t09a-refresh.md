# T09a: обновление видимой поверхности

Текущая реализация: один [EventPassRefresh](../../core/designsystem/src/main/kotlin/ru/eventpass/core/designsystem/EventPassRefresh.kt) в `core:designsystem`, адресная команда в VM конкретной entry, прежние repository ports/общий store. Backend/API/schema 5 не менялись. Матрица исходного кода, запуски и приёмка — [30](../30-t09a-refresh-validation.md).

## Владельцы и команды

| Поверхность и владелец | Read | Что сохраняется при pull |
|---|---|---|
| Tickets root, список current/history и выбранная карточка | TicketRepository.refreshMyTickets | history/selectedTicketId, родитель/позиция; observer применяет прежнюю политику статуса/QR и удаления исчезнувшего выбора |
| MyEvents root, три сегмента | EventRepository.refreshMyEvents, одна коллекция | Сегмент и имеющийся контент/scroll, без трёх запросов |
| EventKey/flowId | EventRepository.refreshPublicEvent(eventId) | Локальная анкета при той же formVersion; новая схема явно очищает ответы/consent и показывает StaleForm |
| DashboardKey | AttendanceRepository.refreshDashboard(eventId) + attendees текущего query | Query/totals/строки до ответа; та же revision перечитывает страницу без предварительного удаления строк, новая revision инвалидирует старые страницы |
| VolunteersKey | VolunteerRepository.refreshVolunteers(eventId) → VolunteersResult | Input/target, разрешённый список; grant/revoke не вызываются |
| Root Profile | ProfileRepository.refreshProfile → ProfileResult | Чистая форма принимает профиль; dirty fields сохраняются, baseline обновляется; несовпавшее изменение baseline показывает конфликт перед явным save |

Route передаёт только callback/state своего владельца. Общий компонент не знает ID/сессию/navigation/repository. Никакого refresh dispatcher/event bus/второго store нет. Публикация действительных изменений общего state связанным observers допустима; initiating read другого экрана не вызывается.

## Lifecycle и конкурентность

Tickets/MyEvents/Event теперь читают только при **visible + RESUMED**, как Dashboard/Volunteers/Profile. PrivateShell вычисляет visible по текущему табу и верхнему ключу конкретного стека; public host — по верхней public entry. Hidden parent/tab не запускает lifecycle read. Повторный вход/foreground делает обычное актуальное чтение, без восстановления gesture-команды из SavedStateHandle.

Каждая VM имеет owner generation и отменяемый read job. Пока выполняется initial/lifecycle/manual read, повторный пользовательский pull игнорируется. Loading первоначального чтения отделён от refreshing; прежние доступные данные остаются при Network/Timeout/Storage, отдельный retry не теряется. Stop/смена ресурса отменяют или инвалидируют чтение; late result/finally старого owner не меняет индикатор/ошибку нового. Порты Tickets/MyEvents/Event публикуют результат через observer; их fake update повторно проверяет coroutine cancellation/auth generation под прежним Mutex.

Dashboard сохраняет observation guard `aaca508`: любой intervening observer result важнее старого refresh reply, opaque revision/фиксированный Clock не определяют новизну. Только внутренний stale/mismatched page ставит один follow-up, который выполняется после текущего read. Повторный жест такой очереди не создаёт. Job публикуется до старта, stop очищает очередь, refreshing включает согласованное чтение страницы. Ошибка страницы имеет свой адресный retry/«Загрузить ещё».

Profile/Volunteers также сравнивают captured observation/owner перед применением refresh result. Начало save/grant/revoke инвалидирует более старое чтение. Access/session loss очищает PII по прежней политике; новая private scope уничтожает старые owners. Profile read не вызывает start/reset/save/sign-out; Volunteers read не вызывает start/grant/revoke. Mutation failure/retry остаются у своей команды: revoke — внутри Dialog, grant — рядом с вводом, profile save — в своей форме/confirmation.

Tickets/MyEvents сохраняют разрешённую read-model в памяти текущей private scope при pause/hidden для выбора и scroll; observation/commands/read останавливаются. Dashboard/Volunteers/Profile очищают transient state при stop как раньше, query/сегменты остаются в безопасном saved state. Event stopReading инвалидирует чтение, прежний leaveForm очищает форму при dispose/уходе; pull не является уходом. Новых PII/credentials/refresh flags в SavedStateHandle нет.

## Жест и исключения

Pinned Material3 1.4.0 `Modifier.pullToRefresh` подключается над **существующим** AuthColumn.verticalScroll либо Dashboard.LazyColumn. Поэтому short/empty/error content тоже имеет target, а middle-of-list сначала прокручивается к началу. Порог/vertical slop/nested-scroll предоставляет Material3; горизонтальные/текстовые/IME interactions не заменены собственным pointer interceptor. Indicator — по центру сверху в content уже защищённого Scaffold, с существующими цветами/отступами. Обычные кнопки «Обновить» удалены; «Повторить», resend, more/next/mutation actions имеют прежний смысл.

Gesture distance — только `remember`, очищается при смене enabled/композиции. Семантика контейнера содержит custom action «Обновить данные» и live stateDescription; повтор действия во время refreshing не ставит очередь. Наличие semantics/автоматический вызов action не означает реальную TalkBack-приёмку.

Auth/editor/publication/Scanner/guest receipt не обёрнуты в компонент. QR fullscreen и confirmation выключают underlying action; собственное Dialog окно не пересылает touch нижележащему контенту. SAF/внешнее окно не даёт underlying touch; Activity pause останавливает чтение. DocumentsViewModel/идемпотентный bind `c75ce56` не менялись: Tickets сохраняет выбранную read-model, Dashboard всегда держит DocumentActions в header даже при paused observer; configuration detach не очищает pending export. Process death по-прежнему не восстанавливает transient pending/writer.

## Проверки

- [RefreshOwnersTest](../../app/src/test/kotlin/ru/eventpass/app/feature/RefreshOwnersTest.kt): port/resource, single flight, retained selection/segment/form/dirty baseline, schema change, old finally/new owner/actor, observer priority.
- [DashboardViewModelTest](../../app/src/test/kotlin/ru/eventpass/app/feature/dashboard/DashboardViewModelTest.kt): прежние observer/page/follow-up регрессии, no user queue, same-revision rows до ответа страницы.
- [RefreshReadContractsTest](../../data/fake/src/test/kotlin/ru/eventpass/data/fake/product/RefreshReadContractsTest.kt): owner/account guards, typed transient read errors, прежний store/session/disk без read-мутаций.
- [PullRefreshComponentTest](../../app/src/androidTest/kotlin/ru/eventpass/app/PullRefreshComponentTest.kt), [RefreshRoutesTest](../../app/src/androidTest/kotlin/ru/eventpass/app/RefreshRoutesTest.kt): настоящий swipeDown, long/middle/short/error, текущая/hidden entry и конкретный initiating port, overlay/guest/Scanner, dirty profile/target.
- [DashboardIntegrationTest](../../app/src/androidTest/kotlin/ru/eventpass/app/DashboardIntegrationTest.kt) использует жест вместо удалённого dashboard-refresh. Back/SAF и прежние tests остаются адресными регрессиями; точные завершённые запуски и пределы — в 30.
