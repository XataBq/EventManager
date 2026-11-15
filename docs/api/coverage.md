# Покрытие API и переход от demo

**Заменено решениями пользователя 13 ноября.** Действующее задание backend: [итоговые доработки](../32-backend-final-requirements.md). Этот документ и OpenAPI v0.1.0 сохраняют историю предложения, не являются обязательным текущим контрактом. Принимаем серверный API с согласованными расширениями; не возвращать autoaccount/verified-only/reset/TTL/deletion к прежним правилам из этого файла.

Срез кода: `bb62cd0`, T01–T06 и независимая T07 foundation. [Контракт](../22-backend-api-contract.md) — целевое предложение, [05](../05-data-contracts.md) — действующие domain/fake-порты. Новые HTTP DTO не означают, что соответствующий Kotlin port уже существует.

## Сценарий → ручки → код

| Сценарий / mobile-frame | operationId из OpenAPI | Текущая точка интеграции |
|---|---|---|
| Общие поля, согласие, первый запуск | getConfig | RegistrationPolicy/AuthInputPolicy; добавить remote config/legal mapping |
| G04/G05, подтверждение и reset | signUp, requestVerification, confirmEmail, signIn, refreshSession, signOut, requestReset, verifyReset, completeReset | [AuthRepository](../../core/domain/src/main/kotlin/ru/eventpass/core/domain/auth/AuthRepository.kt), [AuthViewModels](../../app/src/main/kotlin/ru/eventpass/app/feature/auth/AuthViewModels.kt), [AppSessionViewModel](../../app/src/main/kotlin/ru/eventpass/app/navigation/auth/AppSessionViewModel.kt) |
| P03 профиль, ID, notifications, удаление | getMe, updateMe, previewDeletion, deleteAccount | Account пока минимальный; полный ProfileRepository/reauth/delete — T09/T10 |
| G01/P01 публичное E, G02 guest receipt, G03 login branch | getEvent, registerGuest, requestGuestTicketEmail, registerAuthenticated | [Event/RegistrationRepository](../../core/domain/src/main/kotlin/ru/eventpass/core/domain/ProductRepositories.kt), [EventViewModel](../../app/src/main/kotlin/ru/eventpass/app/feature/event/EventViewModel.kt); guest existing-email развилка меняется в предлагаемом API |
| P02 текущие/история/отмена/письмо | getMyTickets, getMyTicket, cancelMyTicket, resendMyTicket | TicketRepository, [TicketsViewModel](../../app/src/main/kotlin/ru/eventpass/app/feature/tickets/TicketsViewModel.kt); resend port ещё нужен |
| O01/V01/черновики | getMyEvents, getDrafts | EventRepository.observeMyEvents; пагинация за remote repository, не fake прямо из VM |
| O02/O03 редактор/публикация/управление | getDraft, saveDraft, publishDraft, getEventEditor, updateEvent, closeRegistration, finishEvent, uploadCover | EventRepository, [EventEditing](../../core/domain/src/main/kotlin/ru/eventpass/core/domain/event/EventEditing.kt), [EditorViewModel](../../app/src/main/kotlin/ru/eventpass/app/feature/editor/EditorViewModel.kt), platform CoverImporter |
| O05 права волонтёров | getVolunteers, grantVolunteer, revokeVolunteer | [VolunteerRepository](../../core/domain/src/main/kotlin/ru/eventpass/core/domain/checkin/CheckInModels.kt); UI ещё не подключён |
| V02–V05 единый сканер owner/volunteer | getScanAccess, checkIn | [CheckInRepository](../../core/domain/src/main/kotlin/ru/eventpass/core/domain/checkin/CheckInModels.kt), [ScannerController](../../core/domain/src/main/kotlin/ru/eventpass/core/domain/checkin/ScannerController.kt); transport replay semantics расширить |
| O04 dashboard/поиск/отмена участника | getDashboard, getAttendees, cancelAttendee | Attendance/dashboard ports ещё предстоит реализовать в T08; общая модель ticket остаётся |
| P02 PDF, O04 XLSX, сохранение Android | createTicketPdf, exportAttendees, getDocumentJob, downloadDocument | Documents port и Android stream/SAF adapter — T09/T10; не имитировать файл строкой |

Покрыты все 18 mobile-frame из [реестра](../03-navigation.md); 06 UI-kit не требует собственного серверного API. Confirm/reset/fullscreen/dialogs — дополнительные необходимые состояния, а не отдельные бизнес-роли. Эндпоинты не копируются для владельца и волонтёра.

## Отличия текущего fake от целевого API

