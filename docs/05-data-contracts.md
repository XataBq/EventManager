# Стабы, контракты и переход на API

Актуальные правила — [B01–B08 от 27 октября](12-backend-answers-2026-10-27.md). Гостевой submit возвращает QR-билет и отправляет письмо, без создания аккаунта. Показанный билет сохраняется в saved state текущего flow до его закрытия. Постоянный guest cache и сохранение анкеты перед входом исключены.

**Реализация T02.1:** `core:domain` содержит модели/validation/access policy и минимальные EventRepository, submitGuest, observeMyTickets; `data:fake` предоставляет их из одного FakeEventPassEnvironment. Конкретные ошибки этой итерации называются RegistrationError, а полный AppError/набор команд ниже — целевой проектный перечень. На этапе T02.1 auth/persistence отсутствовали; выполненная T02.2 описана ниже. Android guest saved state и остальные команды ещё не реализованы. См. [проверки и границы T02.1](16-t02-domain-fake.md).

## Выбор подхода

**Фактическая T02.2, 28 октября (T04 до H1):** добавлен AuthRepository: observeSession/currentSession/refreshSession, signUp/signIn/signOut, requestVerification/confirmEmail, requestPasswordResetCode/verifyResetCode/resetPassword. Результаты AuthResult типизированы; AuthError различает input/credentials/unverified/taken/challenge/cooldown/network/timeout/storage/session-change/busy. `reauthenticate` не реализован. Оба пути в observeSession/currentSession читают одну сессию того же FakeEventPassEnvironment, что и guards events/tickets; currentSession — IO-free локальный snapshot, не сетевой вход.

SignUp/confirm/reset не создают сессию; после подтверждения и сброса требуется ручной SignIn. Reset code одноразово выдаёт секретный grant, reset меняет проверочное представление пароля и очищает сессию. Accounts/proofs/challenge hashes/deadlines/consumption сохраняются через versioned auth snapshot; outbox и сессия — в памяти. Повтор отправки после cooldown заменяет старый challenge. Это временные demo defaults, не будущие DTO/API. Повреждённая схема блокирует auth; write failure не публикует успех. Созданные guest registrations/tickets ещё не persist, клиентского guest cache нет. UI/outbox controls и Activity wiring ждут H1. Полные границы/проверки — [18-t04-validation.md](18-t04-validation.md); целевой перечень портов ниже шире выполненной итерации.

Использовать **stateful fake за интерфейсами репозиториев**. В разговоре его можно называть стабом: это локальная реализация будущего поведения, а не жёстко зашитый ответ каждого экрана. Она должна сохранять причинные связи: регистрация → билет → проход → явка; отзыв grant → исчезновение мероприятия из волонтёрской вкладки.

Не создавать сейчас выдуманный REST/OpenAPI и не заставлять Compose обращаться к MockWebServer. HTTP-фикстуры пригодятся позже для проверки реального сетевого адаптера. Предложенные ниже операции — мобильные порты, не согласованный серверный контракт.

## Порты и операции

| Порт | Чтение / наблюдение | Команды |
|---|---|---|
| AuthRepository | observeSession | signIn, signUp, signOut, requestVerification, confirmEmail, requestPasswordResetCode, verifyResetCode, resetPassword, reauthenticate; подтверждение не создаёт сессию |
| ProfileRepository | observeProfile | updateProfile, updateEmailPreference, deleteAccount |
| EventRepository | observePublicEvent, observeMyEvents, observeAccess | saveDraft, publish, updateEvent, finishEvent, closeRegistration |
| RegistrationRepository | observeOwnRegistrations | submitGuest, registerAuthenticated, cancelRegistration; без completePending |
| TicketRepository | observeMyTickets (билеты на email аккаунта) | observeTicket, getTicketDocument; только после входа |
| VolunteerRepository | observeVolunteers (owner only) | grantByEmailOrId, revoke |
| CheckInRepository | readResult, observeOwnScanCount | checkIn(eventId, credential); requestId только если его поддержит реальный API |
| AttendanceRepository | observeDashboard, observeAttendees(query, cursor) | exportAttendees(eventId) |
| Navigation return destination | Разрешённый маршрут возврата | Только eventId/route; не хранит ответы анкеты |

Кто вызывает закрытую команду, определяется сессией внутри реализации, а не параметром actorId из UI. Гость получает snapshot своего билета только в результате успешного submit; UI удерживает его в saved state flow. Произвольного чтения ticketId и списка гостю нет. Время и ID инъектируются. После signUp/confirmEmail нужен явный signIn; repo возвращает в том числе билеты, созданные до аккаунта.

Пример формы контрактов (псевдо-Kotlin, ещё не исходный код):

