# T10 M04 — online редактор, drafts, MyEvents и обложки

## STATUS / SCOPE

**IMPLEMENTED / REVIEW_PENDING, 17 ноября 2026.** Base `cec5520`, ветка `codex/t10-api-integration`. Target — локальный коммит с этим отчётом и [SHA-256 manifest](t10-m04-target-manifest.json). Выполнен одним агентом, без субагентов, push и новых APK. M05–M09 не запускались. Требуется отдельное последовательное ревью M04, не авторская TECH_ACCEPTED.

Gate M03: пользователь подтвердил повторное ревью `cec5520` без замечаний и поручил продолжать M04; [свидетельство](t10-m03-user-review-confirmation.md). CODE_REVIEW.md автором не дописывался. Команды/объём пользовательского ревью неизвестны, автоматический вердикт ему не приписывается. M02 fix-review остаётся исторически открытым, все H/UI-пункты T10 — после M09.

Исходный dirty baseline: CODE_REVIEW.md, docs/00-documentation-map.md, 06-delivery-plan.md, 34-api-migration-plan.md, 35-t10-execution.md; untracked пользовательский `docs/prompts/t10-m04-editor-covers.md` и `gradle/gradle-daemon-jvm.properties`. Копии до работы — `.gradle/m04-evidence/baseline`, status — initial-status.txt. CODE_REVIEW/Gradle settings/prompt не включаются в коммит. Для четырёх dirty документов подготовлены отдельные версии собственного изменения **поверх HEAD**, без исходных пользовательских дополнений; непересекающиеся пользовательские дополнения остаются в worktree, связанные статусные абзацы актуализированы по задаче. Manifest описывает committed blobs, а не эти четыре дополненных worktree файла.

## REQUIREMENTS → IMPLEMENTATION → EVIDENCE

| Критерий M04 | Код/владелец | Проверка |
|---|---|---|
| Неполный draft, nullable поля, отсутствие выдуманной server revision | EventDraft.revision nullable, owner DTO → draft fields; EventRepository expected versions nullable; Fake сохраняет positive local versions | RemoteOrganizerRepositoryTest incomplete create/read/PATCH; EventEditingPolicyTest, EventEditingTest, ProductSchema6Test/TicketPersistenceTest |
| Create/read/явный save, тот же server ID после успеха/recreation | RemoteOrganizerRepository + EditorViewModel; transient local→server mapping; только подтверждённый ID в saved entry metadata | M04EditorStateTest confirmedServerDraftIdentityRestoresReadWithoutStartingAnotherCreate |
| Один create key/body, unknown/cooldown/24h, double press/actor/owner | VM pending fields/marker/single-flight; adapter bounded fingerprint/key/context/retention и retryAt, HTTP retry отключён | lostCreateResponseReplaysSameKeyBodyAndDoesNotCreateForChangedInput; uncertainCreateBlocksEditingAndDoublePressThenReplaysOriginalFields; restoredUnknownCreateWithoutBodyNeverSubmitsAgain; lateCreateAfterLogoutCannotPublishPrivateDraft |
| PATCH body, omission/null, typed field errors | AuthHttp сохраняет body при explicit method; changed-only encodeFields; location→venue/cover field mapping | incompleteDraftCreateReadAndPatchKeepNullsAndOmissionDistinct; explicitNullIsEncodedWhileUnchangedFieldsAreOmittedAndIdsStayStable; localStorageLimitsReturnFieldValidationWithoutSendingInvalidDraft; deleteWithoutBodyStillKeepsItsTransportContract |
| Publish/finish, default4h/manual finish, server terminal authority | Existing server ID → POST publish/finish; remote Published.terminal задан server status, VM не пересчитывает его часами Android | publishAndFinishUseSameServerIdAndPreserveDefaultEndAndManualFinish; serverPublishedStateIsNotReclassifiedByDeviceClock |
| Unknown PATCH не replay вслепую, dirty input сохранён | Per-resource uncertain marker до успешного owner GET; отдельные read/command generations | uncertainPatchRequiresExplicitReadBeforeAnotherMutation; refreshedNullRevisionDraftCannotReplaceDirtyInputAcrossPauseResume; прежние EditorViewModelTest/RefreshOwnersTest |
| 10 типов questions, stable options/sections IDs, metadata, free/paid | DraftQuestion ↔ API Question; encodeFields, общие EditorScreen controls | allTenQuestionTypesAndStableOptionSectionIdsSurviveDraftRoundTrip; существующие domain/fake suites |
| Per-tab summary/readiness/items/roles/counters, без fake full Event и hidden reads | MyEventsTab/MyEventSummary; scoped observe/refresh; MyEvents VM/общий screen | scopedDraftListRetainsNullableSummaryCountersAndDoesNotReadHiddenTabs; M04MyEventsStateTest; RefreshOwnersTest scoped spy адаптирован без ослабления assertions |
| Local import → multipart file → server assetId → attach/null detach | AndroidEventCoverStore/CoverAssetReader + RemoteCoverTransfer; VM не знает HTTP/DTO | RemoteCoverTransferTest upload/server ID, private file missing/guest, MIME/size/redirect, cancel/late actor/release; PATCH null tests |
| Bounded off-main preview, no bearer на чужой URL | API-origin asset GET с MIME/size guards; sampled/EXIF decoder общий для local/remote; arbitrary returned URL не используется | Local MockWebServer tests подтверждают endpoint/auth/redirect; compile/lint проверяют Android decoder. Фактические bitmap/EXIF/SAF на устройстве NOT_RUN |
| Общие routes, HTTPS slug links, lifecycle/Back/PII cleanup | Remote host открывает MyEventsRoute/EditorRoute; read visible+RESUMED, Back save/discard/stay, configuration VM/pending сохраняются | M04 VM tests, прежние Editor/Refresh owners; device/UI отдельная финальная приёмка |
| M03 и snapshot совместимы | Existing Event/Tickets owners не переписаны; remote actions остаются за ports; fake codec/legacy fixtures require positive local revisions, schema6 не повышалась | Обе M03 P2-регрессии, RemoteTicketsTest/RemoteEventRepositoryTest; fake editor/persistence/schema6/guest tests |

