# T10 M01 — исправление legacy email lookup

## STATUS / BASE / SCOPE

**REVIEW_PENDING / FROZEN**, 14 ноября 2026. Исправление единственной P2 из CODE_REVIEW.md:1870, ревью коммита `918f78eb705018d04381a754b9ab112f83dee575`. Root назначает независимое повторное ревью; этот отчёт не отмечает техническую или ручную приёмку завершённой.

Изменены только:

- `data/fake/src/main/kotlin/ru/eventpass/data/fake/product/FakeAuthRepository.kt` — общий normalized lookup и uniqueness.
- `data/fake/src/test/kotlin/ru/eventpass/data/fake/product/FakeLegacyEmailRecoveryTest.kt` — три новые регрессии на независимых legacy writers.
- Этот отчёт. Нет изменений app/domain/remote/platform/Gradle, CODE_REVIEW.md и журнала 35; commit/push не выполнялись.

Исходник до исправления и его hash сохранены в `.gradle/m01-fix-email-evidence/FakeAuthRepository.before.kt` / `before.json`. SHA-256 замороженных source/test — `scope-sha256.json` в той же папке. Ранее dirty документация сохранена вне scope.

## REQUIREMENTS → CHANGE → EVIDENCE

| Требование | Изменение | Доказательство |
|---|---|---|
| Mixed-case legacy email остаётся доступным | Единая функция trim + lowercase(Locale.ROOT) применяется к вводу и сохранённому email только при сравнении | mixedCaseLegacyProductSupportsLoginAndRecoveryWithoutRewritingIdentity; mixedCaseStandaloneLegacyAuthSupportsColdLoginAndRejectsDuplicateSignup |
| Login/recovery/signup совместимы | matchingAccounts используется всеми тремя путями; signup отклоняет любой normalized match | Три case-variants login/signup и verify/reset dispatch по сохранённому оригинальному адресу |
| Не переписать identity и связи | Сохранённый Account/email не нормализуется миграцией; session возвращает исходный Account | Assertions accounts/proofs/names/registrations/tickets/grants равны исходным, включая cold restart и собственный ticket |
| Не слить legacy case-collisions | Login разрешён только при ровно одном normalized match; неоднозначность не выбирает чей-либо proof/ID | normalizedLegacyCollisionFailsClosedWithoutDamagingUnrelatedAccounts: оба пароля и все case-variants не авторизуют пользователя |
| Не создавать третий аккаунт / не менять диск на отказе | Signup использует any-match; ambiguous recovery не отправляет письмо и не пишет данные | Старые bytes совпадают побайтно после login/recovery/signup отказов, accounts count неизменен |
| Не заблокировать другие accounts | Неоднозначность обрабатывается только для запрошенного normalized email | Отдельный Alex login и signup независимого адреса проходят; исходные legacy accounts/proofs/tickets/registrations сохраняются |

## ARCHITECTURE / LIMITATION

Новые ports/schema/DI/state owners не введены. Это локальное исправление fake repository; remote backend не меняется. EPA2 / product schema 5 сохраняются. Server-like правила не используются для переписывания legacy email и Account.id.

При нескольких старых Account.id с одинаковым normalized email восстановить единственный правильный ID по введённой почте невозможно. Поэтому login возвращает InvalidCredentials для любых case-вариантов и обоих password proofs; verification/reset принимают запрос как для неизвестного email, **без dispatch**. EmailDispatch означает принятие запроса, а не доставку письма. Signup возвращает EmailTaken при любом normalized match. Автоматического merge, выбора первого Account, переименования email или reset всей базы нет. Для доступа к таким конфликтующим legacy demo identities потребуется отдельное явное разрешение конфликта/сброс demo; остальные аккаунты продолжают работать. Это ограничение только неоднозначных legacy данных, не новая блокировка всех accounts.

## CHECKS / RESULTS

JDK: `JAVA_HOME=C:\Program Files\Android\Android Studio1\jbr` (JBR 21), PowerShell, Gradle wrapper проекта. Согласованное отдельное окно Gradle; параллельных прогонов помощником не выполнялось.

1. До production fix: `./gradlew.bat :data:fake:test --tests '*FakeLegacyEmailRecoveryTest' --console=plain` — **exit 1, 3 tests / 3 failures**. Уникальный mixed-case account не мог войти; collision авторизовал lowercase Account вместо безопасного отказа. Лог `red.log`, сохранённые XML `red-xml/`. Два success helper casts завершились ClassCastException вследствие InvalidCredentials вместо ожидаемого Success; collision assertion показал неверную авторизацию.
2. После fix: `./gradlew.bat :data:fake:test --console=plain` — **exit 0, 95 tests / 0 failures / 0 errors / 0 skipped**. Все прежние 92 плюс 3 новые проверки; задачи compileKotlin/compileTestKotlin/test выполнены, не только up-to-date. Лог `green.log`, полная копия XML `green-xml/`.
3. Сводка `results.json` и manifest `scope-sha256.json` сохранены в `.gradle/m01-fix-email-evidence/`. Копии XML не зависят от последующих filtered Gradle-запусков.

`git diff --check` для этого scope — exit 0; лог `diffcheck.log` в evidence-папке. Полный Android build/lint/device и server tests помощником не повторялись: production изменение только fake lookup. Исправление не объявляет повторную приёмку всего M01.

## HANDOFF

Root фиксирует этот scope и запускает повторное android-code-review P2. Reviewer сверяет отказ на collision, совместимость сохранённых IDs/proofs/связей и неизменность иных источников. После технической приёмки M01 можно переходить к M02; прежний M02 readiness остаётся read-only анализом, его код этим исправлением не запускался. Ручная проверка остаётся только в конце цикла M00–M09.

## Дополнительная проверка root перед фиксацией

14 ноября root выполнил `./gradlew.bat -I .gradle/m01-verified-google.init.gradle :app:assembleDemoDebug :app:testDemoDebugUnitTest --offline --console=plain`: exit 0, BUILD SUCCESSFUL/27s, 96 задач (13 выполнены/83 up-to-date). Demo app unit XML: 72 tests, 0 failures/errors. Лог `.gradle/m01-fix-email-app.log`. Обновлённый demo APK code21: 44725902 bytes, SHA-256 `0565370c3490f65e62cda51ea5bac754f1b87a5d93594b597a0429407ff24a81`; заменяет demo hash из первоначального M01 отчёта. Remote APK не менялся. Это адресный повтор после fake fix, не новый полный прогон всех 232 выполнений/5 device.
