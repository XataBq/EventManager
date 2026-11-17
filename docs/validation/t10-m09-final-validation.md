# T10 M09 — итоговая проверка MVP

**Статус: PARTIAL_EXTERNAL / HUMAN_PENDING.** Разрешённая Android-интеграция завершена до идентифицированной сборки code24; технические результаты перечислены ниже. Полный DONE не заявлен: реальные browser-письма и опубликованные consent-документы отсутствуют, итоговую ручную приёмку подтверждает пользователь.

База: `2e3672113c009883d987241dadc3b913c0091b6b`, ветка `codex/t10-api-integration`. Работа одним агентом, без push/production deploy. Начальные посторонние изменения сохранены в `.gradle/m09-evidence/baseline`; AGENTS, прежний CODE_REVIEW, prompts и JVM settings не включаются автоматически в коммит.

## Что изменилось

- Remote получил три независимых корневых стека общего NavigationState; используются прежние Event/Editor/Tickets/Scanner/Volunteers/Dashboard/Profile. Back из вложенного билета возвращает к E, root card — к списку. Hidden readers остановлены, entry owners удерживаются своими стеками. Повтор текущего таба проходит через Editor/Scanner Back, dirty profile leave сохраняется.
- Logout публичного E сразу удаляет private viewer/profile/bearer, сохраняя только public-only проекцию до нового guest GET.
- Поздний DELETE204 очищает данные удалённого account после смены actor, сохраняя другой account. Новая session того же удалённого account завершается; late failure/cancel не делает профиль нового actor неопределённым.
- Подтверждённый scan инвалидирует старые GET и затем последовательно получает свежий server count; локального увеличения допуска нет. Dashboard удерживает максимум32 неактивных/LRU query projections; observers и in-flight reads защищены pin до finally.
- XLSX проверяется на Android Expat без неподдерживаемого Xerces-feature. DTD блокируется до парсера; разрешены Unicode/ASCII encodings, запрещён обход через EBCDIC/смену encoding. Valid UTF-8/UTF-16, corrupt ZIP и DTD проверяются отдельно.
- Сервер нормализует обложки: реальные1753 байта PNG превратились в1480. Transfer больше не приравнивает server byteCount/mime к локальному оригиналу; descriptor проверяется по допустимому типу/размеру/dimensions, локальные source metadata остаются для managed file и Room.
- Auth email/password не persist в SavedState; legacy email keys удаляются. Guest receipt остаётся ограниченным своим flow по принятому M03.
- Включён оптимизированный unsigned remoteRelease; demoRelease отключён. Remote label — EventPass, demo сохраняет отдельное имя. API base задаётся HTTPS build property, секреты в BuildConfig не добавлены.

## Единая матрица покрытия