| Сейчас | В предложении | Что изменить при интеграции |
|---|---|---|
| Local session 30 минут, без refresh и восстановления после нового процесса | Access 15 мин, rotating refresh/семья сессии | Remote AuthRepository, protected storage, single-flight refresh; не сбрасывать навигацию на каждом expiry access |
| Session.expiresAt обслуживает окончательное истечение demo | Expiry access ещё не означает logout | Явно определить session validity/refreshable state в domain/coordinator, transport tokens оставить внутри data |
| SignUp без consent versions в domain | terms/privacy versions в командном контракте | Расширить auth/registration command или application policy adapter, не добавлять DTO в UI |
| Guest на известный account email → RequiresLogin | Предложение разрешить новую guest регистрацию; повтор существующего билета только письмо | Согласовать развилку, обновить fake/VM/S03 и tests отдельной задачей |
| Email exact trim при большинстве fake операций | Единая case-insensitive/IDNA политика | Централизованная normalization policy; проверить совпадения до миграции реальных данных |
| ManualCode выводится из QR по demo алгоритму | Сервер выдаёт независимый случайный code | Remote mapper использует поле ответа; UI не вычисляет code. Для сохранённого guest receipt нужна новая версия с явным code |
| checkIn(eventId, credential) без ID операции | Idempotency/replay и первый record обязательны | Контекст команды в repository/domain; отличить replay исходного успеха от нового ADMITTED в ScannerController |
| Fake Used может быть без first record в legacy/debug | Новая production USED всегда с записью | Не распространять fake legacy null на production success; удалённый checker nullable отдельно |
| Self-grant допустим в fake | TARGET_IS_OWNER, owner и так сканирует | Локальная корректировка grant validation после согласования, без изменения владения |
| Вся коллекция наблюдается через StateFlow | Cursor pages/polling и freshness | Состояние страниц/refresh за repository или feature state; 5-секундный polling только видимой поверхности |
| Локальный EventCover assetId/mime/size | URL/asset ownership/upload/TTL | Remote cover descriptor/loader; не передавать file path Android на сервер |
| Account содержит id/email/verified | Имя/телефон/организация/notifications/revision | Полный profile model/port, снимки заявок не переписывать сохранением профиля |
| Нет document jobs, owner attendee cancellation, deletion | Полный набор для T08–T10 | Отдельные порты по рабочим срезам, не создавать заранее неиспользуемые абстракции |

Ни одна из строк не реализована этой документационной задачей. Не править текущий demo автоматически по предложению до согласования соответствующего пункта; T07 может продолжать независимую работу в своих границах.

## Покрытие первоначального backend brief

| Исторические вопросы | Решение в новом контракте |
|---|---|
| B01 | EventAccess, independent grants, owner/scanner scope, проверка каждого запроса |
| B02/B04 | GuestIssued/GuestExisting, receipt lifetime, без аккаунта и без сохранённой анкеты |
| B03 | Полный auth/refresh/confirm/reset/logout, TTL, storage, revoke |
| B05–B08 | Lifecycle, валидация, форма/revision, capacity, cancellation |
| B09–B12 | Idempotency, check-in outcomes, QR/manual code, grants |
| B13/B14 | Списки/поиск/cursor/totals/polling/freshness |
| B15/B16 | Draft/save/publish/update, cover upload, plain text program |
| B17/B18 | PDF/XLSX jobs, безопасный download, очередь писем и preferences |
| B19/B20 | Профиль и отдельная предложенная политика удаления с preview |
| B21–B24 | Production links/domains, errors/rate limits, staging acceptance, legal/config/versioning |

## Проверка комплекта

Результат 31 октября 2026: **PASS**, 44 операции, 72 схемы, 74 корректных примера и 14 отклонённых отрицательных примеров. Проверены все JSON request bodies и все JSON success responses операций, локальные `$ref`, path parameters, operationId, ссылки комплекта и покрытие сценариев. Полная OpenAPI-проверка через `openapi-spec-validator 0.7.2` также прошла. Установлен изолированно в игнорируемой `.gradle/api-contract-validation`, зависимости приложения и системная Python-среда не изменялись.

Архитектурная проверка: 11 текущих страниц, 190 локальных ссылок, 9 Mermaid-блоков, 18 mobile IDs, S01–S14 и ссылки на именованные тесты — успешно. `git diff --check` — без ошибок (уведомления о CRLF/LF в ранее изменённых документах). `git diff --name-only -- app core data gradle` — пусто. Новые Android tests/build и ревью кода не запускались; предыдущие пользовательские изменения сохранены. Команды:

```powershell
python scripts/verify-api-contract.py --validator-path .gradle/api-contract-validation
python scripts/verify-architecture-docs.py
git diff --check
```

`verify-api-contract.py` может использовать `--validator-path .gradle/api-contract-validation`, если `openapi-spec-validator` установлен изолированно. JSON Schema требует `jsonschema` (в текущей Python-среде уже был). Скрипт не устанавливает пакеты и не обращается к серверу. Примеры используют только вымышленные данные и credentials; их нельзя принимать на production.

Не проверены работа backend/staging, реальная доставка, криптографическая реализация, гонки БД, производительность, Android remote/device UI. Это спецификация и проверка её согласованности. Android unit/build не запускались: Kotlin/Gradle не меняются.
