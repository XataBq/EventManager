# T10 M05 — Room-черновики remote

## STATUS / SCOPE / GATE

**IMPLEMENTED / REVIEW_PENDING, 17 ноября 2026. Проверка намеренно ограничена пользователем.** Base `8da678a1983864173d991c63a0976e56780a95d8`, ветка `codex/t10-api-integration`. Target — локальный коммит с этим отчётом и [SHA-256 manifest](t10-m05-target-manifest.json). Новых APK/test APK, запуска устройств, push и M06 нет.

При запуске пользователь сообщил: «Ревью я сделал, можем не проверять. Просто выполняй». Это подтверждение gate M04 на текущем `8da678a`; команды/полнота его ревью неизвестны, автоматический вердикт не приписывается. CODE_REVIEW.md автором не менялся. M03 принят ранее на cec5520, M02 fix-review остаётся отдельно открытым, human/UI только после M09.

Последние команды пользователя: «в demo мне вообще не надо, делай только в remote», затем «сценарии тоже не прогоняй, только проверь что скомпилировалось». Они ограничили завершающую верификацию: **только remote compilation**, без сценарных tests/lint/demo/device/APK. Поэтому исходная обязательная матрица промпта не объявляется выполненной; отдельное ревью M05 должно учитывать пробелы evidence. До этого ограничения на ранних срезах уже выполнялись узкие Room и merge тесты (ниже).

Промпт M05 явно разрешал одного помощника: он написал только чистую DraftMergePolicy и семь её JVM-регрессий, без DI/Room/UI/review/commit. Другие агенты не запускались. Дальнейшая реализация и сдача последовательные, ревью не проводилось.

## BASELINE / СОХРАНЕНИЕ ЧУЖИХ ИЗМЕНЕНИЙ

Начальный status: изменены CODE_REVIEW.md, docs/00/06/34/35; untracked пользовательские t10-m04-editor-covers.md, t10-m05-room-drafts.md и gradle/gradle-daemon-jvm.properties. Копии `.gradle/m05-evidence/baseline`, исходный status `initial-status.txt`. CODE_REVIEW, task prompts и local JVM settings сохраняются побайтно и исключаются из коммита. Для четырёх dirty документов индекс получает только наши преобразования HEAD; исходные дополнения остаются в worktree, относящиеся к задаче статусные абзацы актуализированы. Manifest описывает committed LF blobs, а не дополненные worktree версии этих документов.

## REQUIREMENTS → CODE → EVIDENCE

| Критерий | Реализация | Фактическое evidence |
|---|---|---|
| Настоящая Room DB, schema1, account partition, nullable/stable metadata | data:local DraftDatabase/RoomDraftStore/DraftCodec; noBackup factory; узкий domain DraftStore | Production Android KSP/compile; ранние 3 JVM-теста на настоящем Room/Bundled SQLite, включая file reopen/rollback/CAS |
| Save local без HTTP, восстановимое тело/key/create mapping | LocalDraftRepository.saveDraft/syncDraft, StoredDraftCommand и DraftStore атомарная CAS | Скомпилировано; end-to-end offline/restart/create replay NOT_RUN |
| ACK A не стирает pending B; logout/delete не upsert | localRevision vs command.revision, storageVersion/incarnation/account generation; auth context guards | DAO stale CAS/purge проверены ранними JVM tests; HTTP-A/B и auth/logout integration NOT_RUN |
| Unknown PATCH и конфликт | GET перед new/unknown PATCH; base/local/remote; exact-CAS; explicit resolve; DraftMergePolicy stable IDs/null/deletion/order | 7 pure merge tests до сокращения проверок; coordinator/VM/HTTP transitions NOT_RUN |
| Pending cover | Durable managed source; upload descriptor persist до attach; retained refs cleanup; bounded orphan sweep | Remote/platform/UI скомпилированы; restart/upload/attach/cancel/logout/replacement сценарии NOT_RUN |
| Cold Recovery не авторизуется Room, warm validated offline edit | RemoteAuthRepository + scope capture; explicit logout purge callback в remote DI | Remote Java/Kotlin/Hilt скомпилированы; auth/offline behavioral regression NOT_RUN |
| Общий редактор/list без fake server items | localDrafts отдельно; remoteDraftId vs draftId; sync/conflict UI, raw Back guards | Remote Kotlin/Java compilation, без UI прогона; demo NOT_RUN по команде пользователя |
| M03/M04 совместимость | Registration/Tickets owners не переписаны; published online update/finish делегируются M04; nullable expected versions сохранены | Повторные адресные M03/M04 suites NOT_RUN; компиляция не доказывает отсутствие поведенческих регрессий |

