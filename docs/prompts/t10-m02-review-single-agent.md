# Отдельное последовательное ревью T10 M02

Работай в `F:\ASProjects\MospolytechProject`, ветка `codex/t10-api-integration`. Выполни только ревью M02 через локальный `$android-code-review` (`.agents/skills/android-code-review/SKILL.md`). Не запускай субагентов, помощников или параллельную реализацию. Исходники только для чтения; изменяй только CODE_REVIEW.md с сохранением всех прежних записей. Исправления и переход к M03 не входят в эту задачу.

- **BASE:** `a24da313ea20366bf0da53d25a8492d220b78bbc` — начало M02 после принятой M01.
- **TARGET:** `c44dab9717da6a9cca4d483c17ce281a3721d20a` — проверенная реализация M02, включая продолжение WIP `8448db9`.
- Следующий коммит с этим промптом — metadata handoff; код не меняет. Перед началом сверить Git status/log и diff target..HEAD. Не включать пользовательский untracked `gradle/gradle-daemon-jvm.properties` в отчёт как изменение приложения.
- Отчёт: `docs/validation/t10-m02-implementation.md`; точные code/test blobs: `docs/validation/t10-m02-target-manifest.json`; фактическая архитектура: `docs/architecture/t10-public-event.md`, актуальные 02/03/05/ownership/operations/invariants; mapping: `docs/api/migration-mapping.md`.

Маршрут: AGENTS → README → актуальные 08 → 06/T10 → M02/ADR в 34 → 35 → implementation report → относящиеся исходники/тесты и OpenAPI 1.1.1. Решения 13 ноября заменяют ранние demo-правила скилла: unverified login допустим, LIVE регистрация разрешена, вопросы редактируемы, published capacity положительная. Remote guest autoaccount/submit/tickets, EVT code, удаление resend и остальные M03–M09 пока недоступны; это не дефект M02 при честном FeatureUnavailable. App Links отложены.

Проверить diff base..target и связанные пути, не ограничиваться новыми строками или self-report:

1. Slug/internal ID/Unicode code points/однократное path encoding и allowlist, publicSlug restore без raw URI/token в saved state. Internal remote reference не должен стать slug GET.
2. Все 10 Question/Answer форматов, metadata/stable IDs/min2 options, большой valid JSON (>1 MiB и escaped Unicode >16 MiB), unknown/nullable/malformed safety. Порты/VM/Compose без DTO/bearer/fake implementation.
3. Server status/counts/viewer, independent owner/grant/own-ticket и unverified actor; logout/401/current token vs late old 401, queued private state, consumer-edge sanitize/cache release. Single-flight только видимого resource/owner, без hidden polling.
4. Compatible-answer reconciliation; accepted submit после rename/type/options refresh не теряет receipt/ticket. Actor/resource/denial/exit отменяют старый result/finally, configuration recreation той же entry сохраняет форму/command owner. No autosubmit/анкета перед login не persist.
5. Schema6 bounded UTF-8/read/write, v1–v5 fixtures, historical questionSnapshot, QR/manual/Used first-check proofs. Явный reset только известного несовместимого legacy после успешного backup; corrupt/truncated/oversized/unknown не перезаписывать.
6. Остальные consumers расширенных Event/Question: editor, fake, XLSX, renderer, nullable fake-only revisions, effective end +4h/manual finish/LIVE/capacity/no close. Не приписывать API отсутствующие formVersion/If-Match или production готовность.

Исполнитель выполнил полный joint build: 273 JVM passed / 2 skipped; отдельно final public staging smoke 1 passed с cleanup; 4 API33 device tests прошли. Это evidence для проверки, не готовый verdict. Текущие remote XML могли быть заменены filtered staging: полные копии в `.gradle/m02-resume-evidence/verified-results`, summary/log в том же каталоге. Сохранить свои XML до фильтрованных повторов; skipped не считать passed. Машинные verified-google/repeat-tests init scripts описаны в отчёте; отсутствие их на другом checkout не является дефектом приложения. Не обновлять стек и не ослаблять TLS.

Запустить минимальные значимые проверки по находкам/рискам; команды и NOT_RUN перечислить точно. Staging writes не нужны автоматически для ревью: при запуске только отдельные собственные synthetic fixtures и cleanup, без пользовательских credentials/PII. Ручная приёмка только после M09, человеком; сейчас её не запрашивать и не закрывать.

Добавить в CODE_REVIEW.md дату, base/target, scope, подтверждённые P0–P3 с условием/последствием/строкой/evidence, вопросы отдельно, проверки/NOT_RUN/ограничения. Итог: VERDICT (NO_CONFIRMED_FINDINGS / CHANGES_REQUIRED / INCOMPLETE_EVIDENCE), SCOPE, FINDINGS, CHECKS, NOT_RUN, REPORT. Если подтверждённых дефектов нет — прямо написать и назвать пределы. Не называть реализацию принятой только из-за отсутствия находок; исходники не исправлять во время ревью. Push не выполнять.
