# T10 M02 — публичное мероприятие и анкета

## STATUS

**IMPLEMENTED / REVIEW_PENDING, 16 ноября 2026.** Только M02, без субагентов и без независимого ревью в реализационной задаче. M00/M01 остаются TECH_ACCEPTED, M03–M09 TODO. База M02 `a24da313ea20366bf0da53d25a8492d220b78bbc`; переданный HEAD `8448db9`. Точный кодовый target идентифицируется [манифестом](t10-m02-target-manifest.json) и коммитом реализации, содержащим этот отчёт. Отдельный review prompt укажет полный commit hash после фиксации. Самопроверка не является независимым ревью. Ручная приёмка только после M09.

## SCOPE

Продолжены существующие domain/fake/remote/VM/navigation решения WIP, без переписывания foundation. При старте tracked дерево чистое; единственный untracked `gradle/gradle-daemon-jvm.properties` сохранён побайтно вне коммита. Ветка `codex/t10-api-integration` уже указывала на тот же WIP, выполнено переключение с main без изменения базы. Исходные status/HEAD/XML сохранены в `.gradle/m02-resume-evidence/`.

Доработаны code-point slug bounds/path safety, remote viewer/read guards tests, independent own ticket action, actor/resource denial cleanup, typed question/price/hint UI, editor metadata controls, configuration lifecycle формы, bounded schema6/read storage и большие Unicode формы. Общий стек/MVVM/DI и физическое хранение bearer M01 сохранены. Архивные отчёты/API fixtures и CODE_REVIEW.md не менялись. Guest autoaccount/idempotent submit/EVT tickets/remote editor/Room/scan/dashboard/files/delete — M03–M09; недоступные remote операции не выдают успех.

## REQUIREMENTS

| Критерий | Реализация / владелец | Доказательство |
|---|---|---|
| Slug и internal ID различаются; Unicode/один segment/allowlist | EventSlug/PublicEventReference, PublicEventLinkParser, RemoteEventRepository, NavKey | PublicEventLinkParserTest, PublicEventRestoreTest, RemoteEventRepositoryTest: supplementary/path/literal percent; Internal без network |
| Все 10 типов, metadata, stable IDs/min2/options/code points | Domain questions/validator, EventEditingPolicy, EditorScreen/EventScreen | QuestionFormatsTest, EventEditingPolicyTest, M02QuestionPresentationTest; unsupported submit blocked, historical snapshot и XLSX consumers сохранены |
| Server status/counts/viewer; nullable/malformed безопасны | Remote DTO mapper/publicOnly, data-local session stamp | RemoteEventRepositoryTest: draft/null/unknown/lifecycle/full/errors, validated unverified viewer, private relationships независимы |
| Single-flight/owner/late/queued/401 | RemoteEventRepository + RemoteAuthRepository; visible/RESUMED EventViewModel | RemoteEventRepositoryTest, RefreshOwnersTest, M02EventStateTest; old 401 не отзывает новый login, logout чистит viewer/contact/ticket, denial чистит форму |
| Совместимые ответы сохраняются, принятый submit не теряется | EventViewModel commandGeneration отдельно от questions, stable type/option reconciliation | RefreshOwnersTest, EventViewModelTest.acceptedSubmitSurvivesQuestionRenameDuringLostResponseWindow; old command/finally после actor loss suppressed |
| LIVE/positive capacity/default +4h/no close | Event/EditingPolicy + fake projections/commands + remote mapping | RegistrationRulesTest, EventEditingTest; closeRegistration удалён из порта/UI WIP M02, remote versions null |
| Schema6/UTF-8/legacy/история/без corrupt reset | ProductSnapshotCodec/LegacyProductSnapshotCodec, FakeAuthRepository, bounded AtomicFile adapter | ProductSchema6Test, legacy v1–v5 fixtures, migration/storage suites; >16 MiB форма+historical snapshot; backup до explicit reset |
| Большая валидная wire форма | AuthHttp public limit 32 MiB, auth limit 1 MiB | RemoteEventRepositoryTest: >1 MiB ASCII и >16 MiB escaped Unicode; лимит не основан на длине UTF-16 |
| Staging соответствует контракту | HTTPS OpenAPI read и isolated synthetic fixture | OpenAPI structural equality 1.1.1; StagingPublicEventSmokeTest 1 passed, cleanup successful |

