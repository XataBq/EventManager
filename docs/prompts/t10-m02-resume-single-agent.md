# Продолжить M02 одним агентом

Работай в `F:\ASProjects\MospolytechProject`, ветка `codex/t10-api-integration`. Заверши **только M02 T10 — публичное мероприятие и модель анкеты** из `docs/34-api-migration-plan.md`, начиная с переданного WIP. **Субагенты запрещены**: не делегируй реализацию, исследование, проверки или ревью. Пользователь 15 ноября отменил прежнее разрешение на них. Новый самостоятельный агент продолжает через этот промпт; не переписывай приложение с нуля.

Сначала прочитай AGENTS.md → README.md → актуальные решения `docs/08-decisions.md` → статус T10 в `docs/06-delivery-plan.md` → M02/ADR в 34 → `docs/35-t10-execution.md` → `docs/validation/t10-m02-handoff-2026-11-15.md`. Далее `docs/14-engineering-rules.md`, `docs/api/migration-mapping.md`, backend OpenAPI 1.1.1 и только относящиеся разделы 02/03/05/architecture. Проверь текущий Git/status/log. База M02 — `a24da313ea20366bf0da53d25a8492d220b78bbc`; target передачи — коммит, содержащий этот промпт и handoff. Зафиксируй точный HEAD и исходный dirty baseline. M00/M01 TECH_ACCEPTED, M02 WIP_HANDOFF, M03–M09 TODO. Последнее принятое исправление M01 `4091c97`, повторное ревью в CODE_REVIEW.md:1912.

В переданном коде уже частично сделаны EventSlug/PublicEventReference, LIVE/effective end/capacity, типизированные вопросы, public remote GET и viewer/session guards, parser/navigation, адаптация редактора/renderer/fake/schema6/UTF-8 и тестов. Изучи реальный diff `a24da31..HEAD`, оцени незавершённость и продолжи существующие решения. Целевые ADR не выдавай за уже доказанные свойства кода.

Последний сохранённый общий прогон был красным: 4/72 demo app теста в RefreshOwnersTest и EditorViewModelTest. После него были правки без итоговой проверки. Прежние domain/fake/remote результаты не доказывают текущий HEAD. Начни с повторного воспроизведения проверок; не удаляй assertions ради зелёного результата. Особое внимание:

- Unicode slug/IDs, кодирование path segment, allowlist публичных ссылок; публичный slug и внутренний eventId не взаимозаменяемы. App Links не подключать.
- Все 10 форматов вопросов/ответов, help/placeholder/hints, stable IDs, min2 options, code-point limits и большие валидные анкеты; unknown type/nullable draft/malformed response не должны создавать фиктивное published событие.
- Совместимые ответы переживают обновление схемы; несовместимые изменения объясняются. Submit, уже принятый сервером, не должен терять билет из-за смены локальной revision. Backend formVersion/precondition отсутствует. Чужие session/event/entry ответы по-прежнему отбрасываются.
- Server viewer/counts/status определяют доступность; один аккаунт, независимые права на конкретное событие. Logout/401/смена владельца очищают приватные данные, late/queued responses не возвращают их. ViewModel/Compose не знают DTO, токенов и fake/remote.
- Видимый public resource управляет чтением и pull-to-refresh; single-flight и отмена не обновляют соседнюю entry. Не вводить фоновый polling вместо lifecycle.
- Fake schema6: bounded UTF-8, v1–v5 fixtures, исторические snapshots ответов, явное архивирование/reset только известных несовместимых legacy данных; corrupt storage не перезаписывать. Старые guest QR/first check-in proofs сохранить согласно политике миграции.
- LIVE регистрация разрешена; published capacity положительная; default end start + 4 часа, server effective end; close-registration операция удалена. Не возвращать старые запреты unverified/guest autoaccount и ticket resend. Guest QR/capability только saved state текущего flow; ответы перед входом не сохранять/не autosubmit.

Разрешены необходимые исправления кода/тестов/документации в пределах M02. Сохраняй MVVM, DI, текущий стек и дизайн-систему. Не реализуй M03–M09, не подключай ещё недоступные операции притворными успешными результатами и не меняй продуктовые соглашения без уточнения. Обнови затронутые фактические страницы 02/03/05/architecture и миграционную матрицу; одной заметки в отчёте недостаточно.

Минимальный совместный прогон из PowerShell после согласованных правок (JAVA_HOME выбрать по проекту, локальный JBR21 описан в handoff):

```powershell
.\gradlew.bat :core:domain:test :data:fake:test :data:remote:testDebugUnitTest :core:platform:testDebugUnitTest :app:testDemoDebugUnitTest :app:testRemoteDebugUnitTest :app:assembleDemoDebug :app:assembleRemoteDebug :app:assembleDemoDebugAndroidTest :app:assembleRemoteDebugAndroidTest :app:lintDemoDebug :app:lintRemoteDebug :app:verifyDemoIsolation --console=plain
python scripts/verify-architecture-docs.py
git diff --check
```

Проверь доступные transport/regression tests, в том числе валидную анкету больше 1 MiB и поздний logout/401. Сохрани отдельную копию XML/log до фильтрованных повторов; указывай точные команды, exit codes и число passed/failed/skipped. Если доступен staging, сначала проверь drift OpenAPI, затем опциональный `StagingPublicEventSmokeTest` с временными собственными fixtures и cleanup; без внешнего доступа явно NOT_RUN, без выдуманных credentials и без ослабления TLS. Не добавляй секреты/PII в логи. Device-проверки добавляй/запускай только для значимых новых lifecycle/parser/state инвариантов, без полного автоматического прокликивания UI. Ручную приёмку у пользователя между M не запрашивать — она только в конце M09.

В конце создай `docs/validation/t10-m02-implementation.md` по формату 35: scope/base/target, требования→код→доказательства, архитектурные изменения/ADR, миграции, проверки/NOT_RUN, риски, следующий шаг. Обнови README/06/08/34/35 и карту чтения фактическим статусом. При выполненных критериях — **REVIEW_PENDING**, не TECH_ACCEPTED. Подготовь отдельный последовательный запрос ревью M02 по локальному `$android-code-review` с точными base/target; не запускай ревью субагентом, не меняй CODE_REVIEW.md в реализационной задаче и не называй самопроверку независимым ревью. M03 начинается только после отдельного ревью и закрытия находок. Коммит реализации допустим в пределах задачи; push без новой команды пользователя не выполнять.
