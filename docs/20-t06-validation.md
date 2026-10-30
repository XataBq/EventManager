# T06 — редактор и управление мероприятиями, 30 октября 2026

## UI-интеграция после H2 — 30 октября 2026

**T06 — READY_FOR_HUMAN.** Актуальная запись [H2](19-t05-validation.md) подтверждает приёмку T05 пользователем; номер сборки и устройство там не уточнены. Условие H2 снято, повторный запрос не делался. Остаток T06 из foundation реализован; организаторский smoke ожидается. T02 остаётся IN_PROGRESS, H3 целиком относится к T08, H4 не закрыта.

### Передаваемая сборка

[app-demo-debug.apk](../app/build/outputs/apk/demo/debug/app-demo-debug.apk): **0.6.1-t06-demo / code 11**, **34855946 байт**, SHA-256 **`ab1567bb070cab86dca460830feb9298144495b38a0eeeccf87b9aadcb7d028d`**. ApplicationId `ru.eventpass.android.demo`, minSdk 28, compile/target 37. Debug-подпись v2 и aapt2/output metadata проверены. [Test APK](../app/build/outputs/apk/androidTest/demo/debug/app-demo-debug-androidTest.apk) также собран. Code 9 для истории H2 остаётся в `artifacts/apk/`; основной output code 10 заменён code 11, его прежний hash ниже — история, не текущая сборка. Передаваемый APK дополнительно сохранён в `artifacts/apk/eventpass-0.6.1-t06-demo-code11.apk` (локально, не в Git).

Seed `t02-v1` / технический `t01-v1` прежние. `alex@example.com` и `host@example.com`, пароль `DemoPass123!`. Один аккаунт, независимые owner/grant/ticket связи. Event Clock `2026-10-27T09:00Z` + временный offset, auth Clock system UTC + отдельный offset. Для smoke можно выбрать начало **2026-10-28 12:00**, Europe/Moscow, либо более позднюю дату относительно demo Clock. Реального backend/API, серверной публикации или почты нет: публикация меняет общий локальный demo store.

### Реализованный UI и состояние

- Root Events — один MyEvents с вкладками «Организую / Волонтёр / Черновики», loading/empty/read error/retry. Вкладка в SavedStateHandle; временная ошибка чтения оставляет прежние данные. Владение и grant не взаимоисключающие; редактирование/ссылка владельца, публичная страница для обоих списков. Dashboard/Scanner/Volunteers не подменены фиктивными успехами.
- `EditorKey(draftId/eventId, create, entryId)` содержит только ID и флаг нового draft. Hilt EditorViewModel наблюдает guarded readForEditing/observeEditor; чужой маршрут не раскрывает полей, сама mutation повторно проверяет owner/session. Прямой forged route проверен device-тестом. Root/hidden entry owners прежние; logout/expiry уничтожают private scope. ViewModel/Screen не знают fake, DTO и файлов. Preview — отдельный platform interface за DI и entry-scoped ViewModel.
- Один редактор без оплаты/шагов: title, начало, optional end, явный IANA timezone, venue, unlimited либо числовой capacity. Дата вводится `ГГГГ-ММ-ДД ЧЧ:ММ`, парсится строго в зоне события. Невозможные/неоднозначные DST-времена отклоняются; иной часовой пояс устройства не применяется. Полностью пустые обязательные поля можно сохранить в draft; неверный частично набранный формат даты/лимита требует исправления и остаётся в форме, ложного сохранения нет. Неизвестная зона у ранее сохранённого неполного draft не вызывает падения: исходный Instant виден до исправления.
- Явный save; dirty Back предлагает сохранить/выйти без сохранения/остаться. Пока идёт mutation, повторные действия и Back блокируются до результата. Смена таба сохраняет entry, повтор выбранного Events проходит через dirty Back. Unsaved input живёт в ViewModel (rotation), сохраняемый draft — в repository (новый процесс); autosave не заявляется. Ошибка записи не показывает успех. Временный read failure не очищает dirty форму. Description/sections не имеют нового UI-редактора и сохраняются из исходного документа.
- Редактор text/checkbox/single-choice: стабильные question/option IDs, required, добавление/удаление/тип/название, порядок вопросов и вариантов без замены ID. После первой регистрации UI объясняет блокировку; data guard основан на сохранённой истории, а не на текущем числе билетов. formVersion меняется только со схемой; revision защищает всю правку. Конфликт оставляет ввод пользователю, для новой базовой ревизии нужно выйти/открыть editor заново.
- Publication result — состояние того же editor entry, а не повторяемый effect. Приватный publication receipt восстанавливает тот же eventId после restart/повторного вызова. O03 показывает настоящую `eventpass-demo://demo/e/event-<UUID>`: Android Clipboard, Sharesheet и открытие общего E. Cancel share не меняет успех публикации. Ссылку можно снова скопировать из MyEvents. Cold/warm E parser прежний, новые IDs подходят его whitelist.
- Owner может закрыть регистрацию или завершить E; dirty правки сначала сохраняются/отменяются. Общие Clock/availability/edit policy foundation сохранены: starts закрывает заявки, ends/manual finish завершают; terminal update запрещён. Ticket display metadata обновляется в той же транзакции, credentials/status/ownership/старые answers не меняются. Ранее выданный guest receipt/письмо остаётся снимком выдачи, постоянного guest cache не добавлено.
- Поздние результаты проверяют cancellation и generation после suspend, старый finally не снимает busy новой entry. Проверены A → cancel/rebind → B → поздний A; старый save не запускает publish. Сохранены восемь auth-регрессий и обе T05 cancelled-submit-finally проверки.