## ARCHITECTURE / MIGRATION

Обновлены текущие [02](../02-architecture.md), [03](../03-navigation.md), [05](../05-data-contracts.md), [карта M02](../architecture/t10-public-event.md), ownership/operations/responsibilities/invariants и [mapping](../api/migration-mapping.md). ADR-T10-01–05 применены к public-read срезу; ADR-06/07 остаются будущими M05/M08, не выполненными автоматически. Fake localSchemaRevision/revision не являются server formVersion/If-Match. Command generation не меняется от questions refresh, но меняется при настоящем уходе/смене actor/resource/denial. Configuration dispose не очищает незавершённую форму той же entry VM; новый процесс не обещает восстановление ответов.

Schema6 ограничена 32 MiB, строка 200000 UTF-8 bytes; legacy reader v1–v5 сохраняет прежние 8 MiB и формат. Новые registrations сохраняют questionSnapshot, старые неизвестные labels/checker/time не выдумываются. Совместимые старые IDs/QR/manual/Used/first records/profile сохраняются. Известный несовместимый legacy требует явного reset после успешного atomic backup оригинала. Corrupt/truncated/unknown/oversized/trailing не являются разрешением автоматического reset. Все writers общий codec, без guest cache и persisted transient session/outbox. Room в этом M не вводится.

## CHECKS

Полный baseline текущего WIP повторён: exit 0, 3m22s, 325 tasks; 267 JVM invocations, 265 passed / 2 skipped, без failures/errors. Четыре ошибки из старого handoff не воспроизвелись. Это новый прогон сохранённого WIP, не старые результаты из handoff.

Финальная команда (PowerShell, JBR21 Android Studio1):

```powershell
.\gradlew.bat :core:domain:test :data:fake:test :data:remote:testDebugUnitTest :core:platform:testDebugUnitTest :app:testDemoDebugUnitTest :app:testRemoteDebugUnitTest :app:assembleDemoDebug :app:assembleRemoteDebug :app:assembleDemoDebugAndroidTest :app:assembleRemoteDebugAndroidTest :app:lintDemoDebug :app:lintRemoteDebug :app:verifyDemoIsolation -I .gradle/m01-verified-google.init.gradle -I .gradle/t06-evidence/repeat-tests.init.gradle --offline --console=plain --max-workers=2
```

Локальный verified Google Maven init только для уже проверенного androidx.tracing:tracing:1.0.0; без изменения TLS/зависимостей. Repeat init отключает up-to-date только для Test tasks. Это машинные ignored helpers, не новые требования проекта. Финальный exit 0: **273 passed / 2 skipped / 0 failures/errors** (275 invocations): domain 28, fake 101, remote transport 28 (26 passed/2 skipped), platform 15, app demo 78, app remote 25. Обе сборки и test APK, Hilt/DI, lint и isolation прошли: **1m50s, 325 tasks (53 executed / 272 up-to-date)**. Lint: demo: {'Warning': 21}; remote: {'Warning': 44}; ошибки отсутствуют, warnings — version notices и UnusedResources оставшихся/будущих flavor surfaces. XML/лог окончательного совместного прогона — `verified-results/`, `verified-summary.json`, `final-lifecycle-build.log`; предыдущий green до последней lifecycle правки — `final-results/`, `final-build.log` (1m32s).

Staging: `https://staging.eventpass.dikanskii.ru/api/openapi.json`, версия 1.1.1 структурно равна `docs/api/backend-openapi-1.1.1.json`; raw SHA-256 `9e5e31730341979c2bad95dd69fb6c37386d69bf6e2fff89d1732c44137f0d2a`. Затем `$env:EVENTPASS_STAGING_SMOKE='1'; .\gradlew.bat :data:remote:testDebugUnitTest --tests 'ru.eventpass.data.remote.StagingPublicEventSmokeTest' ...`: exit 0, **1 test passed, 0 skipped**, временный собственный аккаунт/event, finally DELETE /me и logout. Без пользовательских credentials/PII в логах. Smoke повторён на окончательном transport коде: exit0, 6s, 17 tasks, 1 passed/0 skipped; `final-staging-smoke.log`/`final-staging-results`. M01 staging auth suite в финальном local прогоне skipped и отдельно не запускалась.

