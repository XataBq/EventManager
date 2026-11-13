# M00: карта миграции Kotlin ports → backend 1.1.1

Статус: REVIEW_PENDING, 13 ноября 2026. Android baseline `0493dbd2e328796cd963689cc25cf3d4d2bd40e1`; действующий demo ещё не изменён. Это target mapping для M01–M09, не описание готовых remote adapters. Согласования [08](../08-decisions.md)/[34](../34-api-migration-plan.md) имеют приоритет над историческим OpenAPI v0.1.0 и заменёнными B01–B08.

## Источник и обозначения

Публичная GET-схема staging получена в M00: [backend-openapi-1.1.1.json](backend-openapi-1.1.1.json), `info.version=1.1.1`, 33 операции / 30 путей. Точная версия deploy SHA не раскрыта сервером; исходный проверенный backend — `def65653b9e1f22dce8bc2d6f266b23ace6cd1d5`, [проверка](../33-backend-update-2026-11-13.md). Historical [openapi.json](openapi.json) не изменён и не является заданием HTTP-клиенту.

Все URL ниже относительно `/api`. KEEP = назначение и сигнатура в основном сохраняются; CHANGE = сохранить ответственность, изменить input/output/semantics; REMOVE = удалить старую операцию и её потребителей в указанном этапе; ADD = новая минимальная способность. Flow = scoped repository state, **не** SSE/push endpoint. Refresh запускает тот же read, не отдельную ручку. Ошибки транспортного уровня общие, см. раздел ниже.

## AuthRepository — M01

Источник: [AuthRepository.kt](../../core/domain/src/main/kotlin/ru/eventpass/core/domain/auth/AuthRepository.kt). Все 11 текущих методов учтены.

| Текущий метод | Решение / API | Input → output / важная граница |
|---|---|---|
| observeSession | KEEP, локальный Flow | Unknown/Guest/Authenticated + generation; не токен и не saved route |
| currentSession | KEEP, та же session source | Атомарный локальный snapshot для guards, не новая независимая auth state |
| refreshSession | CHANGE, GET `/me` | Bearer из TokenStore → User; 401 только текущего token очищает session, Network не подтверждает новые права |
| signUp | CHANGE, POST `/auth/register` | `{fullName,email,password,consentAccepted}` → `{token,user}` / Session вместо EmailChallenge; consent и Session.id требуют адаптации |
| signIn | CHANGE, POST `/auth/login` | `{email,password}` → Session, unverified допустим, expiresAt в ответе отсутствует |
| signOut | CHANGE, POST `/auth/logout` → 204 | Очистить локально даже при сети недоступной; удалённый отзыв тогда не гарантирован |
| requestVerification | CHANGE, POST `/auth/resend-verification` → 204 | `{email}`; никакого challengeId/expiresAt в response, cooldown только если дал 429/Retry-After |
| confirmEmail | REMOVE из remote Android token flow | POST `/auth/email-link` `{token,password?}` → 204 существует, исполняется web-страницей. Возврат в app → refresh /me; не копировать auth token в поле приложения |
| requestPasswordResetCode | CHANGE/rename requestPasswordReset, POST `/auth/forgot-password` → 204 | `{email}`, одинаковый принятый исход для существующего/несуществующего аккаунта, без кода |
| verifyResetCode | REMOVE | Аналога code/resetGrant нет; setup/reset по ссылке 24h |
| resetPassword | REMOVE из Android grant flow | Пароль задаёт web email-link; setup/reset подтверждает email, удаляет старые sessions, далее ручной login |

ADD M01: получение конфигурации документов `GET /auth/consent` → `{terms:{url,version},privacy:{url,version}}`. Null URL не заменять выдуманной ссылкой. Конкретный domain port выбирать вместе с первым auth UI, не раздувать generic config repository.

Session DTO bearer — секрет, local Session.id — несекретный идентификатор, expiresAt не симулировать. Password length для signup/setup/reset 8–256, для login 1–256: нельзя отклонять login по signup min8. fullName max300; локальные правила не должны беспричинно отвергать допустимые сервером имена. Локальный email validation не является проверкой существования аккаунта. Setup/reset/verify, consent, профиль, authority и хранение — ADR-T10-02–04.