## ARCHITECTURE / PORTS / CLEANUP

Фактическая спецификация — [M04](../architecture/t10-editor-covers.md), актуализированы 02/03/05, responsibilities/state-ownership/operations/invariants, 00/06/08/34/35/README и API mapping. ADR-T10-09 фиксирует online owner projection, nullable local preconditions и pending ownership. Новых библиотек/модулей/Room/WorkManager нет.

RemoteEventRepository делегирует owner операции RemoteOrganizerRepository; DI остаётся flavor-aware, unavailable M05–M09 не заменены fake успехом. Server summary не получает фиктивный owner/Event. Мутации, права/места и окончательная validation — серверные. Shared VM получает EventEditorResult.Published.terminal, а не выбирает fake/remote реализацию.

Pending create body живёт только у текущего Editor VM; adapter хранит SHA-256/key/24h/context/retryAt (не более 32 pending). SavedState entry хранит marker/local ID/подтверждённый server ID без тела/ответов/bearer. Нет обещания exactly-once после process loss без тела: новый POST блокируется, пользователь перечитывает drafts. После успеха процесс может восстановить подтверждённый server ID.

PATCH сравнивает поля с подтверждённым baseline и сохраняет null отдельно от omission. Unknown PATCH/publish/finish требует read reconciliation; dirty input при этом остаётся в VM. API не имеет conditional write: строгая multi-device lost-update защита не заявлена. Полноценный конфликтный merge/durable sync — M05.

Мутации/read checks привязаны к captured context, result/finally — к owner/generation. Logout/403/401 очищают private UI/cache, поздний upload не прикрепляется к другому actor/event. Managed local upload source освобождается в finally, в том числе cancel. Новый GET preview использует только assetId через API origin, не произвольный URL. Кодек fake по-прежнему v6: nullable remote revision не сериализуется как вымышленное значение; remote upload source не записывается в product snapshot.

## CHECKS

