# M03: регистрации, билеты и серверный PDF

Фактический срез M03 поверх `a5daf87`, с исправлениями двух P2 ревью `3277e90`; статус/доказательства — [реализация](../validation/t10-m03-implementation.md) и [исправления](../validation/t10-m03-p2-fixes.md). M03 принят пользователем после повторного ревью cec5520 17 ноября: [подтверждение](../validation/t10-m03-user-review-confirmation.md). M03 начат прямой командой пользователя 16 ноября, без субагентов. Повторное независимое ревью узкого исправления M02 остаётся открытым; запуск M03 не переименовывает его в TECH_ACCEPTED. M04 editor/MyEvents/covers описан отдельно в [текущей архитектуре](t10-editor-covers.md); M05–M09 не реализованы. Ручная приёмка только в конце цикла.

## Контракты и authority

`RegistrationRepository` сохраняет отдельные guest/auth команды. В обеих есть локальный `operationId`, отправляемый как Idempotency-Key. Это ID логической команды, не registrationId сервера. `RemoteRegistrations` использует существующий AuthHttp/RemoteAuthRepository и машинные коды API. Auth body содержит только consentAccepted/answers; контакт берётся сервером из профиля, поэтому поля контакта авторизованного участника в форме недоступны для редактирования. Guest body дополнительно содержит guest/fullName/email/phone, пустой optional phone передаётся как `""`. Десять question types из M02 кодируются как string/string[]/boolean; false не превращается в отсутствующий ответ.

`accountCreated` и guest `accessToken` не создают Session. EMAIL_TAKEN открывает login с очисткой анкеты; перенос ответов и autosubmit отсутствуют. ALREADY_REGISTERED не содержит гарантированного ticketId: authenticated adapter ищет незакрытый билет этого eventId только через собственные upcoming/past списки. Не найденный/неоднозначный результат не подменяется выдуманным ID. Ошибки мест/завершения/validation/network/timeout/protocol/cooldown различаются.

`TicketEventSummary` — отдельная server projection, а не полный Event. Сохраняются slug/status/ends/effectiveEnd/finishedAt/capacity/counts/category; Ticket хранит holder email/category/zone/createdAt/checkedInAt. Отсутствующий registrationId представлен null. Remote не создаёт fake organizerId/permissions/Event. Поэтому `OwnTicketResult.Found.event` nullable, а ticket.summary задаёт remote отображение. `MyTicketsResult.Ready.scope/counters` относятся к ответу сервера; client Clock не перераспределяет server upcoming/past и не меняет server lifecycle. Локальные demo Event продолжают использовать statusAt/default +4h.

## Владельцы и retry

- EventKey владеет EventViewModel, неизменным pending command в памяти, сохранённым receipt и fullscreen. Contact/answers не переносятся в login. Успех записывает receipt до публикации UI, затем стирает ввод.
- Локальная валидация текущих questions/policy выполняется только перед первой отправкой новой логической команды. После неопределённого исхода повтор использует тот же command/body/key без повторной валидации по обновлённой анкете, в том числе после добавления обязательного/удаления отвеченного вопроса. Редактирование блокируется до разрешения; HTTP не повторяет мутации автоматически. Snapshot schema refresh не заменяет отправленное тело и не блокирует replay результата. Actor/owner/срок replay/cooldown по-прежнему проверяет adapter; сервер окончательно принимает или отклоняет операцию. RateLimited удерживает допустимый срок повтора.
- SavedStateHandle содержит только boolean `registrationPending` для неопределённой операции; полного тела/ответов/пароля/bearer там нет. После потери процесса без тела новый POST не запускается: предлагаются свои билеты/письмо/setup. Через recreation той же VM pending body остаётся в памяти.
- GuestReceiptState сохраняет компактные ticket/QR/server manual code, email, accountCreated и guest capability только у текущей Event entry. Capability не идёт в bearer, NavKey, TokenStore или product snapshot. Явное закрытие flow удаляет receipt/метаданные; произвольного guest lookup по email/ID нет.
- Data adapter держит только bounded fingerprints/сроки неопределённых команд и локальную epoch, без анкеты/account/email; завершённые попытки удаляются. Captured context проверяется до HTTP и перед результатом. Поздний guest result после login не превращается в receipt нового actor; 401 старого bearer не отзывает новый.