## ARCHITECTURE / PORTS / CLEANUP

Действующая [спецификация](../architecture/t10-room-drafts.md); обновлены 02/03/05, architecture responsibilities/state-ownership/operations/invariants, API mapping, README/00/06/08/34/35. Domain не зависит от Room/Android. data:local — только определение собственных черновиков, без HTTP/DTO/PII участников/credentials; app remote DI связывает Room, remote coordinator, auth и managed files. Новая DB не является fake snapshot migration; fake codec6 не менялся. Один exhaustive branch RemoteDraft в fake — совместимость общего sealed target, не развитие demo feature.

Сохранение pending и create command предшествует HTTP. command.body/key/fingerprint неизменны при появлении B. firstAttempt не продлевается; expired unknown запрещает новый POST/key. ACK принимает server mapping/snapshot и очищает только отправленную версию. Pending B остаётся; unknown PATCH требует GET, расхождение даёт conflict. Explicit merge сохраняет stable IDs, не создаёт исторические answers из новой схемы. Есть отдельные runtime/SQL/auth ownership guards; они требуют самостоятельной поведенческой проверки.

Explicit logout отзывает auth epoch, purge инвалидирует account lease, затем освобождаются cover IDs без чужих retained refs. Auto401/account switch retain приватную работу, но hide до проверенной сессии того же account. Warm network refresh не превращается в logout; cold Recovery не читает account data. DB/files находятся в noBackupFilesDir. Release после DB transition; orphan sweep перед первым import процесса, максимум 128 удалений внутри managed каталога. HTTP/file IO не удерживает SQL transaction.

## TOOLCHAIN / ЗАВИСИМОСТИ

