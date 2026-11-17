# Отдельное последовательное ревью M04

Работай в `F:\ASProjects\MospolytechProject` одним агентом, без субагентов. Используй явно разрешённый `$android-code-review` из `.agents/skills/android-code-review/SKILL.md`. Исходники только читать, менять только CODE_REVIEW.md с сохранением его прежних байтов. Не исправляй находки в ревью, не собирай APK/test APK, не запускай устройство/эмулятор, не делай push. M05 не начинать.

Base `cec5520`. Target — коммит, добавивший `docs/validation/t10-m04-target-manifest.json`: полный SHA получи `git log -1 --format=%H -- docs/validation/t10-m04-target-manifest.json`. Проверяй именно зафиксированный diff и SHA-256 **Git blobs target**, а не случайный более новый worktree. Для первоначально dirty 00/06/34/35 в коммите только своё изменение поверх HEAD; непересекающиеся пользовательские дополнения могут остаться в worktree; CODE_REVIEW.md, пользовательский prompt M04 и gradle-daemon-jvm.properties также не часть реализации. Сначала сверь status/log и manifest; при расхождении сузь область/evidence, не переписывай чужое.

Маршрут: AGENTS → README → 08 → 06/T10 → 00 → 34/M04/ADR-T10-09 → 35 → `docs/validation/t10-m04-implementation.md` → `docs/architecture/t10-editor-covers.md` → 02/03/05/architecture maps → фактический код/consumers. M03 принят пользователем на `cec5520` 17 ноября, отдельное свидетельство `docs/validation/t10-m03-user-review-confirmation.md`; автор реализации не выдавал самопроверку за независимое ревью. M02 fix-review по-прежнему открыт, human T10 после M09.

Проверь рабочий путь draft create/read/changed-only PATCH → publish → edit/finish и связанные guards:

1. Null/incomplete draft без выдуманного Event/revision. Явные nullable preconditions remote, positive local versions fake; стабильные IDs всех 10 question types/options/sections, metadata и paid/free без оплаты. Publication/field validation и default4h/explicit end/manual finish; remote terminal не вычисляется часами Android.
2. POST create: single-flight/double press, прежние body/key при unknown/cooldown/24h, блокировка редактирования, actor/owner check, safe confirmed ID metadata и запрет слепого create после process death без тела. Никакой durable очереди/Room M05.
3. PATCH method/body и omission/null на фактическом HTTP. Unknown PATCH/publish/finish требует успешный read перед следующей записью, не обещает conditional/multi-device защиту. Dirty ввод не заменяется refresh. Forbidden/auth/late result/finally не оставляют private UI.
4. MyEvents: scoped readiness/items/roles/counters, hidden tab не загружается, старый tab не заменяет текущий, details не попадают в summary list. Aggregate demo остаётся совместимым.
5. Cover: bounded managed import/reader, local ID отдельно от server assetId; multipart file → attach/null detach; MIME/size/actor/cancel/storage failure; никаких bearer/redirect к чужому URL. Private temporary release в finally, bounded off-main preview, неизвестный upload/orphan без выдуманного DELETE/retry.
6. Общие MyEvents/EditorRoute, HTTPS slug links, lifecycle visible/RESUMED, configuration/raw input/pending сохранены, Back save/discard/stay. M03 retry при новой анкете и independent own-card/list regressions сохранены. DI/fake/schema6/legacy fixtures не сломаны.

Сохранённые XML/logs — `.gradle/m04-evidence/`, точные команды/counts/ограничения в отчёте. Перед фильтрованным прогоном копируй XML. Выбери адресные проверки по области, не повторяй полный all-modules run и не используй --rerun-tasks. Не выдавай сохранённый прогон за новый. Staging/device/SAF/визуальная приёмка не выполнялись и не закрыты.

Результат по формату docs/35: VERDICT / SCOPE / FINDINGS (только подтверждённые, с минимальными строками и сценарием) / CHECKS / NOT_RUN / REPORT. Нулевые находки не означают human acceptance или production readiness. Автор исправляет находки отдельным реализационным проходом.