### Обложка и сохранение

Optional PNG/JPEG до 5 MiB, crop отображения 16:9. Системный OpenDocument без широких media permissions; cancel не меняет прежнюю обложку. Файл проверяется по decoded content MIME/размеру/границам и пробному bounded decode, не расширению или provider MIME. Байты копируются на IO во внутренний `noBackupFilesDir/event-covers/<assetId>.image`, затем descriptor сохраняется draft/event в прежнем snapshot **schema 3**. Временный URI grant после копирования не нужен; image bytes/Bitmap не попадают в route/SavedStateHandle/snapshot. Preview до 1024px по длинной стороне, decode на IO, JPG EXIF учитывается. Верхний предел каждой стороны 32768px — обратимый технический default защиты decoder.

Неполные `.tmp` удаляются при отказе/отмене. До первого импорта в новом процессе выполняется однократная очистка только собственных файлов без ссылок: retained IDs получены из **всех** events/drafts общего store под тем же Mutex. При ошибке чтения snapshot очистка пропускается. Текущие несохранённые импорты до следующего процесса не удаляются; это защищает другие живые editor entries. Схема/миграции v1/v2 foundation не менялись. В обычном запуске или StorageUnavailable данные не сбрасываются.

Primary API references: [OpenDocument / storage access](https://developer.android.com/training/data-storage/shared/documents-files), [bounded bitmap decode](https://developer.android.com/topic/performance/graphics/load-bitmap), [platform EXIF](https://developer.android.com/reference/android/media/ExifInterface). Новых внешних библиотек нет; core:platform получил зависимости от существующих core:domain/coroutines. Wrapper/toolchain/daemon criteria и версии прежние (JBR 21 Studio1, Gradle 9.5 / AGP 9.3.3 / Kotlin 2.4.21); успешная сборка подтверждает это зафиксированное сочетание, не будущие версии.

### Проверки текущего изменения

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
.\gradlew.bat :core:domain:test :data:fake:test :app:testDemoDebugUnitTest :core:platform:testDebugUnitTest :app:assembleDemoDebug :app:assembleDemoDebugAndroidTest :app:lintDemoDebug :app:verifyDemoIsolation -I .gradle/t06-evidence/repeat-tests.init.gradle --offline --console=plain --max-workers=2
```

Последний полный прогон — **BUILD SUCCESSFUL, 55s**, 187 tasks: 64 executed / 123 up-to-date. Test tasks принудительно исполнены через init script `outputs.upToDateWhen { false }`; assemble/lint частично инкрементальны, clean не выполнялся. **101 JVM = domain 11 + fake 51 + app 36 + platform 3**, 0 failures/errors/skipped. Новые 7 VM-тестов: incomplete save/reload/publish/no duplicate, dirty Back и storage failure, строгие timezone/DST/invalid dates, denied route/logout, dirty read retry, stale revision и cancelled old save/finally. После этого изменена только устаревшая подпись шапки «T05» на общую «Демонстрация»; delivery-build.log подтверждает повтор assemble app/test APK/lint/isolation. Initial lint выявил LocalContext.getString для Sharesheet, исправлено через stringResource без baseline/исключений. Текущий lint: **0 errors / 4 прежних version notices**.

```powershell
# Только отдельный эмулятор, физический телефон не используется:
adb -s emulator-5584 install -r app/build/outputs/apk/demo/debug/app-demo-debug.apk
adb -s emulator-5584 install -r app/build/outputs/apk/androidTest/demo/debug/app-demo-debug-androidTest.apk
adb -s emulator-5584 shell am instrument -w -r -e class ru.eventpass.app.EventEditorIntegrationTest,ru.eventpass.app.EntryOwnershipTest,ru.eventpass.app.NavigationSmokeTest ru.eventpass.android.demo.test/androidx.test.runner.AndroidJUnitRunner
```

**9/9 device** на передаваемом APK (4 новых + 5 navigation/entry ownership), API 33 Android 13, `sdk_gphone64_x86_64`, 1080×2400, портрет, navigation_mode 0 (3 кнопки). Проверены: incomplete draft/recreate → publish → Clipboard → common E → guest custom question/ответ → receipt → form lock; forbidden editor route; новый environment с настоящим Android AtomicFile (Guest после чтения, draft/cover восстановлены после sign-in); MIME/content/oversize/unsupported URI, copy после удаления source, cleanup retained/unreferenced, JPG EXIF rotation. Test-only exported fixture provider находится только в test APK, не main manifest. Проверены существующие hidden owner/back/tab/warm link сценарии. Собственный эмулятор останавливается после проверки; подключённый физический телефон не изменялся.

Эти проверки не являются OS process-kill proof новой сборки: есть Activity recreation, новый backend/AtomicFile и прежний независимый foundation restart test. Нет нового landscape/gesture/API28/API37/fontScale/IME/TalkBack/реального picker/Sheets UI прогона. System picker cancel/revoked cloud provider grant/реальное share target selection остаются ручными; импорт и копия проверены через test content provider. Подпись/metadata/hash и screenshots сохранены в `.gradle/t06-ui-evidence/`. Адресные screenshots editor/published просмотрены; полного визуального принятия нет.

Figma skill применён: свежий O02 `17:9426` context+screenshot получен; O01 `29:558`, V01 `29:96`, O03 `29:664` вернули Starter limit. Для них использованы разрешённые источники `sources/figma-2026-10-21.json`, navigation/design docs и core:designsystem. Обложка — динамические данные, не sample image из макета. UI-kit/Inter/insets переиспользованы; оплата/«шаги» не перенесены. Точное визуальное совпадение недоступных frames не заявляется.

Сохраняются реальные ограничения demo: локальный store одного устройства, нет API/настоящей почты/реального домена/App Links/PDF/камеры/check-in/grants UI/dashboard. Schema 3 не читается старым code 9, обратной миграции нет. Ошибка approval review 403 при сетевом сбое не выполнила команду; после восстановления повтор разрешён и успешен. CODE_REVIEW.md, чужие prompts, daemon settings и прочие посторонние изменения не менялись и не включаются автоматически в коммит.

### Ручной организаторский smoke — ожидается

1. Установить **code 11 / hash выше**. Войти Alex, открыть «Мероприятия», проверить Organizing/Volunteer/Drafts без глобальной роли. Создать draft только с названием, сохранить, закрыть/запустить приложение, снова войти и открыть draft.
2. Заполнить start/optional end/zone/venue, проверить unlimited отдельно от 0 и невалидной даты. Проверить Back: остаться, сохранить и выйти, выйти без сохранения. Unsaved ввод после нового процесса не обещается; восстанавливаются явно сохранённые поля.
3. Добавить text/checkbox/single-choice, required, несколько вариантов; переставить вопросы/варианты, изменить/удалить. Сохранить/reopen, убедиться, что всё на месте. Проверить ошибки пустого choice и дублей вариантов.
4. Через системный picker выбрать PNG/JPG, отменить выбор, попробовать неверный файл/>5 MiB, убрать/заменить обложку. Проверить правильную ориентацию JPG, 16:9 preview и public E; после нового запуска и удаления исходного фото копия должна остаться.
5. Publish → действующая demo-ссылка. Проверить настоящий Clipboard/ShareSheet, cancel share и отсутствие повторной публикации. Открыть свою ссылку; после logout открыть её гостем cold/warm, заполнить свою анкету, получить QR.
6. Вернуться владельцем: вопросы заблокированы после первой регистрации, включая отмену всех и restart. Изменить metadata: E/MyEvents/Tickets согласованы, QR/status не меняются. Проверить close (описание/билеты остаются) и finish (не открывается переносом даты). Capacity ниже Active+Used отклоняется.
7. Клавиатура, большие шрифты, TalkBack, обе альбомные ориентации и жесты/3 кнопки на своём устройстве. Записать build/hash/Android/замечания. **Агент smoke не принял**; после приёмки T06 — T07 grants и общий scanner. Полная H3 по-прежнему относится к T08.

## История foundation — 29 октября (не текущая сборка)


**IN_PROGRESS.** Пользователь ответил: «Ещё не проверял — выполнить независимую часть T06». H2 на T05/code 9 ожидается. В этом изменении выполнены только контракты/domain/fake/persistence и их тесты. MyEvents/editor/Published UI и полный путь публикация → гостевая заявка не подключены. T03/T04 остаются DONE, T02 продолжается; H3 не закрыта.

## Сборки и воспроизводимость

Текущий [app-demo-debug.apk](../app/build/outputs/apk/demo/debug/app-demo-debug.apk): **0.6.0-t06-foundation-demo**, code **10**, **34002962 байт**, SHA-256 `4605116c9d497efe4cac19f077d3f1383962c2d34cc61d1954c8c4b7ee9f70db`. ApplicationId `ru.eventpass.android.demo`, minSdk 28 / target и compile 37; debug-подпись v2 проверена. [Test APK](../app/build/outputs/apk/androidTest/demo/debug/app-demo-debug-androidTest.apk) собран. Ни одна сборка этого этапа на устройство не устанавливалась.

Для ожидающей H2 сохранён **прежний** [eventpass-0.5.1-t05-demo-code9.apk](../artifacts/apk/eventpass-0.5.1-t05-demo-code9.apk), 33593307 байт, SHA-256 `7012bf6f5733c9e5e0f0ad48d34bc3244618454e281c19839d577c5153dc51d7`. Файл находится в игнорируемом каталоге локальных APK и не входит в Git. H2 проверяется на code 9 по [19-t05-validation.md](19-t05-validation.md) до установки новой foundation: старый APK не читает schema 3, безопасная обратная миграция не реализована. Сброс данных автоматически не выполняется.

Wrapper/версии зависимостей/SDK не обновлялись. Использован JBR 21 из `C:\Program Files\Android\Android Studio1\jbr`, существующие wrapper Gradle 9.5 / AGP 9.3.3 / Kotlin 2.4.21, пользовательские daemon criteria сохранены. Это успешная сборка существующего сочетания, не обещание совместимости будущих версий. Новых библиотек/platform API в этой независимой части нет. Figma повторно не запрашивалась: визуальных изменений нет, O01/V01/O02/O03 не проверялись.

Seed прежний `t02-v1`, технический counter demo — `t01-v1`. Event Clock fixed `2026-10-27T09:00Z` + временный offset; auth Clock system UTC + отдельный offset. Offset не сохраняется. В тестах clocks детерминированы отдельно. Demo-аккаунты `alex@example.com` и `host@example.com`, пароль `DemoPass123!`; пароль не новый серверный протокол. Исходное E: `eventpass-demo://demo/e/open-registration`. Новые опубликованные через contract E получают `event-<UUID>` и открываются существующим публичным reader; UI генерации/копирования ссылки пока нет.

## Что реализовано

EventRepository дополнен без новых модулей и параллельного fake backend:

| Порт | Результат и ограничения |
|---|---|
| observeMyEvents / refreshMyEvents | Organizing по owner, Volunteering по активному grant, приватные неопубликованные drafts; Ready/typed failure. Один E может быть в двух списках, отзыв grant не удаляет билет |
| observeEditor(Draft/Published ID) | Только подтверждённый действующий владелец получает поля; guest/unverified/expired → AuthenticationRequired, чужой → Forbidden, отсутствующий → NotFound. Grant не даёт редактор |
| saveDraft(id, expectedRevision?, fields) | Стабильный DraftId; null revision создаёт, совпавшая ревизия обновляет. Неполные поля и незаконченный single-choice разрешены; публикационная валидация отдельно |
| publishDraft(id, expectedRevision) | Валидация → атомарная запись → eventId. Приватный receipt публикации остаётся в draft record: повтор/параллельные вызовы возвращают тот же eventId, draft исчезает из активного списка |
| updateEvent(id, expectedRevision, expectedFormVersion, fields) | Owner/session guards, конфликт ревизии, StaleForm, QuestionsLocked, field validation; title/venue не меняют formVersion |
| closeRegistration / finishEvent | Только владелец, проверка ревизии; close сохраняет публичное описание и билеты, manual finish терминален |

Типизированы Validation с ключами полей, Conflict, StaleForm, QuestionsLocked, EventFinished, AuthenticationRequired, Forbidden, NotFound, SessionChanged, Network, Timeout, StorageUnavailable. Тексты будущего UI должны выбираться из resources. Loading остаётся ответственностью будущей ViewModel, Flow возвращает прочитанный результат.

Отдельный EventDraft/EventFields не требует Instant и валидной анкеты; Event не ослаблен ради сохранения пустой формы. DraftQuestion допускает незаконченное редактирование; при публикации превращается в существующие RegistrationQuestion. ID/порядок/required/варианты сохраняются. Actor и ownerId не принимаются из команды UI; actor разрешается из сессии под общим Mutex. После ожидания очереди повторно проверяются generation/expiry/verification. Ожидание и IO происходят на Dispatchers.IO, без блокирующего Java-монитора на потоке вызова.

Сначала доказан contract-срез: неполный draft → новый экземпляр backend → sign-in → правка → publish → observePublicEvent с теми же полями. Это чтение данных публичного EventDetails, **не** проверка Android-навигации или полного UI-сценария.

## Политики полей и времени

- Публикация: непустые title/venue без управляющих символов, start строго в будущем относительно Event Clock, корректная ZoneId, optional end строго после start. Capacity null — unlimited, 0 — нет мест, отрицательное значение не публикуется.
- Схема: 3 типа, уникальные непустые безопасные IDs, названия вопросов; choice требует непустые варианты с уникальными ID и различными непустыми подписями. Схема включает порядок/тексты/required/тип/варианты; её изменение увеличивает formVersion, обычные metadata — только revision.
- После первой успешной Registration (включая гостя) вся схема блокируется. История регистраций сохраняется после отмены всех билетов, поэтому блокировка переживает restart/v2 migration без отдельного сбрасываемого флага. Ответы/версии прежних регистраций не переписываются. Устаревший editor получает Conflict/StaleForm либо QuestionsLocked при актуальной ревизии после регистрации.
- Capacity не ниже количества Active + Used, Cancelled не учитываются. Это проверка локальной demo-команды; распределение последнего места на сервере не разрабатывалось.
- Обратимый технический default: до старта можно менять даты; после старта — metadata, с неизменными start/end/zone; после end или manual finish/cancelled update запрещён. Нельзя передвинуть завершившееся E в будущее. startsAt закрывает заявки, но не означает Finished; endsAt/manual finish завершают по существующей общей availability policy. Для событий без endsAt требуется ручное finish. В Ready есть now для будущего UI; отдельного фонового таймера/серверного realtime нет, refresh перечитывает Clock.
- Description/sections входят в полный EventFields и сохраняются при read → toFields → metadata edit; тест это проверяет. Будущий редактор обязан сохранять не редактируемые UI поля исходного документа. Отдельного rich-text editor нет.
- Обратимые технические пределы snapshot: title 200, venue 1000, description 10000 символов, 50 sections, 100 вопросов и 100 вариантов на вопрос; остальные текстовые поля также ограничены до безопасной UTF-записи. Они проверяются даже при saveDraft и не выдаются за согласованные API limits.

## Обложка и билеты

Cover optional. Добавлен только domain EventCover: opaque asset ID без путей/URI, PNG/JPEG MIME, размер 1..5 MiB. Descriptor persist в draft/event, image bytes в snapshot не попадают. **Файл, его содержимое, длительный URI grant, picker, managed copy/cleanup и 16:9 rendering ещё не реализованы** и ждут UI-интеграции после H2. Валидация descriptor не доказывает существование/тип файла. Тесты используют metadata без настоящего изображения; нынешний EventScreen сохраняет fallback.

Согласованная техническая display policy для будущих правок: server TicketSnapshot получает текущие event title/start/zone/venue в той же транзакции, что и Event. QR payload, ticket/registration IDs, attendee, ownership, ответы и Active/Used/Cancelled не меняются. Tickets уже читает эти display fields, поэтому после contract edit данные согласованы с public E/MyEvents. Ранее выданный гостевой receipt в saved state и уже отправленное письмо остаются снимком выдачи: постоянного guest lookup/обновления закрытого flow нет. Это явное ограничение B02, не новый гостевой cache; будущий API должен подтвердить политику изменения metadata.

## Persistence и миграции

Schema **3** сохраняет прежнюю бинарную product v2 часть и добавляет event revision/cover и отдельные draft records (owner/revision/incomplete fields/publication receipt). Auth v1 record внутри остаётся прежним. Тот же AtomicFile `noBackupFilesDir/demo-auth-v1.bin`, тот же MutableStateFlow/Mutex/IO для auth, tickets и editor. Успех/новое состояние публикуются после успешной записи; ошибка не создаёт ложную ссылку, draft или правку. Cancel до получения занятого Mutex не пишет; уже принятая серверная demo-операция не обещает отката после начала атомарной записи. UI отмены/late results редактора предстоит доказать отдельно.

Чтение v1 сохраняет accounts/proofs/challenges и прежнее seed-наполнение product, как T05. Чтение v2 сохраняет events/registrations/answers/tickets/status/grants/policy/nextId и весь auth record, назначает event revision 1, cover null, drafts empty. Первая следующая успешная запись любой команды переводит в v3. Тест использует **замороженный writer schema 2 из dbbbea1**, не текущий encoder, сравнивает прежние поля и выполняет подтверждение/вход после migration. Auth- и ticket-записи после saveDraft тоже сохраняют editor data.

Session, outbox secrets, несохранённая регистрационная анкета и guest UI snapshot не persist. Saved editor draft — другая сущность, не B04-анкета регистрации. Неизвестный/повреждённый/truncated/trailing snapshot даёт StorageUnavailable; файл не перезаписывается seed при обычном запуске. Явный debug reset остаётся отдельной командой. Обратной миграции v3 → v2 нет.

## Выполненные проверки

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
.\gradlew.bat :core:domain:test :data:fake:test :app:testDemoDebugUnitTest :core:platform:testDebugUnitTest :app:assembleDemoDebug :app:assembleDemoDebugAndroidTest :app:lintDemoDebug :app:verifyDemoIsolation -I .gradle/t06-evidence/repeat-tests.init.gradle --offline --console=plain --max-workers=2
```

Финальный прогон **BUILD SUCCESSFUL, 32s**, 186 tasks: 28 executed / 158 up-to-date. В init script только `tasks.withType(Test).configureEach { outputs.upToDateWhen { false } }`: все четыре JVM test task реально исполнены; компиляция/assemble/lint частично инкрементальны, clean не выполнялся. До него новые domain/fake контракты отдельно прошли за 8s; первый полный сборочный прогон — 46s. Первые диагностические прогоны не прошли из-за smart-cast компиляции и неверных ожиданий тестов (Unknown до initialize, номер schema и виртуальный timeout); они исправлены, не выдаются за зелёные.

| Модуль | Tests | Failures / errors / skipped |
|---|---:|---|
| core:domain | 11 | 0 / 0 / 0 |
| data:fake | 51 | 0 / 0 / 0 |
| app | 29 | 0 / 0 / 0 |
| core:platform | 3 | 0 / 0 / 0 |
| **Итого** | **94** | **0 / 0 / 0** |

21 новый тест: 4 domain + 17 fake; прежние 73 сохранены, включая 8 auth cancellation/generation/Mutex и обе cancelled submit finally регрессии. Старый migration-тест ожидает новый номер schema 3, остальные его проверки сохранены. Проверены приватность/expiry/grant, incomplete draft/restart/publish/retry, concurrent duplicate/stale save, версии/блокировка формы, capacity/time, metadata/credentials, storage failure и v1/v2/v3 migration/corruption. Отдельная проверка с реально заблокированной записью и единственным потоком вызова подтверждает выполнение следующей задачи и быструю отмену queued editor до освобождения записи.

Lint: **0 errors / 4 warnings** (имеющиеся version notices); baseline/исключения не добавлялись. Hilt app/test compilation и verifyDemoIsolation успешны: только demoDebug, fake через demoImplementation. APK metadata проверены aapt2 и output-metadata; apksigner подтвердил v2. `adb devices -l` пуст, эмулятор не запускался: device tests, OS process-death, новый QR/geometry/IME/TalkBack/fontScale и minSdk runtime **не выполнялись**. Прежние T05 device/process proof относятся к code 8. Код навигации/Compose и прежние регрессии не менялись.

После уточнения теста запуск команды и marker действительно выполняется на одном executor thread; адресный повтор `:data:fake:test --offline --console=plain --max-workers=2` также успешен: 51 тест, 6s. Production-код/APK после общего прогона не менялись.

Локальные материалы: `.gradle/t06-evidence/verification.log`, `final-fake.log`, `unit-results/`, `lint.xml`, `output-metadata.json`, `signature.txt`. Обычная sandbox-команда всё ещё не создаёт процесс (`helper_unknown_error: setup refresh had errors`); команды выполнялись через разрешённый elevated запуск. Это ограничение среды агента, не Android-дефект. Полное android-code-review не запускалось, CODE_REVIEW.md не изменялся.

## Остаток и ручная приёмка

Сейчас человек проверяет **H2 code 9** по разделу checklist в [19-t05-validation.md](19-t05-validation.md): гостевая заявка/QR, поворот/возврат/закрытие flow, отдельный аккаунт/подтверждение/ручной вход, свои билеты/отмена. Приёмка не отмечена агентом.

После H2 остаётся T06 UI: три MyEvents вкладки со states/retry; Hilt ViewModel/Route/Screen редактора; прямые route guards; даты с явной timezone; dirty Back save/discard/stay; busy/late result/session-entry isolation; редактор трёх типов вопросов и объяснение блокировки; системный picker с content/size validation и устойчивым asset adapter; cover rendering; O03 со Clipboard/ShareSheet; cold/warm опубликованная ссылка в общий EventDetails и сквозная гостевая заявка. Нужны адресные device/restoration/picker проверки и чтение mobile Figma nodes с фиксацией доступности. На текущем APK этих действий редактора нет.

Checklist будущего организаторского smoke после этой интеграции: создать неполный draft → сохранить/restart → заполнить дату/место/лимит → три типа вопросов → обложка → publish → copy/share → открыть гостем → отправить заявку → подтвердить блокировку вопросов после отмены/restart → close → finish. **Этот checklist сейчас не выполнен и ещё не готов к выполнению в UI.** H3 целиком относится к T08. Сначала завершение T06 после H2; следующий функциональный этап — T07 grants и общий scanner.