| Срез | Проверка и реализация | Результат / предел |
|---|---|---|
| Auth/account | Existing transport/VM regressions, current/late401, cancelled acceptance, Recovery, profile partial PATCH/null/false/email, actor/delete races | PASS в общем JVM прогоне. Настоящий Android Keystore:4 теста на каждом API28/API36. EmailVerified не является auth gate. |
| Public E / анкета | Existing M02 mapping/policies/VM и presentation compilation, slug≠id, 10 типов/Unicode/false/arrays/unsupported | PASS в JVM; fresh public staging smoke PASS. Полный визуальный Figma/TalkBack — человеческая приёмка. |
| Registration / tickets | Existing M03 immutable retry/schema/scope races, guest capability, list/card isolation, cancel/PDF | PASS в JVM; live registration/list/card/PDF/cancel staging PASS. Guest browser setup с настоящей почтой не подтверждён. |
| Room / offline |9 тестов настоящего Room/Bundled SQLite, включая6 coordinator+HTTP tests: reopen, immutable create key/body, lost ACK, ACK A сохраняет B, expired unknown без нового POST, conflict choice/stable IDs, normalized uploaded descriptor/restart, purge/late ACK, cold Recovery retention | PASS. Это production schema/DAO/store/coordinator sources, не in-memory имитация. Android process death отдельно доказан ниже. Schema1 не менялась, destructive migration не добавлена. |
| Editor / cover | Shared Android managed-copy validation, реальный PNG upload/attach/detach, incomplete draft, publish started E, edit questions after registration, historical false/export, finish/published adapter regressions | Native managed-copy test PASS на API36. Live StagingEditorSmokeTest PASS после исправления normalized receipt. Остальные owner policies/commands — existing JVM/VM regressions. |
| Scanner / volunteers | Sparse/replay/invalid/used, same key/lost ACK, old GET count race, grant/revoke по real userId; server race двух scanners | PASS JVM и fresh staging. UI denied-camera→manual проверен на API28; modern полный smoke также PASS (`final-modern-retry.log`). Оптическое чтение QR физической камерой — human checklist. |
| Dashboard | Coherent stats/page revision, pagination25 synthetic rows, query/cursor/bounds, bounded STALE_PAGE, Used cancellation, actual ticketId, LRU retention/pinned page | PASS JVM. Live21 registrations/page2/search/counts/cancel/revoke PASS; totals не выводятся из видимой страницы. |
| Profile/delete | Shared Account/session, dirty/save/observer guards, late204/switch/cancel; actual account204 and cascade | PASS JVM. Live owner deletion проверяет исчезновение его событий и билетов других участников при сохранении их accounts; own fixtures cleanup в finally. |
| Files | MIME/origin/redirect/current401/late actor before output, ZIP/XML limits; real server PDF/XLSX, real DocumentsUI cancel/reopen/rotation | PASS transport/native. Реальный SAF PDF открыт/отрисован Poppler; XLSX ZIP/XML и содержимое/history проверены. Полный набор сторонних SAF providers/office viewers не проверен. |
| Navigation/lifecycle | Real remote DI, Tickets Back, three tabs, Dashboard/Scanner parent, denied manual, profile recreation; true OS process death | API28 и API36 полный сценарий PASS; финальный API28 startup/insets/intent boundary —2/2 PASS. PID14834→15064, между ними процесс отсутствовал, Android task28 сохранился. После cold /me восстановлен безопасный корень; сохранённый pending найден в Room. Raw unsaved ввод при process death не обещается. |
| Release / isolation | Debug demo/remote, unsigned R8/resource shrink release, lint3 variants, verifyDemoIsolation, actual release bootstrap/Room | Builds/lint/isolation PASS. R8 verification copy подписана только debug test key: ранний authenticated bootstrap/Keystore/Room PASS; после последнего file/cover fix финальная release copy запущена cold до guest login (`final-release-guest-layout.json`). Это не production подпись/URL. |
| Docs/review | Structural docs + diff checks, отдельный frozen sequential review | Итог дополняется после заморозки. Авторский проход обозначается как self-review; независимость не выдумывается. |

## Общий прогон и evidence

Последний общий прогон после исправлений: `.gradle/m09-evidence/final-all.log`, **exit0, 3м41с, 533 tasks**. Все JVM Test tasks принудительно исполнены без повторной компиляции неизменённых зависимостей через `final-tests.init.gradle`.

| Модуль/вариант | Passed | Failed | Skipped |
|---|---:|---:|---:|
| core:domain |36|0|0|
| data:fake |101|0|0|
| data:remote (включая5 live staging) |70|0|0|
| data:local:verification |9|0|0|
| core:platform |15|0|0|
| app demo |95|0|0|
| app remote |26|0|0|
| **Исполнений** |**352**|**0**|**0**|

После дедупликации shared app test class/name: **326 уникальных кейсов**, без сложения адресных повторов. XML сохранены в `.gradle/m09-evidence/final-all-results`; машинная сводка `final-all-summary.json`.

Команда общего прогона: JBR21, `EVENTPASS_STAGING_SMOKE=1`, wrapper с tasks `:core:domain:test :data:fake:test :data:remote:testDebugUnitTest :data:local:verification:test :core:platform:testDebugUnitTest :app:testDemoDebugUnitTest :app:testRemoteDebugUnitTest :app:assembleDemoDebug :app:assembleRemoteDebug :app:assembleRemoteRelease :app:assembleRemoteDebugAndroidTest :app:lintDemoDebug :app:lintRemoteDebug :app:lintRemoteRelease :app:verifyDemoIsolation`; init scripts `.gradle/m09-evidence/final-tests.init.gradle`, `.gradle/m05-evidence/verified-artifacts.init.gradle`, `.gradle/m01-verified-google.init.gradle`; `--offline --console=plain --continue --max-workers=2`.