```kotlin
interface RegistrationRepository {
    suspend fun submitGuest(command: GuestRegistrationCommand): GuestRegistrationOutcome
    suspend fun registerAuthenticated(command: RegistrationCommand): RegistrationOutcome
}

sealed interface GuestRegistrationOutcome {
    data class Issued(val ticket: GuestTicketSnapshot, val email: String) : GuestRegistrationOutcome
    data class RequiresLogin(val email: String) : GuestRegistrationOutcome
    data class ExistingRegistration(val email: String) : GuestRegistrationOutcome // без ticketId/QR
    data class Failed(val error: AppError) : GuestRegistrationOutcome
}

sealed interface RegistrationOutcome {
    data class Created(val ticketId: TicketId) : RegistrationOutcome
    data class AlreadyRegistered(val ticketId: TicketId) : RegistrationOutcome
    data class Failed(val error: AppError) : RegistrationOutcome
}

sealed interface ScanOutcome {
    data class Admitted(val checkIn: CheckIn) : ScanOutcome
    data class AlreadyUsed(val firstCheckIn: CheckInSummary) : ScanOutcome
    data class Invalid(val reason: InvalidTicketReason) : ScanOutcome
    data class Failed(val error: AppError) : ScanOutcome
}
```

В `InvalidTicketReason`: NotFound, WrongEvent, Cancelled. Маппер backend может свести подробные причины к общей, если сервер не раскрывает их. Ошибка доступа не должна возвращать ФИО чужого участника.

Guest/auth исходы разделены, чтобы AlreadyRegistered(ticketId) не превращался в обход гостевого доступа. ExistingRegistration для гостя — предложенный безопасный default без повторного показа старого QR; точный серверный ответ уточняется при интеграции. Guest snapshot появляется только из нового успешного submit, не по номеру/email или восстановлению старого запроса. Примеры — форма клиентских портов, не зафиксированная JSON-схема.

## Общее fake-хранилище

**P2 cfc0624:** подготовленная signIn-сессия принимается только после успешного возврата из IO на контекст caller, с проверкой активности/generation/аккаунта/срока до публикации. Отмена до этой точки не активирует сессию. Уже записанные долговечные account/challenge изменения при отмене не откатываются: память и auth snapshot остаются согласованными. Явный retry confirmation delivery после ошибки/отмены разрешён, повторная мутация успешного delivery блокируется handler/backend. Проверки границ — в [отчёте T04](18-t04-validation.md).

`FakeBackendStore` содержит users, sessions, events, registrations с email и optional userId, tickets, grants, checkIns, черновики мероприятий и demo mail outbox. Все репозитории используют один store. Гостевой submit не добавляет User; последующий вход с тем же email открывает соответствующие билеты. Derived lists/counters строятся из этих данных, без независимых чисел для разных экранов.

Fake воспроизводит пользовательские исходы: права, соответствие ticket/event, свободные места/закрытие/начало события, статус анкеты и ошибки. Конкуренцию настоящих регистраций и устройство транзакций сервера не моделируем. Flow публикуется после изменения общего состояния. Ошибки и время задаются явно, без случайности.

Для demo snapshot: versioned schema, reset и seed, восстановление данных fake-сервера/черновиков мероприятий после перезапуска. Ответы незавершённой анкеты перед входом и после process death не сохранять. Пароли fake — искусственные данные; реальные пароли не сохраняются. Выход очищает приватное клиентское состояние, но не весь fake-сервер: ранее оформленные билеты остаются на email.

Состояние default demo сохраняется, unit tests каждый раз получают новый store. Версия seed выводится в debug-панели и в отчёте H-приёмки.

## Набор сценариев

| ID | Fixture / действие | Ожидаемый результат |
|---|---|---|
| S01 | u-alex: owner A, volunteer B, participant C, participant+volunteer D | Все связи видны одновременно; права не переносятся между событиями |
| S02 | Anonymous + submit → G02 → соседний экран/Back → закрытие flow → signUp/confirm/signIn | QR доступен в сохранённом flow и после пересоздания Activity, но не после закрытия/нового запуска; User не создан submit-ом; после входа билет доступен по email |
| S03 | Anonymous + существующая почта | RequiresLogin; ответы теряются, после входа форму заполняют заново; автоотправки нет |
| S04 | Ответы сервера: без лимита / remaining=0 / CapacityReached при submit | Безлимит доступен, 0 блокирует кнопку; отказ отправки показывается как «мест нет», без теста гонки |
| S05 | Active / Used / Cancelled / чужой event / неизвестный QR | Пять детерминированных исходов проверки |
| S06 | Отзыв grant при открытой камере | Следующая операция Forbidden, выход из сканера; свой билет остаётся |
| S07 | Двойной тап во время отправки | Один клиентский запрос, кнопка в loading; протокол server retry не выдумывать |
| S08 | Ошибка сети / timeout при запросе | Честная ошибка связи; без ложного успеха и автоматического повторного submit |
| S09 | Пустые tickets/owned/volunteer/drafts, прошедшие | Правильные подсказки; без кнопки каталога |
| S10 | Подтверждение аккаунта / код сброса / expired session | После подтверждения ручной login; reset по коду без отдельного verify email; недоступная сессия ведёт во вход |
| S11 | Нет разрешения камеры / постоянный отказ | Ручной ввод остаётся доступен |
| S12 | Email retry failure, export failure, grant user missing | Честная ошибка, билет не исчезает, повтор возможен |
| S13 | Длинные данные, font scaling, большие списки | Нет обрезания действий; поиск работает |
| S14 | Смена формы до первой регистрации / попытка правки после неё | Устаревшую форму показать как ошибку с перезагрузкой; после первой регистрации вопросы не редактируются; переноса ответов не обещаем |