## EventRepository — M02/M04

Источник: [ProductRepositories.kt](../../core/domain/src/main/kotlin/ru/eventpass/core/domain/ProductRepositories.kt). Все 10 методов учтены.

| Метод | Решение / API | Input/output и семантика |
|---|---|---|
| observePublicEvent | CHANGE M02, GET `/events/{slug}` | EventSlug/PublicEventReference, не EventId в URL; `{event,viewer}`; viewer.ticketId/isOrganizer/isVolunteer — независимые связи, permission не глобальная роль |
| refreshPublicEvent | CHANGE M02, тот же GET | Single-flight видимого resource; stale response не обновляет другой E/owner |
| observeMyEvents | CHANGE M04, GET `/me/events?tab=organizing\|volunteering\|drafts` | API отдаёт один tab/items/counters/roles, текущий MyEventsResult ждёт три массива; в target хранить per-tab readiness, не выдавать незагруженный tab за пустой |
| observeEditor | CHANGE M04, GET `/events/by-id/{event_id}` | Owner-only, EventDetails со status draft nullable start/capacity; questionsLocked сервер не даёт |
| refreshMyEvents | CHANGE M04, GET текущего tab | Не три фоновых запроса скрытым tab без причины |
| saveDraft | CHANGE M04, POST `/events` / PATCH `/events/{event_id}` | Создание `{publish:false,...fields}` с idempotency key, update — только изменённые поля; stable local DraftId→server id/slug mapping; expectedRevision не серверная precondition |
| publishDraft | CHANGE M04, POST `/events/{event_id}/publish` | Существующий id, без ожидаемой server revision; EventDetails, повтор стабилен |
| updateEvent | CHANGE M04, PATCH `/events/{event_id}` | Удалить expectedFormVersion/write-revision обещания; исторические ответы не меняются от текущей анкеты |
| closeRegistration | REMOVE M02–M04 | Ни ручки, ни отдельной target кнопки нет; live registration допустима |
| finishEvent | CHANGE M04, POST `/events/{event_id}/finish` | Owner-only; finishedAt сохраняется, повтор не оживляет событие; returned EventDetails |

EventInput: title/description/startsAt/endsAt/location/capacity/priceType/questions/sections/coverAssetId. Непереданное поле PATCH сохраняет старое, `endsAt:null` = startsAt+4h, `coverAssetId:null` = detach. Draft допускает null startsAt/capacity; published требует capacity>0 и заполненные поля. Server статусы draft/published/live/finished; manual finishedAt приоритетен. Нет произвольного zoneId в API — UI форматирует Instant в выбранной app timezone, не объявляет её server field.

ADD M04: upload `POST /assets/event-covers`, multipart file → `{assetId,url,mimeType,byteCount,width,height}`, `GET /assets/event-covers/{asset_id}` для чтения (owner до публикации/публично после). Нельзя передать local assetId в EventInput. PriceType server free/paid — metadata, оплаты не реализуем.

## RegistrationRepository / TicketRepository — M03

Источник: [ProductRepositories.kt](../../core/domain/src/main/kotlin/ru/eventpass/core/domain/ProductRepositories.kt), [RegistrationModels](../../core/domain/src/main/kotlin/ru/eventpass/core/domain/registration/RegistrationModels.kt). Все 2 + 4 метода учтены.

| Метод | Решение / API | Input/output |
|---|---|---|
| submitGuest | CHANGE, POST `/events/{event_id}/registrations` | `{consentAccepted,answers,guest:{fullName,email,phone:""}}` + Idempotency-Key → `{ticket,accountCreated,accessToken}`; passwordless account не session |
| submitAuthenticated | CHANGE, тот же POST с bearer | `{consentAccepted,answers}`; server берёт имя/phone из профиля, не из отдельной регистрационной команды |
| observeMyTickets | CHANGE, GET `/me/tickets?scope=upcoming\|past` | `{items,counters}` на scope; отдельная readiness tab, не клиента по одному current Active фильтру |
| observeOwnTicket | CHANGE, GET `/tickets/{ticket_id}` | Bearer владельца; guest token только живого flow в query `token`, не доступ по ID |
| refreshMyTickets | CHANGE, тот же GET текущего scope | Сохранить tab/selection, не подменять hidden ресурс |
| cancelOwnTicket | CHANGE, POST `/tickets/{ticket_id}/cancel` | Ticket response, active→cancelled; 409 TICKET_USED; повтор cancelled успешен |