PowerShell, JBR 21 `C:\Program Files\Android\Android Studio1\jbr`, repository Gradle wrapper. Общие аргументы: `-I .gradle/m01-verified-google.init.gradle --offline --console=plain --max-workers=1`. Init — прежний локальный workaround, не новая зависимость; TLS/стек не менялись. Не использовались --rerun/--rerun-tasks или all-modules test. Перед фильтрованными прогонами XML сохранены отдельно; старые M03 evidence не выданы за новые.

Первый рабочий срез: `:data:remote:testDebugUnitTest --tests '*RemoteOrganizerRepositoryTest' :app:compileDemoDebugKotlin :app:compileRemoteDebugKotlin` — exit0, 1m02s, 2 теста. Лог `first-slice.log`. Это промежуточный код, не финальный результат.

Совместная команда:

```powershell
.\gradlew.bat :app:compileDemoDebugKotlin :app:compileRemoteDebugKotlin :data:remote:testDebugUnitTest --tests '*RemoteOrganizerRepositoryTest' --tests '*RemoteCoverTransferTest' --tests '*RemoteTicketsTest' --tests '*RemoteEventRepositoryTest' :app:testDemoDebugUnitTest --tests '*M04*Test' --tests '*EditorViewModelTest' --tests '*RefreshOwnersTest' --tests '*M03*Test' :core:domain:test --tests '*EventEditingPolicyTest' :data:fake:test --tests '*EventEditingTest' --tests '*TicketPersistenceTest' --tests '*ProductSchema6Test' --tests '*M03GuestRegistrationTest' :app:lintDemoDebug :app:lintRemoteDebug :app:verifyDemoIsolation -I .gradle/m01-verified-google.init.gradle --offline --console=plain --max-workers=1
```

- `final-verification.log`: exit0, 3m43s, 197 задач / 85 executed. **106 passed**, 0 failures/errors/skipped: remote 37, app 30, domain 6, fake 33. XML `joint-results`, counts `joint-summary.json`.
- После server-terminal regression и удаления новых VisibleForTests warnings та же релевантная команда: `final-delta-verification.log`, exit0, 3m03s, 197 задач / 64 executed. **107 passed**, 0 failures/errors/skipped: remote 38, app 30, domain 6, fake 33. XML `final-joint-results`, counts `final-joint-summary.json`. Обе app compile и lint прошли; fake отсутствует в main/remote runtime. Lint **0 errors / 21 demo и 44 remote warnings**; подавлений не добавлено.
- Последняя адресная field-validation regression: `:data:remote:testDebugUnitTest --tests '*RemoteOrganizerRepositoryTest' :app:compileDemoDebugKotlin :app:compileRemoteDebugKotlin` — exit0, 35s, 51 задача / 6 executed, **11 passed**, без failures/errors/skipped. `final-field-check.log`, `final-field-results`. App compile здесь инкрементально UP-TO-DATE, не объявляется новой полной компиляцией. Уникальных JVM cases в совокупности **108**, а не сумма повторных 106/107/11.
- Финальная проверка общего sampled/EXIF decoder — `:app:compileDemoDebugKotlin :app:compileRemoteDebugKotlin :app:lintDemoDebug :app:lintRemoteDebug`: exit0, 2m20s, 172 задачи / 46 executed; `final-preview-check.log`. Android pixel/EXIF correctness на устройстве этим не доказывается.
- Окончательный diff (consumer-edge session stamp guards, UI counters, общий sampled/EXIF decoder) проверен `:app:compileDemoDebugKotlin :app:compileRemoteDebugKotlin :data:remote:testDebugUnitTest --tests '*RemoteOrganizerRepositoryTest' --tests '*RemoteCoverTransferTest' :app:testDemoDebugUnitTest --tests '*M04*Test' --tests '*EditorViewModelTest' --tests '*RefreshOwnersTest' --tests '*M03*Test' :app:lintDemoDebug :app:lintRemoteDebug :app:verifyDemoIsolation` с общими аргументами: **exit0, 2m29s, 193 задачи / 62 executed, 16 remote + 30 app passed**, без ошибок/пропусков. Лог `final-acceptance-check.log`, XML `acceptance-results`, `acceptance-summary.json` подтверждает **108 уникальных cases** вместе с неизменившимися suites предыдущего совместного прогона. Lint снова **0 / 21 и 44 warnings**, isolation успешен.
- `python scripts/verify-architecture-docs.py` и `git diff --check`: оба exit0: 19 текущих страниц, 382 local links, 9 Mermaid blocks, 18 mobile IDs и S01–S14/test references; `documentation-checks.log`. Mermaid rendering/семантика Kotlin этим скриптом не проверяются.