Debug ScenarioController умеет выбрать seed, сменить тестовую личность, изменить сетевой сценарий и воспроизвести внешнее событие (отзыв, проход другим пользователем). Не включать его в production release. Смена debug-личности не равна глобальному переключению роли.

## Правила результатов операций

- `register`: сервер решает доступность/места; после startsAt регистрация закрыта. Нет лимита — неограниченно; 0 оставшихся мест — закрыто. Fake имитирует исход, не серверную гонку. Гостевой билет занимает место и не создаёт User.
- `signIn`: не отправляет анкету автоматически. observeMyTickets после входа включает билеты на email аккаунта, созданные до его регистрации.
- `checkIn`: право на E обязательно; Active переходит в Used, повтор Used даёт AlreadyUsed. Не начислять посещение из клиентского распознавания QR до успешного ответа repo.
- `cancel`: повтор отмены не освобождает ещё одно место. По рабочему варианту Q07 Used не отменяем. Тот же grant остаётся действовать. Новая регистрация после отмены — отдельная операция с новым токеном, если разрешена политикой и есть места.
- `grant/revoke`: идемпотентные операции для пары event/user; отзывается только grant, не весь пользователь. Исторический проверивший сохраняется в аудите до отдельного решения о удалении.
- Итоги: registered = неотменённые регистрации, arrived = успешные checkIns в этом наборе, awaiting = registered − arrived. Пока событие идёт, лучше «Ещё не пришли», а не окончательное «Не пришли». Изменение текста согласовать H3.

## Документы, письма и камера

- PDF и экспорт должны отдавать **реальные файлы** для ручной проверки, если задача заявлена готовой. Fake может использовать детерминированные локальные файлы; не переименовывать CSV в XLSX или текст в PDF. В первом вертикальном срезе допустим явный «ещё не реализовано», но не toast «скачано» без файла.
- Будущий backend предпочтительно формирует билет и XLSX; клиент получает document descriptor/stream, а `DocumentSaver` сохраняет через системный выбор места/делится URI. Формат, MIME, ошибки, отмена пользователем — часть контракта.
- Fake письмо — запись в debug outbox; гостевой submit также возвращает GuestTicketSnapshot с QR. Snapshot хранит UI в SavedStateHandle flow, не в постоянном клиентском cache. После закрытия flow fake не восстанавливает его по произвольному ID, хотя запись билета остаётся в данных fake-сервера для будущего кабинета. Outbox — служебный debug-инструмент; настоящие письма отправляет backend. Гостевой PDF отдельно не задан.
- QR decoder (камера или тестовый источник) возвращает строку; бизнес-проверку делает CheckInRepository. CameraX/ML Kit не решают, можно ли пропускать человека.
- Backend недоступен после считывания → приостановить камеру, показать проблему связи, не показывать красный «недействителен» и не зелёный «разрешён».

## Переход на настоящий API

1. Получить auth, OpenAPI/другой контракт, error schema, временные зоны, права, QR/check-in/idempotency и правила файлов/ссылок. Не выдавать текущие порты за обязательный серверный дизайн.
2. Создать `:data:remote`: transport DTO → domain mapping, session handling, server error mapping. Production секреты и baseUrl вне UI.
3. Выбрать механизм observe-потоков по backend: push/SSE/WebSocket или согласованный polling. UI знает лишь Flow и freshness, не протокол. В фоне остановить ненужные подписки.
4. Прогнать общий набор contract tests для fake и remote с управляемым тестовым сервером; API может потребовать уточнения domain-контракта, это оформляется отдельным изменением.
5. Переключить Hilt bindings в app через build variant `demo`/`api`; один режим на сборку, не случайный fake fallback при HTTP-ошибке.
6. Повторить H2–H4 на staging, включая видимость гостевых билетов после отдельной регистрации аккаунта и два устройства для проверки синхронизации. Проверки транзакций распределения мест принадлежат backend, Android проверяет ответы и UI.
7. Production-сборка не содержит fake store/debug outbox/автовход; при ещё не реализованном API release должен быть явно заблокирован.

«Централизованно заменить» означает заменить реализации и DI, сохранив UI-контракты. Это не обещание, что интеграция сведётся к смене одной строки URL.