Адресные device результаты: `files-keystore.log` —8 исполнений/4 кейса; `final-verified.log` —6 XLSX исполнений/3 кейса на API28/API36; `native-cover.log` —1 shared platform cover test; `api28-profile-camera.log` — полный remote UI smoke1/1; `final-modern-retry.log` — полный smoke1/1 на финальном APK; `final-min-device.log` —2/2 minSdk startup/insets/intent boundary; `real-process-death.json`, `process-reopened-drafts.json`, `release-room-layout.json`/PNG — отдельное OS/R8 evidence. Системный UI проверяется на собственных EventPass_M09_API28 (Android9) и EventPass_M09_API36 (Android16/API36.1), пользовательские AVD/data не очищаются.

Промежуточные ошибки не скрыты: baseline не собрал fake tests и выявил logout regression/устаревшие adapters; normalized cover и Android XML feature были реальными defects. Fixed-delay guest test заменён latch-ordering, его assertion сохранён. UI harness исправлялся для Compose clock/native DocumentsUI/IME/LazyColumn, не отключением проверок. Последний modern повтор ранее не вернулся из picker: после поворота тест нажимал stale accessibility node. Ожидание новой orientation/window и повторное получение filename/Save node устранили сбой; неизменённый APK прошёл весь сценарий за46с, включая подтверждённый cleanup. Один промежуточный общий staging прогон упёрся в server Cooldown; последний общий прогон выше уже прошёл все5 live cases. Старые XML до baseline содержали stale fake результаты и не суммируются как новые.

## APK и заморозка

Acceptance: **remoteDebug**, `ru.eventpass.android.remote`, **0.10.9-m09-rc1-remote / code24**, min28/target37, API `https://staging.eventpass.dikanskii.ru/api/`.

- [Установочный APK](../../artifacts/apk/eventpass-0.10.9-m09-rc1-remote-code24.apk): **54206423 байт**, SHA-256 `c06977fd75d7f73c74f7f01afd2e83bb5eeeb5a2995ce59e9bcfdbd20fe1a3fe`.
- [Unsigned R8 release](../../artifacts/apk/eventpass-0.10.9-m09-rc1-remote-code24-release-unsigned.apk): **10443856 байт**, SHA-256 `cb9fdd8cbe1caa88bdb1cc44d9d7d557b7da8958c749e9e3a616ae8e29d952be`. Не устанавливается до подписи.
- Acceptance certificate: Android Debug, SHA-256 `7637d5c353c3c5212e60b54eeb13923cac90db1440156dd6e275fa2f5c141895`. Это локальная проверочная подпись.
- `aapt` подтвердил label EventPass, version/min/target, optional camera/rear camera/autofocus; release без debuggable, cleartext=false и backup=false. `verifyDemoIsolation` проверил зависимости; дополнительно в содержимом обоих APK не найдены fake/tools/gallery markers и6 точных private values из локальных fixtures. Это адресная проверка, не универсальное доказательство отсутствия любых секретов.
- `artifact-audit.json`: ZIP/CRC/XML проверены на4 реальных XLSX, task28 до/после OS kill совпал. PDF отдельно отрисован и просмотрен (`saved-ticket-preview.png`). `acceptance-signature.txt`, `acceptance-badging.txt`, `releaseUnsigned-manifest.txt` сохранены локально.
- Production source/build freeze — implementation commit M09 (точный hash фиксируется следующим документационным коммитом после заморозки); output acceptance совпадает побайтно с APK последнего общего прогона. После него менялся только AndroidTest harness/документация.

Установка: `adb install -r artifacts/apk/eventpass-0.10.9-m09-rc1-remote-code24.apk`. Для чистых destructive сценариев используйте отдельный synthetic account; личный аккаунт пользователя автоматически не удалять. Файлы evidence в `.gradle/m09-evidence` игнорируются Git, секреты из private JSON в отчёт не копировать.