Нет formVersion в API. Unknown question IDs → field error; не отправлять устаревшую анкету молча. Answer transport `boolean|string|string[]`; Question types text/textarea/email/tel/number/date/choice/select/multiple/checkbox. Option value/label/hint, question help/placeholder сохраняются; type нельзя терять при отображении/редактировании. Unknown type — безопасный отказ submit, не подмена Text. required checkbox=true, optional=false допустим; choice/select/multiple при публикации минимум 2 options. Старый snapshot answer_labels на сервере не является новой схемой анкеты.

Ticket включает code/qrToken/status/holderName/holderEmail/category/zone/createdAt/checkedInAt/event. qrToken непрозрачный; display code EVT-xxxxxx и guest accessToken — разные значения. TicketEvent не содержит полного questions/organizerId/coverUrl. Нельзя собрать полноценный Event из TicketEvent с fake owner/пустыми permissions: при необходимости отдельное чтение public E по slug. ALREADY_REGISTERED не гарантирует ticketId в ошибке; перечитать свои tickets/событие. EMAIL_TAKEN → login без сохранения анкеты/автоповтора. Guest receipt compact state — у исходной entry, без постоянного кеша.

## CheckInRepository / VolunteerRepository — M06

Источник: [CheckInModels](../../core/domain/src/main/kotlin/ru/eventpass/core/domain/checkin/CheckInModels.kt). Все 3 + 4 метода учтены.

| Метод | Решение / API | Input/output |
|---|---|---|
| observeAccess | CHANGE, GET `/events/{event_id}/scanner` | ScannerInfo: event summary/viewerRole/checkedByMe/recent, не boolean grant из local state |
| refreshAccess | CHANGE, тот же GET | Foreground/lifecycle-bound; no hidden polling; 403 revoke/finished ≠ общий logout |
| checkIn | CHANGE, POST `/events/{event_id}/scan` | `{code: qrToken или EVT-xxxxxx}` + key → ScanResult; replayed=true не новый допуск |
| observeVolunteers | CHANGE, GET `/events/{event_id}/volunteers` | Array userId/publicId/fullName/email/grantedAt/checkedInCount |
| refreshVolunteers | KEEP, тот же GET | Single-flight owner resource |
| grant | CHANGE, POST `/events/{event_id}/volunteers` | `{identifier: email или publicId}` → Volunteer, не ScanGrant без дополнительных полей |
| revoke | KEEP, DELETE `/events/{event_id}/volunteers/{volunteer_id}` → 204 | volunteer_id здесь userId; повтор не требует искусственного grant ID |

ScanResult result=allowed/already_used/invalid; optional scanId/replayed/reason/ticketCode/holderName/category/checkedInAt/checkedInBy. **checkedInBy — отображаемое имя, не UserId**. Scan ID выдаётся только при использовании idempotency key; ticketId/checkerId в ответе нет. Текущий обязательный CheckInRecord не переносится 1:1: использовать sparse projection без выдуманных ID. Неверный/чужой билет может не иметь вообще contact/time/code. reason — русский display text, не машинный InvalidTicketReason: нельзя определять WRONG_EVENT по совпадению текста, допустим generic invalid. QR и EVT не derivation друг друга.

## AttendanceRepository — M07

Источник: [AttendanceModels](../../core/domain/src/main/kotlin/ru/eventpass/core/domain/attendance/AttendanceModels.kt). Все 4 метода учтены.

| Метод | Решение / API | Input/output |
|---|---|---|
| observeDashboard | CHANGE, GET `/events/{event_id}/stats` + details при необходимости | `{revision,registered,capacity,checkedIn,notCheckedIn}`; header из отдельного доступного EventDetails, не дублированная authority |
| refreshDashboard | CHANGE, те же read | Stats/page версии сравниваются, смешанную комбинацию не выдавать Ready |
| attendees | CHANGE, GET `/events/{event_id}/attendees?search=&page=&pageSize=&revision=` | `{revision,items,total,page,pageSize}`, pageSize≤100; local cursor должен хранить query/E/revision и server page, не обещать cursor protocol |
| cancelRegistration | CHANGE input registrationId→ticketId, POST `/events/{event_id}/attendees/{ticket_id}/cancel` | `{ticketId,status:"cancelled"}`, used→409; re-read связанных stats/page/ticket |

