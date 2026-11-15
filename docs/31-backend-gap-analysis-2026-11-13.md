# Сравнение backend EgorDikanskiy/eventpass с Android MVP

**После этого обследования пользователь выбрал итоговые варианты.** Текущий список необходимых доработок — [32](32-backend-final-requirements.md). Не все прежние расхождения ниже требуют исправления: autoaccount/unverified login/reset-link/live/positive capacity/editable form/bearer/manual/deletion приняты как серверные решения. Этот файл остаётся доказательством исходного сравнения снимка 94af06f.

Дата: 13 ноября 2026. Исследован `main` на **`94af06fb82e432308e4425f01447018d8641ca72`**: [зафиксированный снимок](https://github.com/EgorDikanskiy/eventpass/tree/94af06fb82e432308e4425f01447018d8641ca72). Это статическое обследование совместимости с нашими принятыми сценариями и [предложением API v0.1.0](22-backend-api-contract.md), а не полное security/code review или проверка работающего staging.

## Вывод

Backend уже содержит реальную основу: FastAPI, PostgreSQL, password hashes и opaque sessions, event-scoped organizer/volunteer, транзакционные регистрации/check-in, очередь исходящих jobs, RabbitMQ worker, SMTP/Mailpit, PDF и private S3. Это больше, чем HTTP-заглушки, и годится для начала совместной интеграции.

**Полностью подключить текущий Android без изменения поведения пока нельзя.** Сервер воспроизводит ряд ранних правил Figma, которые пользователь позже изменил: автосоздание аккаунта гостю, разрешённый вход до подтверждения email, reset по ссылке, регистрация во время события и редактирование вопросов после регистрации. Есть и отсутствующие MVP-операции. Это предметные расхождения, не просто разные имена JSON.

Наши URL/DTO/access-refresh TTL/DocumentJob/cursor — предложения, а не обязательная серверная реализация. Их можно пересогласовать и адаптировать в `data:remote`. Подтверждённые B01–B08/Q-правила нельзя менять молча ради имеющегося backend.

## Что уже совпадает и может переиспользоваться

- Один users account без глобальной роли; organizer_id, volunteers и tickets отдельные связи с E. Owner и volunteer используют один scanner; owner не нуждается в self-grant. Участие не даёт сканирование.
- Публичная страница только опубликованного E; личные билеты, свой профиль, свои события/черновики. Закрытые owner-операции и scanner проверяют пользователя и eventId на сервере.
- QR — случайный token_urlsafe(32), без профиля внутри; отдельный guest token для доступа, а session bearer хранится хешем. Пароли через pwdlib с Argon2-зависимостью.
- При регистрации блокируется строка event, проверяется capacity и уникальность неотменённого ticket; scan блокирует event/ticket и меняет ACTIVE→USED с серверным временем/checker. Отменённый билет не проходит, Used не отменяется. Повтор check-in возвращает already_used. Это полезная реализация серверной согласованности, не доказательство нагрузочной проверки.
- Общие totals считают неотменённые/Used, поиск списка их не меняет. Owner-only экспорт всех tickets включает Cancelled и ответы, защищает CSV cells от формул. Есть реальный PDF, с проверкой доступа и S3-кэшем по fingerprint актуальных данных.
- Регистрация и email job фиксируются в общем request transaction; отправка отдельным worker с повтором. Есть интеграционные тесты capacity/isolation/concurrent scan, profile/drafts, писем и анкеты. Их наличие не означает, что они запускались в этом обследовании.

Основные источники: [database.py](https://github.com/EgorDikanskiy/eventpass/blob/94af06fb82e432308e4425f01447018d8641ca72/backend/database.py), [tickets.py](https://github.com/EgorDikanskiy/eventpass/blob/94af06fb82e432308e4425f01447018d8641ca72/backend/tickets.py), [scanner.py](https://github.com/EgorDikanskiy/eventpass/blob/94af06fb82e432308e4425f01447018d8641ca72/backend/scanner.py), [organizer.py](https://github.com/EgorDikanskiy/eventpass/blob/94af06fb82e432308e4425f01447018d8641ca72/backend/organizer.py).

## A. Расхождения с уже принятыми сценариями

| ID | Согласовано у нас | Реализация backend и следствие | Что нужно |
|---|---|---|---|
| A01 | Гостевой submit не создаёт аккаунт | `register_for_event` вызывает `create_user`, ticket.user_id NOT NULL, возвращает accountCreated=true; письмо ведёт в setup password | Отдельная guest registration identity по email, nullable/эквивалентная связь с аккаунтом; аккаунт только через signup. После verified login сервер связывает прежние guest tickets |
| A02 | Подтверждение email, затем ручной login | Signup сразу возвращает Session; login/get_viewer/require_user не запрещают email_verified=false. Можно входить, создавать E, читать tickets без подтверждения | Signup возвращает challenge без session; login после проверки пароля возвращает отдельный EMAIL_UNVERIFIED. Закрытые операции проверяют verified account |
| A03 | Reset: email → код → новый пароль; без автоматического verify | `/forgot-password` шлёт ссылку на 24 часа; `/email-link` для reset/setup выставляет email_verified=true. Нет code/verify/resetGrant | Добавить кодовый flow; отделить смену пароля от подтверждения аккаунта. Ручной вход после confirm как действие `/email-link` уже возможен, но signup/login обходят этот барьер |
| A04 | Перед входом анкета не сохраняется и не submit-ится автоматически | Сервер выдаёт EMAIL_TAKEN гостю на существующий account. Web RegistrationCard/LoginPage сохраняют pendingRegistration и повторяют submit после login | Android не копирует web pending-flow. Guest+existing-account ветку согласовать отдельно: текущий demo тоже RequiresLogin, новое API-предложение допускает гостевой билет на известную почту. Обязательное правило B04 — никаких сохранённых ответов/автоотправки |
| A05 | Phone optional, если заполнен — российский формат; имя Unicode с базовой валидацией | GuestContacts.phone — обязательное поле строки (пустая строка допустима), null не принимается; профиль разрешает произвольную строку до 50. Имя max 300, ISO controls явно не исключены | Guest phone optional/null, централизованная нормализация/валидация; сохранить широкий Unicode без требования двух слов |
| A06 | Регистрация закрыта при startsAt; завершение вручную или по endsAt, null означает без автоокончания | Status live после начала, finished через **4 часа**; submit запрещает только draft/finished. Поэтому во время live продолжает выдавать билеты | endsAt и отдельное ручное завершение; запрет новой регистрации с startsAt; убрать жёсткие +4 часа |
| A07 | Owner может отдельно закрыть регистрацию, не пряча публичную страницу | Нет registrationOpen/close-ручки, доступны только publish/edit | Добавить закрытие регистрации и доступность с причиной; не подменять его отменой/завершением |
| A08 | Безлимит capacity=null; remaining=0 запрещает новые заявки | DB capacity NOT NULL >0, DTO 1..1 000 000, unlimited нет | Поддержать null/unlimited. Нулевой **остаток** и нулевой **общий лимит** не путать |
| A09 | После первой регистрации questions/options/order/required фиксируются навсегда | PATCH сохраняет новые questions без проверки first registration; тест прямо удаляет вопрос после выдачи билета и ожидает успех | Блокировка с первого submit, включая guest; отмена всех не снимает lock. Хранение answer_labels полезно, но не заменяет принятое правило |
| A10 | Неполный draft, полная валидация только при publish | CreateEventRequest одинаково требует title/startsAt/location/capacity/questions для draft и publish | Разделить неполные draft fields и publication validation; не заставлять Android подставлять фиктивные даты/названия |
| A11 | Published E имеет правила редактирования: Started dates locked, Finished terminal | PATCH не проверяет lifecycle, может менять дату/анкету даже у finished; изменение startsAt меняет вычисляемый статус обратно | Проверять lifecycle/editable fields, не оживлять завершённое событие правкой даты |
| A12 | Типы TEXT/CHECKBOX(boolean)/SINGLE_CHOICE | Backend имеет text/textarea/email/tel/number/date/choice/select/multiple; **boolean checkbox нет**. Multiple — массив строк; choices требуют минимум 2 options против нашего минимум 1 | Зафиксировать общий минимальный набор. TEXT/choice легко маппятся; CHECKBOX нельзя молча заменить multiple. Дополнительные server types не требовать от Android без расширения UI/валидации |
| A13 | Owner может отменить чужого Active участника своего E | `/tickets/{id}/cancel` разрешает только ticket.user_id==actor; organizer cancel отсутствует | Отдельный owner guard/команда. Не выдавать владельцу чужую session и не отключать проверку ownership |
| A14 | USED в истории, история без QR; Cancelled сохраняется | `list_tickets` держит USED в upcoming до finished; cancelled ticket скрывается, если на E есть другой неотменённый. Ticket DTO всегда содержит qrToken, PDF допускает USED с QR | Согласовать current/history; Android может скрыть QR, но серверная выдача/статусы также должны быть определены. Не терять историю отмен после re-registration |
| A15 | Профиль email read-only в мобильном MVP; история заявок не меняется от редактирования профиля | PATCH `/me` разрешает смену email, лишь сбрасывает verified, не отзывает session. В регистрации holderEmail прежний, notification worker использует **текущий** users.email | Android не показывает смену email; серверный протокол изменения отдельно согласовать/защитить. Уточнить получателя писем и связь с неизменяемым registration snapshot |
| A16 | Выключение preferences не отменяет обязательную выдачу билета | `resolve_ticket_notification` проверяет notifications_enabled и для registered: при false письмо с новым билетом не придёт. Это закреплено тестом | Разделить обязательные ticket/security сообщения и optional reminders. Не менять Android-текст «билет отправлен», если отправка не создаётся |

Источники A01/A14: [tickets.py](https://github.com/EgorDikanskiy/eventpass/blob/94af06fb82e432308e4425f01447018d8641ca72/backend/tickets.py). A02/A03/A15: [auth.py](https://github.com/EgorDikanskiy/eventpass/blob/94af06fb82e432308e4425f01447018d8641ca72/backend/auth.py). A05/A08/A10/A12: [schemas.py](https://github.com/EgorDikanskiy/eventpass/blob/94af06fb82e432308e4425f01447018d8641ca72/backend/schemas.py). A06/A07/A09/A11: [events.py](https://github.com/EgorDikanskiy/eventpass/blob/94af06fb82e432308e4425f01447018d8641ca72/backend/events.py), [schema.sql](https://github.com/EgorDikanskiy/eventpass/blob/94af06fb82e432308e4425f01447018d8641ca72/backend/schema.sql). A04: [RegistrationCard](https://github.com/EgorDikanskiy/eventpass/blob/94af06fb82e432308e4425f01447018d8641ca72/src/pages/event/RegistrationCard.tsx), [LoginPage](https://github.com/EgorDikanskiy/eventpass/blob/94af06fb82e432308e4425f01447018d8641ca72/src/pages/auth/LoginPage.tsx). A16: [jobs.py](https://github.com/EgorDikanskiy/eventpass/blob/94af06fb82e432308e4425f01447018d8641ca72/backend/jobs.py), [API tests](https://github.com/EgorDikanskiy/eventpass/blob/94af06fb82e432308e4425f01447018d8641ca72/tests/api.spec.ts).

## B. Auth/session: точное сравнение

| | Наше предложение | Backend |
|---|---|---|
| Login response | tokenType/accessToken/accessExpiresAt/refreshToken/refreshExpiresAt/sessionId/account/serverTime | `{token,user}` |
| Токен | Opaque access на 15 минут + rotating refresh idle 30 дней / absolute 90 | Один случайный bearer на **30 дней** от выдачи, expiry только в БД |
| Проверка | Digest access + активная session/account/verified + event rights | SHA-256 bearer, JOIN sessions/users, expires_at>now(); verified не требуется |
| Продление | Отдельный refresh/reuse detection/single-flight | Refresh endpoint/rotation/expiry metadata отсутствуют; после истечения нужен login |
| Logout | Отзыв семьи через refresh, включая access | Удаляет строку текущего bearer; серверный отзыв есть |
| Password reset | Code→grant, после смены все session revoked, без auto-verify | Link→password; все sessions и links удаляются, email одновременно подтверждается |
| Anti-abuse | Rate limits, retryAt/Retry-After, code attempts | Нашёл только тихий cooldown 1 минута для повторной ссылки; общих login/registration/scan rate limits в коде/proxy нет |

Один opaque bearer **не является автоматически дефектом**: это альтернативное решение с более долгим сроком и повторным login. Но Android нельзя выдать за совместимый без переделки production SessionCoordinator/storage/expiry policy. Если оставляем один токен — нужны явный expiresAt и согласованное восстановление сессии; если принимаем наше предложение — сервер добавляет refresh. Блокировка входа неподтверждённым — отдельное уже принятое требование, независимо от выбора JWT/opaque/refresh.

## C. Недостающие операции и данные

1. **Обложки.** Нет upload API, assets table, привязки asset к E. В EventDetails есть coverUrl/sections defaults, но EventInput и DB их не сохраняют. Существующее S3 используется для PDF и само не даёт поддержку cover. Нет endsAt/zoneId на E; PDF/письма берут общий EVENTPASS_TIMEZONE.
2. **Повтор ticket email.** Нет отдельной команды resend текущего билета и email-only recovery. `/auth/resend-verification` — подтверждение/guest setup, а не повтор билета для любого участника. Worker шлёт plain-text ссылку; PDF вложением/QR в самом письме сейчас не создаётся.
3. **XLSX.** Экспорт фактически CSV (`text/csv`, attendees.csv), хотя docs допускают «CSV или XLSX». Настоящего XLSX нет. В CSV нет phone/registration timestamp/checker/formVersion. Android уже делает XLSX в demo; переименование CSV в XLSX недопустимо. Можно согласовать CSV как продуктовую замену или дописать серверный XLSX.
4. **Revision/formVersion.** Нет expectedRevision, formVersion, stale form, questionsLocked и conflict. Последний PATCH затирает предыдущий; timestamp регистрации/answer_labels сохраняются, но общей версии формы нет. Клиентская защита от позднего HTTP не защищает от правок второго устройства.
5. **Идемпотентность.** Нет Idempotency-Key/outcome storage. Повтор регистрации даёт ALREADY_REGISTERED/EMAIL_TAKEN, повтор scan — already_used, повтор create — новый E. Уникальность/транзакция не возвращают исходный потерянный результат. Особенно важно согласовать retry после timeout для guest/create/check-in.
6. **Dashboard consistency/freshness.** Stats и attendees разные чтения без общей revision/serverTime/updatedAt; page+offset сортируется по checkedInAt и сдвигается от check-in, возможны повторы/пропуски страниц при параллельной работе. Нет status filter/Cancelled в списке (SQL их исключает), хотя export их включает. Нет checkedInCount в каждой строке volunteers; `/scanner` имеет только checkedByMe для текущего actor. Polling можно реализовать в Android, но нельзя восстановить отсутствующую общую revision адаптером.
7. **Capabilities.** Есть viewer.isOrganizer/isVolunteer/ticketId, этого достаточно для части mapping. Нет canRegister/reason/remaining/editableFields; часть можно вычислить из согласованных полей, но сейчас нет необходимых registrationOpen/endsAt и других причин.
8. **Ошибки/конфигурация.** Нет `/config`, versions/URL соглашений, consent и server acceptedAt в командах signup/registration; согласие сейчас web checkbox. Ошибки `{code,message,fields:string}` вместо машинных field codes/params; несколько разных бизнес-ошибок объединены в VALIDATION/русский message. Нет requestId/retryAt. Не заставлять Android разбирать русские строки; общую оболочку можно адаптировать, различающие codes нужно добавить.
9. **App Links.** Ссылки web `/account-link#token=...&action=...`, guest `/e/{slug}/ticket/{id}?token=...`; нет поставляемого assetlinks.json. Нужен принадлежащий команде production host, сертификат Android и согласованные пути/fallback. Nginx отдаёт SPA по произвольному path, это не настройка verified App Links.

Источники: [schemas](https://github.com/EgorDikanskiy/eventpass/blob/94af06fb82e432308e4425f01447018d8641ca72/backend/schemas.py), [organizer](https://github.com/EgorDikanskiy/eventpass/blob/94af06fb82e432308e4425f01447018d8641ca72/backend/organizer.py), [jobs](https://github.com/EgorDikanskiy/eventpass/blob/94af06fb82e432308e4425f01447018d8641ca72/backend/jobs.py), [worker](https://github.com/EgorDikanskiy/eventpass/blob/94af06fb82e432308e4425f01447018d8641ca72/backend/worker.py), [main](https://github.com/EgorDikanskiy/eventpass/blob/94af06fb82e432308e4425f01447018d8641ca72/backend/main.py), [nginx](https://github.com/EgorDikanskiy/eventpass/blob/94af06fb82e432308e4425f01447018d8641ca72/deploy/nginx.conf).

## D. Решения, которые нужно согласовать, а не объявить отсутствующими функциями

- **Ручной credential:** сервер принимает публичный `EVT-` + 6 цифр и QR одной строкой `/scan {code}`. Мы предложили отдельный случайный 12-char ManualCode; нынешний Android demo выводит отдельный код из QR. Это разные протоколы. Typed QR/manual или одна строка — не принципиально; формат и защита ручного ввода нужны общие. Не вычислять Android code для настоящего ticket. Ограничений перебора scan пока нет.
- **Guest token:** сервер выдаёт отдельный bearer capability, хранит raw guest_token в ticket без expiresAt и позволяет читать ticket/PDF по query. Это защищённый token lookup, **не** публичный поиск по одному ticketId. Наше принятое Android-правило запрещает постоянный guest cabinet/cache, но не хранение server ticket; capability может существовать для письма. Guest PDF и срок capability отдельно не утверждены: не вводить ни автоматический постоянный cache, ни требование убрать защищённую ссылку письма без согласования.
- **PDF:** сервер выдаёт PDF синхронно с закрытого S3/генерацией, не DocumentJob. Для MVP можно принять direct download, сохранив Android port. Отличаются разрешённые статусы: PDF доступен Used/Cancelled/finished (Used содержит QR), мы планировали private export только текущего Active. Само отсутствие job endpoint не означает отсутствие PDF.
- **Удаление Q10:** сервер уже имеет DELETE `/me` без password/re-auth/preview. FK каскадно удаляют собственные E и чужие tickets на них, свои tickets/grants/scans; у чужих tickets checkerId становится null, но scans удаляемого checker исчезают. Нет уведомления затронутым участникам/сохранения истории E. **У нас каскад не согласован, функция отключена.** Нельзя считать реализацию сервера ответом пользователя; password confirmation — уже согласованный минимум, retention/аудит/чужие билеты надо обсудить.
- **Дополнительные функции:** paid priceType, category из первого choice, больше типов анкеты, recent scan log и редактирование email есть на server/web. Это не основание добавлять MAX/payment/глобальные роли или все новые поля в Android. Наличие priceType=paid не доказывает реализованные платежи.
- **Пересечение связей:** нет глобального User.role, owner+participant возможно. Но публичный `get_event_role` выбирает owner раньше volunteer; `/me/events` группирует owner через if/elif, поэтому даже при имеющейся двойной связи E не появится в обоих разделах. Grant самому owner отклоняется. Это допустимо лишь после согласования ограничения, поскольку наш demo допускает независимые связи внутри E.
- **Scan завершённого E:** сервер возвращает 403 до lookup, в том числе для Used. У нас Used сохраняет AlreadyUsed/first record даже после завершения; Active не допускается. Нужно определить, должен ли scanner оставаться доступен для проверки истории, и дать бизнес-исход EVENT_INACTIVE вместо смешивания с revoke.

## E. Покрытие всех 44 операций нашего предложения

Все target пути ниже относительно `/api/v1`; backend пути — относительно `/api`. «Частично» означает наличие похожей операции с описанными выше отличиями, **не** соответствие готовому контракту. Сравнивать количество ручек как процент готовности нельзя: одна backend-ручка может заменять несколько наших, а одна похожая ручка может нарушать ключевое правило.

| Target операция | Что есть на backend | Оценка |
|---|---|---|
| GET /config | — | Нет config/legal policy |
| POST /auth/sign-up | POST /auth/register | Частично: сразу Session |
| POST /auth/email-verification/requests | POST /auth/resend-verification | Частично: нет challenge metadata |
| POST /auth/email-verification/confirm | POST /auth/email-link | Частично: общий verify/setup/reset |
| POST /auth/sign-in | POST /auth/login | Частично: unverified допускается |
| POST /auth/refresh | — | Нет |
| POST /auth/sign-out | POST /auth/logout | Есть отзыв текущего bearer, другой request |
| POST /auth/password-reset/requests | POST /auth/forgot-password | По ссылке, не коду |
| POST /auth/password-reset/verify | — | Нет code→grant |
| POST /auth/password-reset/complete | POST /auth/email-link | Другая семантика reset/verify |
| GET /me | GET /me | Есть; другие имена/лимиты/нет revision |
| PUT /me | PATCH /me | Есть partial profile, допускает email |
| POST /me/deletion-preview | — | Нет |
| POST /me/deletion | DELETE /me | Есть несогласованный каскад без password |
| GET /events/{eventId} | GET /events/{slug} | Требуется slug↔id mapping; owner-only by-id отдельно |
| POST /events/{eventId}/registrations/guest | POST /events/{id}/registrations + guest | Автоаккаунт, отличается принципиально |
| POST /events/{eventId}/ticket-email-requests | — | Нет ticket recovery/resend |
| POST /events/{eventId}/registrations | POST /events/{id}/registrations | Есть, другие форма/время/duplicate |
| GET /me/tickets | GET /me/tickets?scope= | Есть, другой current/history; без pages |
| GET /me/tickets/{ticketId} | GET /tickets/{id} | Есть own или guest-token access |
| POST /me/tickets/{ticketId}/cancellation | POST /tickets/{id}/cancel | Есть self Active, Used запрещён |
| POST /me/tickets/{ticketId}/email-requests | — | Нет ticket resend |
| POST /me/tickets/{ticketId}/pdf-jobs | GET /tickets/{id}/pdf | PDF есть, синхронный и иные статусы |
| GET /me/events | GET /me/events?tab= | Есть groups, нет cursor/period |
| GET /me/event-drafts | GET /me/events?tab=drafts | Draft list есть |
| GET /event-drafts/{draftId} | GET /events/by-id/{id} | Owner read draft есть |
| PUT /event-drafts/{draftId} | POST /events + PATCH /events/{id} | Draft требует полную форму; нет client ID/revision |
| POST /event-drafts/{draftId}/publication | POST /events/{id}/publish | Есть публикация существующего E |
| GET /events/{eventId}/editor | GET /events/by-id/{id} | Есть, нет editableFields |
| PUT /events/{eventId}/editor | PATCH /events/{id} | Есть, нет freeze/version/lifecycle guards |
| POST /events/{eventId}/registration-closure | — | Нет |
| POST /events/{eventId}/completion | — | Нет; fixed startsAt+4h |
| POST /assets/event-covers | — | Нет |
| GET /events/{eventId}/volunteers | GET /events/{id}/volunteers | Есть; нет per-volunteer check count |
| POST /events/{eventId}/volunteers | POST /events/{id}/volunteers | Есть email/publicId, duplicate=422 |
| DELETE /events/{eventId}/volunteers/{userId} | DELETE /events/{id}/volunteers/{userId} | Есть |
| GET /events/{eventId}/scan-access | GET /events/{id}/scanner | Есть расширенный read, иные lifecycle outcomes |
| POST /events/{eventId}/check-ins | POST /events/{id}/scan | Есть QR/display code; нет replay/manualCode protocol |
| GET /events/{eventId}/dashboard | GET /events/{id}/stats | Totals есть, revision/freshness нет |
| GET /events/{eventId}/attendees | GET /events/{id}/attendees | Search/page есть, Cancelled исключены |
| POST /events/{eventId}/attendees/{registrationId}/cancellation | — | Owner cancellation нет |
| POST /events/{eventId}/exports | GET /events/{id}/attendees/export | Все tickets/answers в CSV, не XLSX |
| GET /document-jobs/{jobId} | — | Нет публичного document job API |
| GET /document-jobs/{jobId}/content | PDF/export endpoints | Прямые binary downloads вместо job |

## F. Что менять в Android, а что просить от backend

**Android adapters:** base `/api`, HTTP методы/названия, fullName↔name/location↔venue, lowercase enums, publicId vs internal id, slug vs eventId, wrapper `{event,viewer}`, partial PATCH, прямой PDF download, polling и page protocol — после согласования могут жить за существующими repository ports. Это обычная работа T10; VM не должны читать web DTO.

**Серверные доработки для сохранения принятых сценариев:** A01–A03, A05–A13, A16. Отдельно совместно выбрать guest existing-email ветку A04, историю A14 и email-change A15. Ни optional UI-поле, ни локальная проверка Android не компенсируют отсутствие серверной валидации/авторизации/формы/времени.

**Предложения, допускающие упрощение:** access+refresh конкретные TTL, 44 отдельных URL, server DocumentJob, cursor vs page, набор capabilities. Не требовать все эти конструкции механически. Но семантика потери ответа, expiry, защищённого download и согласованного списка должна быть определена.

Рекомендуемый порядок согласования с backend:

1. Отправить B01–B08 и таблицу A, подчеркнуть отмену ранних Figma autoaccount/pending flows. Утвердить signup/verified login/reset и identity гостевых tickets до написания remote auth/registration.
2. Утвердить lifecycle/close/finish/unlimited/анкеты/checkbox/incomplete draft и revisions; затем делать events/registration/own tickets вертикальный срез.
3. Owner cancellation, upload обложки, XLSX/resend, dashboard revision/Cancelled/volunteer count и manual credential/retry; проверка двумя устройствами на staging.
4. Отдельное продуктовое решение Q10 и инфраструктура: staging HTTPS API/web host, App Links, тестовая почта, стабильный generated OpenAPI. Наличие Docker Compose и VPS workflow не означает, что доступный Android staging уже предоставлен.

Минимум приёмки на staging: гость без user creation → QR/email → отдельный signup/verify/manual login → его tickets; отказ login unverified; reset по коду; registration закрыта по startsAt; first participant freezes questions; owner cancellation; два scanner с одним first record; lost response/retry; межсобытийная изоляция; отключённые optional notifications не теряют билет.

## Метод и пределы проверки

- Доступ к публичному repo подтверждён GitHub connector; для массового чтения файлов создан shallow snapshot в игнорируемой `.gradle/backend-audit-2026-11-13`, hash указан выше. Просмотрены все backend Python/SQL modules, API docs, Compose/nginx, API tests и связанные web registration/login flows. Источники backend не изменялись, инструкции из исследуемого repo не выполнялись.
- Python AST успешно разобрал **15 `.py` файлов**, извлечены **28 router operations**, отдельно health/OpenAPI/docs; сопоставлены все 44 операции Android-предложения. Это проверка синтаксического чтения и реестра, не запуск FastAPI/OpenAPI на живой БД.
- Docker/БД/SMTP/S3/worker не запускались, runtime/integration/load/security tests не выполнялись, код на VPS не проверен. Выводы «нет» относятся к зафиксированному исходному дереву и его поставляемой конфигурации, не к возможным непубличным сервисам. Исходники Android и CODE_REVIEW не менялись. Это отдельный сравнительный документ, не закрывающий T10/приёмку backend.