Предыдущий code23 и исторические demo APK сохранены. Любое последующее production изменение требует новой идентифицированной сборки и адресной проверки; данный отчёт не переносит результаты на произвольный будущий output APK.

## Внешние условия и реальные ограничения

1. `/auth/consent` на preflight1.1.1 возвращал null URL/version для terms/privacy. Подготовлены [privacy draft](../legal/privacy-draft.md) и [terms draft](../legal/terms-draft.md) по сведениям пользователя: Мосполитех, Россия, demo123@mail.ru. Полные реквизиты, основания/сроки/подрядчики, утверждение и HTTPS публикация остаются оператору/backend. Правовая готовность не заявляется.
2. Пользователь разрешил отложить backend-почту. Реальная доставка и открытие verify/setup/reset не подтверждены и не являются PASS. Созданный на указанной пользователем почте аккаунт не удаляется автоматически; credentials только `.gradle/m09-evidence/mail-account.private.json`, не Git/APK. Пароль ящика не запрашивался.
3. Отсутствие If-Match оставляет гонку GET→PATCH двух устройств. Пользователь явно принял ограничение; localRevision не объявляется server lock. Реальный conflict workflow проверен, атомарность серверной записи не заявляется.
4. Две ранние UI teardown-попытки получили401, потому что тест использовал токен после logout и ещё не сохранял пароль. Их server cleanup не подтверждён: backend должен проверить0–2 собственных synthetic accounts с prefix `android.m09.device.`/`@example.com` и именем `M09 retained edit`. Это не личный аккаунт пользователя. Доступные идентификаторы/таймстампы только в игнорируемом evidence. Тест исправлен: private recovery record + fresh sign-in + подтверждённый DELETE204; новые fixtures очищаются.
5. Production URL, frontend origins/App Links, signing key и store publication не предоставлены/не выполнялись. Release — unsigned staging build; runtime copy с debug key не production credential.
6. Физическая камера/оптическое распознавание, полный TalkBack/Figma visual pass, все шрифты/ширины и все SAF providers — не автоматическая human acceptance. При поздней ошибке provider может оставить неполный выбранный пользователем файл; клиент не удаляет пользовательские SAF copies. Upload без server idempotency может оставить orphan asset при утрате ответа; серверный GC/backup retention приложением не доказаны.

## Одна ручная приёмка на финальном APK

Пока **NOT_RUN**; результат отмечает только пользователь. Установить именно APK/hash выше, сохранить текущие пользовательские данные. Личный тестовый аккаунт из локальной конфигурации не использовать для удаления.

- Вход unverified account, три вкладки, профиль/сохранение/email/notifications. После исправления backend — реальные verify/setup/reset в браузере и возврат в приложение.
- Мероприятие по slug, live/full/finished, форма10 типов и Unicode/false/multiple; guest QR только в текущем flow, без автоматической отправки после login.
- Tickets current/past, detail, PDF, отмена/Used; Back из detail и E к правильному родителю, вкладки/скрытые forms.
- Черновик без сети → сохранить локально → перезапустить → синхронизировать; конфликт двух устройств и явный выбор версии; обложка/отмена picker/замена/публикация/завершение.
- Два аккаунта и два E: grant/revoke, возврат/resume; физическая камера, отказ permission/manual EVT, wrongE/cancelled/used/replayed без повторного допуска.
- Dashboard counts/search/page2, cancel/scan race и возврат из Scanner/Volunteers; export всех статусов и исторических ответов независимо от query.
- PDF/XLSX в используемом вами provider/viewer, cancel/reopen/rotation; logout не удаляет пользовательскую сохранённую копию.
- Удаление только отдельного disposable account: понять cascade, проверить другие accounts, неопределённый ответ и повторную сверку.
- Portrait/обе landscape, трёхкнопочная/gesture navigation, IME, увеличенный шрифт и TalkBack; сравнить mobile Figma00–04/06. Старые demo H-приёмки не закрывают этот пункт.

После замечаний — исправление, адресный повтор и новый APK до подтверждения. **M09/T10 DONE — только после принятия пользователем финальной сборки и явного разрешения внешних остатков.**
