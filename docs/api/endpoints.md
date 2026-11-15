# Реестр методов API

**Заменено решениями пользователя 13 ноября.** Действующее задание backend: [итоговые доработки](../32-backend-final-requirements.md). Этот документ и OpenAPI v0.1.0 сохраняют историю предложения, не являются обязательным текущим контрактом. Принимаем серверный API с согласованными расширениями; не возвращать autoaccount/verified-only/reset/TTL/deletion к прежним правилам из этого файла.

Сгенерирован из [openapi.json](openapi.json); бизнес-правила — [контракт](../22-backend-api-contract.md). Все пути относительно `/api/v1`. `public` — без сессии; `optional` — допускается bearer; `user` — подтверждённый аккаунт; `owner/scanner` — право на конкретный объект.

| Метод и путь | Назначение | Доступ | JSON запроса → успешный ответ |
|---|---|---|---|
| `GET /config` | Конфигурация и версии соглашений | public | — → 200 Config |
| `POST /auth/sign-up` | Создать аккаунт и письмо подтверждения | public | SignUpRequest → 201 EmailChallenge |
| `POST /auth/email-verification/requests` | Повторить письмо подтверждения | public | EmailRequest → 202 EmailChallenge |
| `POST /auth/email-verification/confirm` | Подтвердить email без входа | public | ConfirmEmailRequest → 200 ConfirmedEmail |
| `POST /auth/sign-in` | Войти по email и паролю | public | LoginRequest → 200 SessionTokens |
| `POST /auth/refresh` | Обновить и ротировать пару токенов | public | RefreshRequest → 200 SessionTokens |
| `POST /auth/sign-out` | Отозвать сессию устройства | public | LogoutRequest → 204 без тела |
| `POST /auth/password-reset/requests` | Запросить код восстановления | public | EmailRequest → 202 EmailChallenge |
| `POST /auth/password-reset/verify` | Обменять шестизначный код на resetGrant | public | ResetVerifyRequest → 200 ResetGrant |
| `POST /auth/password-reset/complete` | Задать пароль и отозвать все сессии | public | ResetCompleteRequest → 204 без тела |
| `GET /me` | Получить собственный профиль | user | — → 200 Account |
| `PUT /me` | Сохранить имя, телефон, организацию и уведомления | user | ProfileUpdate → 200 Account |
| `POST /me/deletion-preview` | Рассчитать последствия удаления | user | EmptyRequest → 200 DeletionPreview |
| `POST /me/deletion` | Подтвердить удаление аккаунта и созданных событий | user | DeleteAccountRequest → 202 DeletionReceipt |
| `GET /events/{eventId}` | Открыть публичную страницу мероприятия | optional | — → 200 PublicEvent |
| `POST /events/{eventId}/registrations/guest` | Выдать гостевой билет без создания аккаунта | public | GuestRegistrationRequest → 201 GuestIssued; 202 GuestExisting |
| `POST /events/{eventId}/ticket-email-requests` | Запросить письмо с существующим билетом | public | GuestResendRequest → 202 AcceptedMail |
| `POST /events/{eventId}/registrations` | Зарегистрировать текущего пользователя | user | RegistrationRequest → 201 Registered; 200 Registered |
| `GET /me/tickets` | Список собственных текущих билетов и истории | user | — → 200 TicketPage |
| `GET /me/tickets/{ticketId}` | Получить собственный билет | user | — → 200 Ticket |
| `POST /me/tickets/{ticketId}/cancellation` | Отменить собственный ACTIVE билет | user | EmptyRequest → 200 Ticket |
| `POST /me/tickets/{ticketId}/email-requests` | Повторить письмо на email владельца билета | user | EmptyRequest → 202 MailReceipt |
| `POST /me/tickets/{ticketId}/pdf-jobs` | Подготовить PDF собственного текущего билета | user | EmptyRequest → 202 DocumentJob |
| `GET /me/events` | Список мероприятий по независимой связи | user | — → 200 MyEventPage |
| `GET /me/event-drafts` | Список неопубликованных черновиков владельца | user | — → 200 DraftPage |
| `GET /event-drafts/{draftId}` | Прочитать свой черновик | owner | — → 200 Draft |
| `PUT /event-drafts/{draftId}` | Создать или сохранить свой неполный черновик | user | SaveDraftRequest → 200 Draft |
| `POST /event-drafts/{draftId}/publication` | Опубликовать один раз и получить ссылку | owner | RevisionRequest → 200 Publication |
| `GET /events/{eventId}/editor` | Данные редактора и ограничения полей | owner | — → 200 ManagedEvent |
| `PUT /events/{eventId}/editor` | Сохранить опубликованное мероприятие | owner | UpdateEventRequest → 200 ManagedEvent |
| `POST /events/{eventId}/registration-closure` | Закрыть приём заявок | owner | RevisionRequest → 200 PublicEvent |
| `POST /events/{eventId}/completion` | Завершить событие вручную | owner | RevisionRequest → 200 PublicEvent |
| `POST /assets/event-covers` | Загрузить обложку PNG/JPEG до 5 MiB | user | multipart file → 201 Cover |
| `GET /events/{eventId}/volunteers` | Список активных волонтёров и их первых проходов | owner | — → 200 VolunteerPage |
| `POST /events/{eventId}/volunteers` | Выдать сканирование существующему аккаунту по email или ID | owner | GrantTarget → 200 GrantResult |
| `DELETE /events/{eventId}/volunteers/{userId}` | Отозвать сканирование, сохранив билеты и историю | owner | — → 204 без тела |
| `GET /events/{eventId}/scan-access` | Проверить текущее право сканирования | scanner | — → 200 ScanAccess |
| `POST /events/{eventId}/check-ins` | Проверить QR или ручной код и отметить первый проход | scanner | CheckInRequest → 200 CheckInResult |
| `GET /events/{eventId}/dashboard` | Счётчики явки и версия данных | owner | — → 200 Dashboard |
| `GET /events/{eventId}/attendees` | Список участников с поиском и ответами | owner | — → 200 AttendeePage |
| `POST /events/{eventId}/attendees/{registrationId}/cancellation` | Отменить регистрацию участника от имени владельца | owner | EmptyRequest → 200 CancelAttendeeResult |
| `POST /events/{eventId}/exports` | Подготовить XLSX всех участников и ответов | owner | EmptyRequest → 202 DocumentJob |
| `GET /document-jobs/{jobId}` | Статус подготовки своего PDF или экспорта | user | — → 200 DocumentJob |
| `GET /document-jobs/{jobId}/content` | Скачать готовый файл с повторной проверкой доступа | user | — → 200 binary |