Промежуточные failures не скрыты: неверный contains для ConcurrentHashMap исправлен на containsKey; два Windows file-lock failure для JAR устранены остановкой Gradle daemon и max-workers=1 без удаления проекта; исправлено имя UI state preview; старый RefreshOwners spy адаптирован к scoped overload. Логи сохранены в `addressed-compile-attempt.log`, `addressed-windows-lock*.log`, `addressed-ui-compile-attempt.log`, iteration-results. Неудачный прогон app был 27/28 из-за spy, production поведение не ослаблено.

## NOT_RUN / RISKS

- **Новые APK/test APK, assemble, install, device/emulator не запускались по заданию.** Существующий code23 не содержит M04 и исправлений M03; версии/артефакты не обновлялись.
- Live staging/drift/OpenAPI fetch, реальный upload/photo/публикация по сети не запускались. Использован предоставленный snapshot backend 1.1.1 и локальный MockWebServer. Новая актуальность staging не заявлена.
- Android bitmap/EXIF/camera/SAF/поворот/OS process kill/TalkBack/fontScale/Figma visual не выполнялись; память/SavedState/lifecycle guards проверены JVM-сценариями, не новым OS kill. Все H-пункты остаются за человеком после M09.
- Полные all-modules/app-remote/data/fake suites не запускались: только перечисленные релевантные suites плюс компиляция/lint обоих вариантов. 21/44 предупреждения lint остаются, зависимости ради них не обновлялись.
- Нет conditional PATCH и durable unknown-body recovery; orphan uploaded asset при потерянном ответе возможен, DELETE отсутствует. После неизвестного create без тела необходимо сверить drafts вручную; поиск по title/автоматический второй POST отсутствуют. Room/pending/merge/offline — M05.
- Это IMPLEMENTED/REVIEW_PENDING, не независимое ревью и не production/human acceptance. Следующий M не запущен.

## HANDOFF / РУЧНЫЕ СЦЕНАРИИ ПОСЛЕ M09

Следующая самостоятельная задача — [отдельное ревью M04](../prompts/t10-m04-review.md) на точном commit/manifest; менять только CODE_REVIEW.md. После успешного gate можно M05. Не считать старый APK проверкой нового кода, не повторять foundation M01–M03, не закрывать M02/H-пункты.

На будущей итоговой сборке человек проверяет:

1. MyEvents: organizing/volunteering/drafts, загрузка/пустой/error, возврат в прежний tab, pull только текущей поверхности; item/counters совпадают с сервером.
2. Новый пустой draft → явное сохранение → Back → повторное открытие; nullable даты/capacity не подставлены. Invalid raw дата/число дают field error, refresh не стирает dirty.
3. Программа и все question types/metadata/options сохраняют IDs после save/reopen; публикация неполного draft показывает validation, исправленный draft публикуется с тем же ID. Free/paid — только metadata.
4. Create unknown → повтор без изменения данных; нет второй отправки по double press. После перезапуска неизвестной команды предлагается сверка drafts, подтверждённый ID восстанавливается без нового create.
5. PNG/JPEG, в том числе EXIF-ориентации, replace/remove/save/reopen cover; слишком большой/невалидный файл, отказ/storage/network/cancel/смена аккаунта не дают ложного сохранения или чужого изображения.
6. Edit опубликованного → end=null/default4h и explicit end → finish → terminal UI; поздний ответ/403/выход не возвращают private данные. Copy/share/open — HTTPS со slug, Back — прежний editor/list.

Ни один пункт этого checklist агентом не отмечен пройденным.
