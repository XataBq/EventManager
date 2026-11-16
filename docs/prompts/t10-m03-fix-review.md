# Повторное ревью исправлений M03

Работай в `F:\ASProjects\MospolytechProject`, одним агентом, без реализации и субагентов. Прочитай AGENTS.md и используй явно разрешённый `$android-code-review` (`.agents/skills/android-code-review/SKILL.md`). Меняй только CODE_REVIEW.md, сохрани предыдущие записи. APK не собирай: пользователь запретил новые APK в этом проходе. Push не выполняй.

Base **3277e90**, target — коммит, добавивший `docs/validation/t10-m03-p2-fixes-manifest.json` (полный hash получи `git log -1 --format=%H -- docs/validation/t10-m03-p2-fixes-manifest.json`). Проверяй зафиксированный diff, Git status и SHA-256 из манифеста; при расхождении явно сузь/останови вердикт. Пользовательский dirty CODE_REVIEW.md и untracked gradle-daemon-jvm.properties принадлежат baseline, не реализации.

Маршрут: README → 08 → 06/T10 → 34/ADR-T10-05/M03 → 35 → `docs/validation/t10-m03-p2-fixes.md` → `docs/architecture/t10-registration-tickets.md` → owners/operations/invariants → код. Исходное CHANGES_REQUESTED — CODE_REVIEW.md:1973. Это повторное ревью двух P2 M03, не ревью M02, не запуск M04 и не человеческая приёмка.

Проверь:

1. Новая команда валидируется по текущим questions/policy. Pending guest/auth после неопределённого исхода повторяется с прежними answers/body/key при добавлении required или удалении отвеченного вопроса. Identity/owner/expiry/cooldown/recovery guards не обходятся; новая форма/новый POST не создаются автоматически.
2. Own-card Found/refresh не добавляет и не заменяет server list items/events/scope/counters. Оба порядка list/card, Back, восстановленная out-of-scope selection при синхронном list, status/now/QR отдельной карточки. Forbidden/NotFound/auth и поздние ответы не оставляют private card/QR. Существующий demo fallback и pull только видимого ресурса сохранены.
3. Обе регрессии реально отличают старый код: RED 12/7 failures сохранён в `.gradle/m03-p2-fix-evidence/red-results`; финальный GREEN — `final-results`. Полный app 90+25, адресные data 9+1, lint 0 errors (21/44 warnings). Не суммируй повторные app cases и прежние 190/3. XML перед фильтрованным прогоном копируй отдельно.

Адресная команда (JBR 21 из Android Studio1, wrapper):

```powershell
.\gradlew.bat :app:testDemoDebugUnitTest --tests '*M03RegistrationStateTest' --tests '*M03TicketsStateTest' --rerun :data:remote:testDebugUnitTest --tests '*RemoteTicketsTest' --rerun :data:fake:test --tests '*M03GuestRegistrationTest' --rerun -I .gradle/m01-verified-google.init.gradle --offline --console=plain --max-workers=2
```

Если времени/среды недостаточно, укажи NOT_RUN; не объявляй старые XML новым прогоном. Финальный формат — VERDICT / SCOPE / FINDINGS / CHECKS / NOT_RUN / REPORT по docs/35. Нулевые находки не закрывают ручные H-пункты, M02 fix-review или M04–M09. Существующие APK code23 исправлений не содержат.