API33 Pixel7 AVD `EventPass_Insets_API33`, serial emulator-5584, three-button: **4 tests passed**. Demo M02QuestionPresentationTest + M02EventRecreationTest — OK (2), 14.944s (`demo-device-retry.log`); remote RemoteRootInsetsTest + RemoteIntentBoundaryTest — OK (2), 6.884s (`final-remote-device.log`). Проверены все десять renderer formats/metadata/typed multiple, paid metadata, реальное same-entry Activity recreation с сохранением contact/consent, safe system-bar content и sanitization ignored secret URI. Remote GET/Unicode slug restore проверены transport/JVM, не полным device E2E на staging. Перед этим новый lifecycle harness имел 2 failures одного testcase: RESUMED при recreate и DESTROYED при teardown, last state PAUSED; assertions сохранности не достигнуты. Доставка ссылки заменена на прежний Activity-owned coordinator, assertions не удалены. Тесты повторно собраны и lint обеих веток прошёл (exit0, 16s), app APK не менялся после joint build.

- [demo APK 0.10.2-m02-demo, code22](../../artifacts/apk/eventpass-0.10.2-m02-demo-code22.apk): **45034746 bytes**, SHA-256 `2e93473344d0a4322e12843b507d2fc9b816a052f64f34a99d527e5333ffbe34`.
- [remote APK 0.10.2-m02-remote, code22](../../artifacts/apk/eventpass-0.10.2-m02-remote-code22.apk): **45811829 bytes**, SHA-256 `8f5d07a7278ee318d046bce3480e2a0e273f7bd310bf8618175b4949434935a1`.

MinSdk28/target37, debug signature/metadata проверены; APK не является human/production acceptance. Старые code20/21 не перезаписываются.

Evidence `.gradle/m02-resume-evidence/`: before-results (старые outputs), baseline-results/summary + baseline-build.log, iteration1-results с неуспешным новым тестом, staging-results/smoke.log, final-results/summary + final-build.log, device logs, lint и metadata. XML копировались до фильтрованного staging прогона; skipped не прибавлены к passed. Новый late-401 test сначала использовал virtual timeout для реального HTTP, исправлен real-clock guard; этот harness failure не является RED продукта. Затем сценарий усилен: старый public request действительно получает поздний 401 после нового login, без cancellation коллектора. Случайные sandbox/setup/automatic-review timeout/usage ошибки не выдаются за ошибки приложения; после проверки/уточнения разрешений команды выполнены.

## NOT_RUN / RISKS

Независимое ревью ещё не выполнено. Ручной приёмки/физического устройства/полного TalkBack/всех orientations/fontScale/providers/нового OS-process-death нет; activity recreation не называется process death. Новые API не объявляются production-ready. В remote пока недоступны M03+ операции: форма отображается и валидируется, submit возвращает честный FeatureUnavailable; own ticket ID уже берётся из viewer, ticket UI — M03. Полный guest capability/server answer encoding/retry protocol остаётся M03. Старые demo guest/manual/resend пути документированы как остаток следующих этапов. Формат server API без formVersion/precondition не позволяет обещать атомарную защиту схемы/двух редакторов. App Links отложены, consent URL/test mail и финальные сценарии остаются общей T10 приёмке.

## HANDOFF

Следующий шаг — **отдельное последовательное ревью M02** через android-code-review по base `a24da31` и точному implementation target из нового промпта; только CODE_REVIEW.md, без субагентов/правок исходников. После findings — разрешённый отдельный fix pass и повторное ревью. M03 начинать только после TECH_ACCEPTED M02. Не считать этот отчёт, самопроверку, staging smoke или debug APK человеческой приёмкой/независимым ревью.
