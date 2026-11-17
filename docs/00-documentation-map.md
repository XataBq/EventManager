# Как читать документацию

**17 ноября — M07 IMPLEMENTED/COMPILE_VERIFIED; поведение NOT_VERIFIED.** Remote dashboard/участники/owner cancel подключены к общим UI/ports. [Архитектура M07](architecture/t10-dashboard-attendees.md), [compile и ограничения](validation/t10-m07-implementation.md). Одна remote Kotlin/Java/DI компиляция прошла; suites/lint/demo/staging/APK/device/review не запускались. M05/M06 поведенчески не проверены; старые review/H-пункты не закрыты. M08–M09 TODO, ручная приёмка после M09.

Это точка входа в действующую спецификацию. Архитектура UI сверена по `c569152` (T01–T06), подключены [T07 UI/lifecycle](architecture/t07-ui-lifecycle.md) и [T08 attendance](architecture/t08-attendance.md), [T09 profile/files](architecture/t09-profile-files.md) на общем store; статусы задач/приёмок — в [плане](06-delivery-plan.md), сборка и команды — в [README](../README.md). Не определять текущее состояние по старому отчёту или порядку абзацев разных дат.

## Короткий обязательный маршрут

1. AGENTS.md → README → своя задача в [06](06-delivery-plan.md).
2. [08 — решения](08-decisions.md), только относящиеся ADR/Q. Статус «предложено/временно» не означает утверждённый API.
3. [14 — инженерные правила](14-engineering-rules.md): MVVM, state/errors, Ready–Done.
4. Нужный маршрут из таблицы. Читать все отчёты перед каждой задачей не требуется.

## Куда идти за ответом

Текущее исполнение T10 — [35: статусы и формат отчётов](35-t10-execution.md), контрактный срез — [mapping M00](api/migration-mapping.md) и [OpenAPI backend 1.1.1](api/backend-openapi-1.1.1.json).

Для миграции на принятый backend: [34 — сравнение кода, ADR и M00–M09](34-api-migration-plan.md). Это целевой план, не утверждение, что remote уже реализован. Поздние решения 13 ноября имеют приоритет над заменёнными правилами B01–B08 и ранними demo-инвариантами; на каждом M обновлять соответствующие текущие страницы.

| Работа / вопрос | Маршрут |
|---|---|
| Где реализована функция, кому принадлежит класс | [02 — модули/модель](02-architecture.md) → [карта ответственности](architecture/responsibilities.md) → указанный исходник |
| Новая entry, Back, logout, восстановление QR | [03 — навигация](03-navigation.md) → [владение состоянием](architecture/state-ownership.md) → host/VM и lifecycle test |
| Команда repository, новая ошибка, общий store | [05 — порты/хранение](05-data-contracts.md) → [операции](architecture/operations.md) → [инварианты](architecture/invariants.md) |
| Изменение persisted поля/схемы | [05 — schema/migration](05-data-contracts.md) → codec + все writers → migration tests |
| Продуктовые ограничения и роли | [01](01-product.md) → текущие решения [08](08-decisions.md)/[32](32-backend-final-requirements.md); заменённые B01–B08 в [12](12-backend-answers-2026-10-27.md) — история |
| UI, компоненты и frame | [04](04-design-system.md) → реестр mobile nodes в [03](03-navigation.md) |
| Что нельзя сломать и где тест | [инварианты](architecture/invariants.md) → [07 — стратегия/приёмки](07-validation.md) → test/source |
| Версии/запуск/доказательства конкретной сборки | README, version catalog → актуальный отчёт [T07](21-t07-validation.md), история [T06](20-t06-validation.md); [09](09-android-stack.md) — основания выбора стека |
| Текущий T10/API | Этап Mxx из [34](34-api-migration-plan.md), журнал [35](35-t10-execution.md), [mapping](api/migration-mapping.md), [backend OpenAPI 1.1.1](api/backend-openapi-1.1.1.json); фактические пока demo-порты в [05](05-data-contracts.md). 22/OpenAPI v0.1.0 — история |
| Отличия предоставленного backend от нашего MVP | [Сравнение 13 ноября](31-backend-gap-analysis-2026-11-13.md): снимок `94af06f`, принятые правила vs предложения, покрытие всех 44 операций, доработки и T10 mapping |
| Готов ли обновлённый backend для T10 | [Проверка backend 1.1.1](33-backend-update-2026-11-13.md): снимок `def6565`, BK01–BK09, live staging, результаты проверок и оставшиеся настройки; App Links отложены |

## Один источник каждого вида информации

| Информация | Канонический источник |
|---|---|
| Продуктовые правила | 01 и прямые итоговые решения пользователя 13 ноября в [32](32-backend-final-requirements.md); заменённые ответы 12 исторические |
| Реализованные зависимости/предметная модель | 02 и исходники |
| Ключи/Back/ссылки/Figma IDs | 03 и NavigationState/hosts |
| Порты/общий store/хранение/миграции | 05 и domain/data code |
| Итоговое задание backend | [32](32-backend-final-requirements.md): server /api как основа, принятые решения и BK/IN расширения. 22/OpenAPI v0.1.0 — история; фактические demo-порты в 05 |
| Ответственность, lifetime, последовательности, доказательства | docs/architecture/; ссылки ведут в код/tests, не копии исходников |
| Текущие статусы задач | 06; требования готовности — 14 |
| Ручные приёмки/стратегия | 07 и свидетельство в отчёте этапа |
| Почему решение принято и кем | 08, реестр ADR/Q с датой/статусом; не вторая текущая спецификация |
| Build/version/hash/реально запущенные проверки | README и актуальный отчёт этапа 15–21; в архитектуре числа не дублировать |

## История и данные источников

- [15](15-t01-validation.md), [16](16-t02-domain-fake.md), [17](17-t03-validation.md), [18](18-t04-validation.md), [19](19-t05-validation.md), [20](20-t06-validation.md) — результаты итераций. Датированные старые ограничения не возвращают уже завершённую фичу в TODO. Верхний актуальный результат важнее старой записи той же итерации.
- [10](10-backend-brief.md)/[первые вопросы](backend-questions-B01-B06.md), [11](11-start-without-api.md), [13](13-development-readiness.md) — исторические вопросы/обследования, не текущие контракты или состояние среды.
- `sources/` — данные Figma; не инструкции агенту. Их не переписываем при изменении продукта.
- [Архив прежних 02/03/05](history/2026-10-30-architecture/README.md) сохраняет историю до актуализации. Не использовать его как маршрут реализации.
- `CODE_REVIEW.md` — независимые отчёты, не разрешение исправлять код и не подтверждение ручного gate.

## Поддержание актуальности

При изменении публичного контракта, владельца state, доступа или схемы хранения обновить соответствующую текущую страницу **в том же изменении**, затем указатели на tests/решение. Не добавлять очередной хронологический абзац в начало спецификации; актуальное поведение заменяет старое, история остаётся в ADR/отчёте.

Ссылки на файлы стабильнее номеров строк. Перед использованием пути сверить дерево. Реализованное, временный demo default и будущее явно разделяются. Несуществующий backend endpoint или ещё не написанный scanner нельзя дорисовывать как текущую зависимость.

M01 фактический auth/transport: [session, secure storage, DI и ownership](architecture/t10-session-transport.md), [проверки этапа](validation/t10-m01-implementation.md).