Attendee response: ticketId/ticketCode/fullName/email/status(present/expected/cancelled)/checkedInAt/answers. Нет registeredAt/checkerId/registrationId: текущие обязательные поля изменить на optional/убрать, не вставлять now/actor/ticketId под видом registrationId. Normal list может исключать cancelled; total относится к search и этому фильтру. Полный XLSX все статусы получает сервер независимо от query/page.

Revision — opaque consistency token **только чтения dashboard**. Смена query/E сбрасывает cursor; STALE_PAGE/409 → первая страница+stats, старые rows не append. Не передавать token как expectedRevision в PATCH editor. Перед read последующих страниц передавать revision первой.

## ProfileRepository / DocumentsRepository — read profile M01, mutations M08; PDF M03

Источники: [ProfileModels](../../core/domain/src/main/kotlin/ru/eventpass/core/domain/profile/ProfileModels.kt), [DocumentModels](../../core/domain/src/main/kotlin/ru/eventpass/core/domain/documents/DocumentModels.kt). Все 3 + 3 метода учтены.

| Метод | Решение / API | Input/output |
|---|---|---|
| observeProfile | CHANGE M01/GET `/me` | Единая User/Account проекция с session refresh |
| refreshProfile | KEEP M01/GET `/me` | Проверенная session generation, недоступный сервер не выдумывает пустой профиль |
| saveProfile | CHANGE/PATCH `/me` | partial `{fullName,email,phone,organization,notificationsEnabled}` → User; ProfileFields добавить email; неотправленное≠null |
| prepare | CHANGE | TicketPdf → GET `/tickets/{ticket_id}/pdf[?token=...]`; AttendanceXlsx → GET `/events/{event_id}/attendees/export?format=xlsx`; download descriptor/private temp file вместо локального snapshot всех rows |
| validate | CHANGE, локальный generation/access guard | Не отдельная HTTP-ручка. Backend проверяет доступ при скачивании; local guard до/во время SAF; revoked stale descriptor не основание выдавать cached файл |
| resendTicket | REMOVE | Серверной операции нет, feature исключена пользователем. Удалить product UI/порт; не возвращать фальшивый Queued |

ADD M08: delete account `DELETE /me` → 204. Без password/preview. Перед командой local confirmation объясняет каскад мероприятий/чужих tickets; network timeout = unknown outcome, не success. Смена email сбрасывает emailVerified, но не очищает tickets по старому локальному email; backend привязка userId. User поля id/publicId/fullName/email/phone/organization/emailVerified/notificationsEnabled должны сохраняться без потери.

## Локальные порты, которые не превращаются в HTTP-ручки

| Порт / метод | Решение / этап | Граница |
|---|---|---|
| DocumentFiles.save | CHANGE M03/M08 | SAF/ContentResolver остаётся platform, принимает новый document descriptor/stream без зависимости VM от HTTP. Проверять generation в процессе записи; cancel/ошибка удаляет временный файл |
| DocumentFiles.cleanup | KEEP M03/M08 | Bounded private cache cleanup, logout/account change; не удалять пользовательский SAF-файл без отдельного действия |
| CoverImporter.importCover | CHANGE M04/M05 | Platform source→валидация/свой temporary asset; upload в data:remote. В offline state local cover ≠ server EventCover. Добавить upload orchestration, не HTTP в composable |
| DemoRepository.observeRecord | KEEP demo-only | Technical spike/tools, без remote аналога; не включать в remote DI/APK |

Итого учтено **48 текущих объявлений методов** в 12 портах (включая local/demo). ADD — consent configuration, cover upload/read, deletion, draft local storage и read refresh/recovery при необходимости; добавлять только при реализуемом срезе. Не строить future repositories в M00.

## Общие ошибки, nullable поля и retry