Закреплены Room **2.8.5** и SQLite/Bundled driver **2.7.1**, без изменения существующих Kotlin2.4.21/KSP2.3.12/AGP9.3.3/Gradle9.5/Hilt2.60.1/minSdk28/compileSdk37. Основания версий: [официальные Room release notes](https://developer.android.com/jetpack/androidx/releases/room), [официальные SQLite release notes](https://developer.android.com/jetpack/androidx/releases/sqlite). Совместимость заявляется только в объёме фактических KSP/Android/JVM compilations и ранних SQLite tests, не как общая гарантия всех конфигураций.

Java Maven Central соединение завершалось timeout: первый пробный прогон занял 5m06, успешной проверкой он не является. Для недостающих точных артефактов использован native curl с ограничением connect5s/max30s, официальные repo1.maven.org и Google Maven, опубликованные SHA1 и локальные SHA256. Изолированный ignored `.gradle/m05-maven` и exclusive init фильтруют только точные добавленные координаты, не заменяют глобальный cache и не отключают TLS. [Manifest загруженных зависимостей](t10-m05-dependencies.json). Для повторения без подготовленного cache обычному Gradle потребуется сетевой доступ. PowerShell quoting/configuration попытки, исправление JVM target/Room-test close и отсутствие offline annotation AAR были неуспешными итерациями, не tests passed.

## CHECKS

JAVA_HOME — `C:\Program Files\Android\Android Studio1\jbr`. Все Gradle команды с wrapper, `--offline --console=plain --max-workers=1`; добавлены локальные verified artifacts init, для app также `.gradle/m01-verified-google.init.gradle` (прежний verified tracing artifact).

| Команда / срез | Результат | Evidence |
|---|---|---|
| `:core:domain:test --tests ru.eventpass.core.domain.event.DraftMergePolicyTest` на pure merge срезе | exit0, 7 tests, 8s | `.gradle/m05-evidence/merge-domain.log`; core/domain/build/test-results/test/TEST-ru.eventpass.core.domain.event.DraftMergePolicyTest.xml |
| `:data:remote:compileDebugKotlin :data:local:verification:test :data:local:compileDebugKotlin` до app integration | exit0, 3 настоящих Room tests, 19s | `.gradle/m05-evidence/coordinator-compile.log`; data/local/verification/build/test-results/test/TEST-ru.eventpass.data.local.DraftDatabaseTest.xml |
| Пробный app compile обоих flavors до команды remote-only | failed: недостающий annotation-experimental1.5.0 AAR в offline cache, demo compile не завершён | `.gradle/m05-evidence/app-compile.log`; не считается успешной проверкой |
| Первый remote compile после загрузки зависимости | failed: UI helper оказался внутри функции; область видимости исправлена | `.gradle/m05-evidence/remote-compile.log` |
| `:app:compileRemoteDebugKotlin` итоговый source срез | **exit0, 14s**, 43 tasks (7 executed / 36 up-to-date) | `.gradle/m05-evidence/remote-compile-final.log` |
| `:app:compileRemoteDebugSources` тот же срез | **exit0**, включая Java/Hilt generated classes, без APK | `.gradle/m05-evidence/remote-sources-final.log` |
| Индекс/manifest/посторонние файлы | SHA256 committed blobs и исключённых baseline, git diff whitespace проверяются при собственном коммите | `.gradle/m05-evidence/commit_own.py`, target manifest |

Ранние десять unit-тестов не выдаются за актуальную полную матрицу координатора/VM. Итоговый успех remote compile не равен прохождению lint/behavioral/device tests. В output остались compiler warnings существующих safe calls и redundant exhaustive else, они не являлись ошибками компиляции.

## NOT_RUN / RISKS

- По прямой последней команде пользователя: сквозные crash/recovery/offline/create A→B/conflict/auth/cover/VM сценарии, повторные M03/M04 регрессии, lint, demo compile/isolation, structural architecture script. Их нельзя считать пройденными по компиляции. Ранние 3 Room / 7 merge tests не покрывают эти сочетания.
- Staging/device/emulator/реальное OS kill/SAF/камера/UI не запускались; новые APK/test APK не создавались. Code23 не содержит M04/M05 и не является их приёмочной сборкой.
- Backend без conditional PATCH: GET→PATCH остаётся гонкой; strict multi-device lost-update protection отсутствует. Local revision не server precondition.
- Upload без idempotency: потерянный ответ может оставить orphan asset и потребовать явной повторной загрузки; server DELETE asset не выдуман. Expired unknown create требует решения пользователя после проверки серверных drafts; автоматического поиска по title нет.
- Любая ошибка durable storage должна оставлять ошибку/Recovery, не подтверждение save; поведение при реальном повреждении DB/полном диске и logout cleanup failure не воспроизводилось.

## HANDOFF / РУЧНОЙ CHECKLIST ПОСЛЕ M09

Следующая самостоятельная задача — отдельное последовательное ревью M05 на точном target manifest, с отмеченными NOT_RUN; M06 не начинать до gate. Не повторять M04, не считать проверенным по одному compile, не создавать новые APK без команды. Пользовательское принятие M04 и старые H-проверки не закрывают M05 или финальный remote UX.

Будущая ручная проверка на итоговом M09 APK (сейчас **не выполнена**):

1. После проверенного входа отключить сеть, изменить неполный draft, явно сохранить: «Сохранено на устройстве», Back/список/restart сохраняют pending. После cold start без /me данные скрыты, после входа того же account доступны.
2. Восстановить сеть, явно sync: один server event, mapping переживает restart, статус «Сохранено на сервере». Новая правка во время отправки не исчезает.
3. Изменить тот же draft с другого клиента: base/local/remote, accept/keep/merge, null и удаление вопросов/options/блоков; отправка только после осознанного выбора и sync.
4. Pending обложка: save offline/restart, upload→attach, replacement/cancel, повтор после неизвестного upload; прежняя подтверждённая обложка не исчезает при ошибке.
5. Account B не видит A; explicit logout очищает A pending/files, поздний ответ не возвращает их. Auto401 скрывает retained работу, безопасный re-auth восстанавливает.
6. MyEvents local projection отдельно, server counters неизменны; Back save/discard/stay, rotation и background не отправляют мутации самопроизвольно. Published publish/edit/finish требуют сети.