## Чтение, навигация и отмена

Remote host использует общий TicketsRoute через TicketKey; ключ содержит только собственный ticketId, не QR/capability. One-time delivery selection сохраняется consumed через recreation. Переход из server ticket к E использует slug, не внутренний eventId. Private owners M01 очищаются при смене сессии.

TicketsViewModel подписывается на выбранный TicketScope. Смена вкладки отменяет прежнее чтение; pull списка обновляет только этот scope и ожидает ответ. Refresh single-flight; hidden scope не поллится. `data` хранит server list projection с исходными items/events/scope/counters. Отдельный `selectedCard: OwnTicketResult.Found?` хранит выбранную own-карточку, её event/now; до ответа карточка может показывать выбранный элемент списка. Found карточки не добавляет/заменяет элементы списка и не меняет counters при любом порядке ответов list/card. Карточка открывается даже вне текущего server scope. Её observer устанавливается до запуска list observer, поэтому мгновенный ответ списка не сбрасывает восстановленный ticketSelection. Back/select(null)/смена вкладки очищают selectedCard; список остаётся серверным. Жест карточки читает только её GET, не соседний список, и применяет ответ лишь к всё ещё выбранной карточке того же owner. Transient errors сохраняют данные; Forbidden/NotFound убирают приватную карточку/QR и недоступный элемент прежнего списка, authentication очищает приватное состояние. Stop отменяет observer/read/command и подавляет старый finally.

POST cancel применяет ответ сервера, различает TICKET_USED и NotFound; права/места решает сервер. Он инвалидирует upcoming/past; клиент не уменьшает счётчики локально как окончательную истину. QR доступен только Active при активном server event, Used/Cancelled показываются отдельно. Self cancel доступен для server Active согласно backend, без выдуманного отдельного регистрационного ID.

## Наблюдение за использованием билета — 18 ноября

Пока TicketsRoute видим и RESUMED, TicketsViewModel повторяет read своей текущей области каждые 5 секунд; refresh выбранной server-карточки не меняет чужой scope/счётчики. stop отменяет poll/read, owner guards запрещают поздние ответы. При Used карточка скрывает QR/fullscreen и показывает ResultMark с зелёной отметкой.

GuestReceipt использует отдельный read-порт TicketRepository.observeGuestTicket(ticketId, guestAccess). Remote GET использует query token без bearer; capability остаётся только в SavedState текущего receipt, не в списках/кеше. Poll принадлежит видимому RESUMED receipt; ответ применяется по owner/идентичности receipt. Статус сохраняется только в saved state этого flow. При Used/Cancelled опрос останавливается. Это серверный read, клиент не выполняет check-in. [Поставка и NOT_RUN](../validation/2026-11-18-production-ui-fixes.md).

## PDF и SAF

`DocumentSnapshot.Remote` содержит transient `DocumentSource`, stamp и request; DTO/HTTP/bytes не попадают в ViewModel. DocumentStamp допускает guest accountId=null. RemoteTicketDocuments выдаёт source только для TicketPdf, XLSX остаётся M08. Bearer отправляется только на API origin; guest PDF использует query capability из живого receipt и не отправляет её как bearer.

AuthHttp.downloadPdf: cancellation, redirects/retry отключены, HTTPS/origin/path guards прежние, MIME application/pdf, `%PDF-` и предел 16 MiB до пользовательской записи. Полный bounded byte buffer существует только в data во время скачивания, не в UI/saved state. DocumentFailure передаёт typed transport/storage/auth ошибки в platform. Текущий bearer-401 очищает сессию через прежний gateway.