Envelope `{code,message,fields}`. UI использует локальные resources и typed outcomes, не parser message. 401 → session invalid только для текущей generation; 403 → forbidden resource; 404 → notfound; 422 VALIDATION/CONSENT_REQUIRED и `fields` → field issues. 409 EVENT_FINISHED/EVENT_FULL/ALREADY_REGISTERED/EMAIL_TAKEN/TICKET_USED/STALE_PAGE/IDEMPOTENCY_CONFLICT различаются. 429 RATE_LIMITED + Retry-After секунды, 413/422 INVALID_IMAGE, 503 STORAGE_UNAVAILABLE. Network/timeout/cancel и malformed JSON/unknown business code — различимые transport/protocol outcomes, не выдуманный успешный результат. Не всякая 409 является editor conflict.

SignUp/Registration consentAccepted обязательно. Phone поля User nullable; GuestContacts.phone обязательная строка, пустое значение разрешено. Session не содержит expiresAt/refresh token. Draft поля nullable, published EventSummary валиднее draft. Scan fields optional. GET consent null URL не является завершённым legal UI. Fields issue может быть общий INVALID_ANSWER, собственная локальная валидация не меняет server contract.

Idempotency-Key create/register/scan: 16–128 допустимых символов, outcome 24h, повтор с тем же immutable body; changed body→409. HTTP mutation auto-retry выключен; cancellation/timeout не rollback. При replay scan не повторять success sound/новый допуск. После process death без сохранённого body нельзя обещать replay; ADR-T10-05. PATCH не имеет write precondition: GET-сравнение не даёт строгую защиту lost update; conditional-write — отдельная backend-зависимость, не текущая операция.

## Fixtures и достаточные доказательства

| Набор | Назначение / этап | Необходимые варианты |
|---|---|---|
| Auth | M01 | user verified/unverified/passwordless, Session без expiresAt, 204 пустое тело, invalid credentials, 401 старой generation, 429/Retry-After, network/key invalidation |
| PublicEvent | M02 | draft nullable (owner read), published/live/finished, positive capacity/full, slug≠id, all 10 question types + неизвестный type, bool/string/list ответы, min2 options |
| Registration/Tickets | M03 | accountCreated true с accessToken без session, auth false/null capability, EMAIL_TAKEN/ALREADY_REGISTERED без ticketId, active/used/cancelled, same-key replay, closed flow/process death |
| Editor/Covers | M04/M05 | incomplete/partial/null PATCH, finish idempotent, real PNG/JPEG + bad/large, foreign asset, pending local cover, Room old schema/account partition/conflict, local→remote create lost response |
| Scanner/Volunteer | M06 | allowed sparse/with scanId, replayed, already_used без checkerId, invalid без ticket data, revoked/finished, EVT manual, counts after revoke/regrant, concurrent first check-in |
| Dashboard | M07 | stats/page matching+mismatch revisions, stale page 409, empty filtered page, search replacement, absent registeredAt/checkerId, cancel used/foreign/race |
| Profile/Documents/Delete | M08 | email change, nullable phone, server PDF/XLSX bytes/MIME/error, revoked/logout during SAF, delete 204/unknown outcome and complete local cleanup |

Локальные sanitized JSON fixtures создавать по этапам из этой схемы/синтетических данных, без bearer/guest tokens/реальных анкет в Git. Наличие fixture не означает выполненный contract test. Mock HTTP + JVM/domain/fake + адресные device tests; staging только выделенные синтетические accounts/events. User manual test только после M09. Доступ к Mailpit/terms/privacy нужен для полного auth/legal smoke, не M00. Тестовые seed имена organizer/volunteer/participant сами по себе не дают credentials.

## Контекст ревью

На M00 source app/domain/fake/platform read-only, тесты старого demo не «исправляются» документацией. Устаревший пункт no-autoaccount в `.agents/skills/android-code-review/SKILL.md` не применяется к target T10: прямое решение пользователя/AGENTS/08/34 выше по приоритету. Сам защищённый skill не изменён. Каждое следующее ревью получает точный этап/base/diff и проверяет фактически изменённые contracts/потребителей/миграции, а не требует будущие M заранее. Текущий demo и история прежних проверок остаются отдельно от target mapping.
