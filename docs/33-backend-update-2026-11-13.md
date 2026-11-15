# Проверка обновления backend для Android

13 ноября 2026, проверка staging около 11:31 MSK. Требования — [32](32-backend-final-requirements.md) и итоговые решения пользователя 13 ноября. Это проверка достаточности API и адресных сценариев, не полное ревью безопасности backend и не приёмка Android.

## Вывод

**Для начала T10 ручек достаточно.** Все функциональные группы BK01–BK09 из итогового задания представлены в коде; основные сценарии подтверждены на доступном staging. Не нужно ждать ещё одного большого этапа разработки API перед первым remote-срезом Android.

Есть незавершённая настройка документов согласия и нужен доступ к тестовой почте для сквозной проверки setup/reset/verify. Особенность `guest.phone` адаптируется без обязательного телефона в UI. **Android App Links отложены пользователем и не блокируют текущую интеграцию.** Этот отчёт не отмечает T09/T09a/T10 или новые H-проверки выполненными.

## Какие изменения изучены

- Исходная точка сравнения: `94af06fb82e432308e4425f01447018d8641ca72`, 12 ноября 23:18 MSK; прежний отчёт — [31](31-backend-gap-analysis-2026-11-13.md).
- [69602094f72743fcc5175d8ec9395b69402ca50e](https://github.com/EgorDikanskiy/eventpass/commit/69602094f72743fcc5175d8ec9395b69402ca50e), 13 ноября 11:08:33 MSK: EventPass 1.1, функциональные дополнения, миграция и staging release.
- [def65653b9e1f22dce8bc2d6f266b23ace6cd1d5](https://github.com/EgorDikanskiy/eventpass/commit/def65653b9e1f22dce8bc2d6f266b23ace6cd1d5), 13 ноября 11:09:41 MSK: web-переход после подтверждения почты с сохранённой сессией. Последний коммит меняет web UI; основные backend-дополнения — в предыдущем.
- Прочитаны маршруты, модели, сервисы, миграция, storage/worker, новые API-тесты и staging-конфигурация. Локальная копия исходников — служебный снимок в `.gradle/`, Android-код не изменялся.

## Покрытие итогового задания

| Требование | Что появилось | Свидетельство и предел |
|---|---|---|
| BK01: окончание | `endsAt`, `effectiveEndsAt`, `finishedAt`, owner `POST /events/{id}/finish`; null означает старт + 4 часа | На staging проверены default, явный конец, повтор finish, запрет новых регистраций/scan после finish, отсутствие оживления вручную завершённого E после PATCH. Наступление автоматического окончания по реальным часам отдельно не ожидалось; формула/сравнение прочитаны в коде |
| BK02: неполный draft | Nullable дата/capacity, пустые поля, незаконченная анкета, PATCH только переданных полей, проверка publish | Сохранение неполного draft, отказ publish, заполнение/публикация и повтор publish того же ID прошли; конкурентный replay create не создал второе E |
| BK03: отмена owner/self | `POST /events/{id}/attendees/{ticketId}/cancel` плюс существующая self cancel | Owner/чужие права, повтор cancel, USED/409, self cancel, новая регистрация новым ticket и конкурентные cancel/scan прошли |
| BK04: обложка | Multipart `/assets/event-covers`, JPEG/PNG 5 МиБ, metadata/assetId, owner attach, S3, очистка непривязанных | PNG upload/read, чужая привязка, приватность до публикации и публичность после неё прошли. Очистка через сутки, EXIF и сбой S3 независимо в live-прогоне не воспроизводились; соответствующий код прочитан |
| BK05: программа | `sections` в input/storage/details, порядок массива | Запись и возврат sections после публикации проверены |
| BK06: анкеты | Boolean `checkbox`, required true, editable вопросы, исторический `answer_labels` с type/label/value | required=false-ответ для обязательного checkbox отклонён; после регистрации поле изменено на text с другой подписью, XLSX сохранил исходную подпись и «Да». Optional false поддержан кодом, отдельно live не проверялся |
| BK07: XLSX | `GET /events/{id}/attendees/export?format=xlsx` | Файл разобран как workbook, проверены все 4 тестовые регистрации, включая отменённые; исходные ответы, ID/телефон/время регистрации/checker и лист metadata. Формульные строки независимо в этом прогоне не проверялись; text-cell запись есть в коде |
| BK08: согласованность | `revision` в stats/attendees, ticket-trigger, event SHARE-lock, STALE_PAGE/409; `checkedInCount` волонтёра | После отмены старая revision отклонена; счётчик первого allowed равен 1 и сохраняется после revoke/regrant. Длительная пагинация под нагрузкой не проверялась |
| BK09: согласия | `consentAccepted`, серверное время и версии; `GET /auth/consent` | Команды с true принимаются; хранение/проверка false прочитаны в коде. **На staging URL/версии документов пока null**, см. ниже |
| IN01: ошибки | Машинные коды новых веток, fields, бизнес-ошибки в OpenAPI | Live подтверждены TICKET_USED, EVENT_FINISHED, STALE_PAGE, IDEMPOTENCY_CONFLICT и VALIDATION |
| IN02: повтор команд | `Idempotency-Key`, body fingerprint, DB advisory lock, outcome на 24 часа | Конкурентный create, повтор регистрации, конфликт тела, scan replay с тем же scanId и `replayed:true`, новый scan → already_used, replay после revoke → 403 прошли. Права при replay проверяются |
| IN03: лимиты | DB-счётчики вне транзакции команды, лимиты auth/register/manual scan, 429/Retry-After | Код и добавленный API-тест прочитаны. Лимит на общем staging намеренно не исчерпывался; 429 независимо не воспроизводился |
| IN04: окружение | Работающий HTTPS staging, OpenAPI 1.1.1, seed-скрипт, Mailpit, новые API-тесты, `no-store` middleware | Публичные GET и основной API-прогон выполнены. App Links отложены. Доступ к почте и полный backend test suite в рамках задачи не проверены |

Основные исходники: [правила lifecycle](https://github.com/EgorDikanskiy/eventpass/blob/def65653b9e1f22dce8bc2d6f266b23ace6cd1d5/backend/services/event_rules.py), [маршруты событий](https://github.com/EgorDikanskiy/eventpass/blob/def65653b9e1f22dce8bc2d6f266b23ace6cd1d5/backend/api/events.py), [билеты/отмена](https://github.com/EgorDikanskiy/eventpass/blob/def65653b9e1f22dce8bc2d6f266b23ace6cd1d5/backend/api/tickets.py), [сканер](https://github.com/EgorDikanskiy/eventpass/blob/def65653b9e1f22dce8bc2d6f266b23ace6cd1d5/backend/api/scanner.py), [экспорт](https://github.com/EgorDikanskiy/eventpass/blob/def65653b9e1f22dce8bc2d6f266b23ace6cd1d5/backend/services/attendee_export.py), [дашборд](https://github.com/EgorDikanskiy/eventpass/blob/def65653b9e1f22dce8bc2d6f266b23ace6cd1d5/backend/api/organizer.py), [миграция](https://github.com/EgorDikanskiy/eventpass/blob/def65653b9e1f22dce8bc2d6f266b23ace6cd1d5/backend/infrastructure/migrations/001_eventpass_1_1.sql).

## Остаток перед полной интеграционной приёмкой

### Документы согласия не настроены на staging

Фактический `GET /api/auth/consent` возвращает:

```json
{"terms":{"url":null,"version":null},"privacy":{"url":null,"version":null}}
```

Поддержка есть: [consent.py](https://github.com/EgorDikanskiy/eventpass/blob/def65653b9e1f22dce8bc2d6f266b23ace6cd1d5/backend/services/consent.py), параметры `TERMS_URL`, `PRIVACY_URL`, при версионировании — `TERMS_VERSION`, `PRIVACY_VERSION`. Нужны реальные доступные документы и значения окружения. Это настройка/контент, новая ручка не требуется. До их появления нельзя считать переходы по документам готовыми.

### Телефон необязателен в UI, но JSON-поле обязательно

`GuestContacts.phone` — обязательная строка до 50 символов. Без поля сервер вернул `422 VALIDATION`, `fields.guest.phone=MISSING`; с `phone:""` гостевая регистрация успешно создала билет, пустое значение хранится как отсутствие телефона.

Принимаем текущий формат в Android mapper: необязательный телефон → пустая строка, не опускать поле и не посылать null. Это не требует от пользователя вводить телефон и не блокирует T10. Нормализация/проверка российского номера остаётся на Android согласно продуктовым правилам; сервер здесь допускает произвольную строку. [Схема](https://github.com/EgorDikanskiy/eventpass/blob/def65653b9e1f22dce8bc2d6f266b23ace6cd1d5/backend/models/schemas.py#L183).

### Почта и общее тестовое окружение

Адреса staging уже опубликованы в README backend. Есть [seed-staging.py](https://github.com/EgorDikanskiy/eventpass/blob/def65653b9e1f22dce8bc2d6f266b23ace6cd1d5/deploy/seed-staging.py): organizer/volunteer/participant, пароль из `STAGING_TEST_PASSWORD`. Сам файл не доказывает, что seed выполнен; рабочий общий пароль не предоставлен. Для этого прогона использованы отдельные временные аккаунты.

Чтобы закрыть весь auth flow, нужен доступ к Mailpit через согласованный SSH-туннель либо иной почтовый sandbox. Получение/открытие verify/setup/reset-писем, одноразовость и истечение 24 часов в live-прогоне не проверялись. По коду setup/reset меняют пароль, подтверждают email и удаляют старые сессии; verify сохраняет существующую сессию. После смены пароля нужен ручной вход, refresh нет. Сессия — opaque bearer на 30 дней, в БД хранится hash; каждый защищённый запрос сверяется с sessions и expires_at. Истечение возвращает 401, Android удаляет локальную сессию и предлагает вход.

### Документация backend частично устарела

В [docs/API.md](https://github.com/EgorDikanskiy/eventpass/blob/def65653b9e1f22dce8bc2d6f266b23ace6cd1d5/docs/API.md) новый раздел 1.1 описывает дополнения, но старые таблицы выше ещё не отражают consentAccepted, revision и другие поля. Для DTO и HTTP-ответов использовать актуальный generated OpenAPI и проверенный код, а не старые примеры. Это не отсутствие API.

## Что именно проверено на работающем сервере

- [Health](https://staging.eventpass.dikanskii.ru/api/health): 200, `{"status":"ok"}`.
- [OpenAPI](https://staging.eventpass.dikanskii.ru/api/openapi.json): 200, `info.version=1.1.1`. Версия ответа не доказывает точный Git SHA развёртывания; build SHA сервером не возвращён.
- [Consent](https://staging.eventpass.dikanskii.ru/api/auth/consent): 200, null документы, как выше.
- 55 адресных сценарных проверок прошли, затем 3 удаления собственных тестовых аккаунтов вернули 204. В прогоне также подтверждены вход без verify, приватность билета, гостевой capability/PDF, пересечение participant/volunteer на одном E, история после finish. Результаты — [JSON](validation/backend-2026-11-13-v1.1.1.json).
- Первый запуск локального проверочного скрипта остановился после успешных 38 проверок из-за попытки разобрать пустой 204 как JSON. Это ошибка проверочного скрипта; обработка исправлена, приведённый полный повтор прошёл 58/58. Никакой backend-дефект из этого не выводится.
- Аккаунты owner/member/checker и их мероприятия/билеты удалены. Два специально созданных passwordless guest-аккаунта с адресами `android.audit.908b49a7e0a1.guest@example.com` и `android.audit.e4660c747c52.guest@example.com` остались без билетов после owner cascade: удалить их через `/me` без setup-ссылки нельзя. Это синтетические данные staging; при доступе к sandbox их можно удалить после установки пароля либо при обычном сбросе тестовых данных. Письма тестов направлялись только на синтетические example.com адреса; доставка на реальные адреса не проверялась.

Полный backend/API/browser suite не запускался. Миграция существующей БД, 24-часовая очистка/expiry, SMTP/S3 failures, два физических сканера, нагрузка, Android-кеш/Room/DI и потеря мобильной сети не проверялись. Ни наличие серверных тестов в diff, ни этот ограниченный прогон не заменяют их дальнейшую проверку по мере интеграции.

## Следующая задача

Начать T10 с одного законченного remote-среза: авторизация/защищённое хранение bearer → публичное событие → заявка → QR/PDF билета. Адаптировать связанные domain/fake правила к принятым серверным решениям и доказать mapping/ошибки на этом срезе; затем подключать редактор/draft, scanner и dashboard. Приёмку нового API-поведения готовить отдельно от прежней demo-приёмки. Room/offline draft и удаление Q10 остаются реализацией Android, App Links и ticket resend в текущий объём не добавлять.