AndroidDocumentFiles пишет источник во временный private file, повторно проверяет stamp и только затем открывает SAF destination; DocumentStreams проверяет доступ/отмену перед каждым chunk. Temporary удаляется в finally; при ошибке не объявляется «сохранено». Logout/смена entry отменяют DocumentsViewModel и исключают позднюю запись. Picker request/capability не persist; сохраняется только picker ID, stale callback не скачивает документ заново. Частичный файл у внешнего provider при прерванной записи не удаляется без разрешения пользователя — прежнее ограничение SAF. Ticket resend в remote UI скрыт; legacy demo resend удаляется полностью в M08.

## Fake и совместимость

FakeTicketOperations и FakeAuthRepository создают passwordless account и setup-link без session/password proof в той же server-like транзакции, что билет/место/outbox. Setup подтверждает email; вход после него ручной. Владелец регистрации теперь userId нового аккаунта; совместимые legacy guest записи с userId=null связываются по нормализованной почте только при однозначном account match.

Новые demo билеты имеют EVT-код, ручной ввод принимает EVT и сохранённый legacy 12-char формат. Старые QR/ManualCode/Used/check-in proofs не переписываются. Fake replay — bounded память имитируемого сервера на время среды, не Android guest receipt cache; cold-start не обещает durable replay. Новый процесс без тела не запускает повтор автоматически. Guest PDF remote реализован; demo guest без remote capability не притворяется серверной PDF-операцией.

Schema6 сохраняется: nullable auth password proof уже поддержан EPA2, manualCode уже записывается отдельно. Remote TicketEventSummary/guestAccess и transient replay не записываются в fake product file. Codec допускает только настоящий fake registrationId при записи. Legacy fixtures не моделируются искусственным downgrade нового EVT credential в формат v2, который его не содержит: перед таким тестом конструируется настоящий legacy manual proof, затем его сохранность проверяется полностью. Legacy reset-links по прежней политике M01 отзываются, verification/password proofs сохраняются.

## Проверки

RemoteTicketsTest: guest without Session, typed payload, immutable retry, EMAIL_TAKEN, late actor, 429, scopes/counters/nullable Event, cancel/Used, ALREADY_REGISTERED, PDF capability/MIME. M03RegistrationStateTest: pending body/key после добавления обязательного/удаления отвеченного вопроса для guest и auth, no blind resend after restore, saved capability lifetime. M03TicketsStateTest: изоляция out-of-scope карточки при обоих порядках list/card и после Back, восстановление selection при немедленном list, refresh только карточки с её новым status/now, Forbidden cleanup. M03GuestRegistrationTest: fake replay/account/setup/manual login. Старые EventViewModel/RefreshOwners/Documents tests сохраняют lifecycle/SAF guards. StagingTicketsSmokeTest использует только синтетического owner и собственные event/ticket, затем DELETE /me; без создания неочищаемого guest account. Числа/команды/NOT_RUN — в отчётах реализации и исправлений, эти указатели сами по себе не являются новым прогоном.

## Представление анкеты и билета — code28

SELECT сохраняет доменный optionId, но отображается отдельным раскрывающимся SelectField, CHOICE — прежними вариантами. Вопросы всех типов разделены нумерованными карточками, ошибка/help остаются внутри своего вопроса. Inline TicketQr открывает прежний fullscreen через optional onOpen; у fullscreen onOpen отсутствует. EventPassSection и внутренний Column занимают доступную ширину независимо от QR/status. Ticket.category остаётся в серверной модели, но убран из карточки как невыбранная пользователем неоднозначная проекция первого choice; права по нему не определяются. [Поставка/NOT_RUN](../validation/2026-11-18-production-ui-followup.md).

## Одиночный выбор — code30

CHOICE в EventScreen использует RadioOption (стандартный Material3 RadioButton, parent selectable row, Role.RadioButton/selectableGroup) вместо OptionCard. SELECT сохраняет dropdown; transport остаётся Answer.SingleChoice с прежним optionId, новое поле/тип API не вводятся. [Поставка/NOT_RUN](../validation/2026-11-18-ui-spacing-actions.md).
