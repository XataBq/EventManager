# T07: закончить интерфейс волонтёров и общий сканер

**Актуализация 6 ноября:** UI T07 реализован (`48c09b9`, исправление `6de02da`) и принят пользователем вместе со всеми подготовленными ручными проверками demo: [свидетельство](../validation/2026-11-06-manual-acceptance.md). Постановка ниже историческая, не повторять выполненную работу и запросы приёмки.

Работай в `F:\ASProjects\MospolytechProject`. Реализуй **оставшуюся UI-интеграцию T07** на существующей foundation. Доведи до проверяемого demo APK; backend пока отсутствует, требования отправлены и ответ ожидается. Не запускай подагентов.

## Вход и разрешение

- Исходный HEAD при постановке `555a2a0`, уже отправлен в origin/main. Сначала проверь актуальные Git status/log; сохрани чужие изменения. Есть большой незакоммиченный пакет архитектуры/API, CODE_REVIEW и prompts — не включать целиком в свой коммит.
- **1 ноября пользователь подтвердил «Проверил оба, замечаний нет»** на вопрос о Back и организаторском smoke T06 на code 13. T05/H2 и T06 приняты, повторного разрешения UI T07 не требуется. Устройство не сообщено. H3/H4 не приняты.
- `bb62cd0` уже реализовал VolunteerRepository/CheckInRepository, FakeCheckInOperations, schema 4, ManualCode, first records и ScannerController. Не создавать их повторно, не писать второй store/scanner state machine и не повторять миграцию v3→v4.
- Ревью `555a2a0` не нашло подтверждённых P0–P3 в Back diff. 41 app JVM перепроверен ревьюером; 10 device подтверждены сохранённым журналом. Это не свежая проверка всех модулей/всей камеры. CODE_REVIEW не менять и полный review-скилл автоматически не запускать.

## Прочитать перед изменениями

AGENTS → README → docs/08-decisions → T07 в docs/06-delivery-plan → docs/14-engineering-rules. Затем docs/21-t07-validation, docs/23-navigation-back-validation, docs/architecture/t07-checkin-foundation и относящиеся sections 02/03/04/05, state-ownership/operations/invariants. Фактические contracts и owner lifecycle проверять в коде.

API в docs/22 и docs/api — предложение, не принятый backend. Не переносить сейчас в demo новые TTL/refresh, idempotency/replay protocol, existing-email ветку, random manual generation или TARGET_IS_OWNER. Использовать текущие согласованные demo contracts; необходимое изменение обсудить и обосновать отдельно. Не ждать появления API для камеры/UI.

## UI-источники

Figma file `i2NNhWGwunnvhZZX3GPuSR`: O05 `29:833`, V02 `29:243`, V03 `29:289`, V04 `29:336`, V05 `29:375`; MyEvents V01/O01 `29:96`/`29:558`, UI kit `35:486`. Общие Tickets/guest receipt — IDs из docs/03.

Перед get_design_context прочитать figma-design-to-code, перед use_figma — figma-use. Figma только читать. При недоступности явно указать непроверенные узлы и использовать сохранённые источники/спецификацию, не заявлять сверку живого дизайна. Компоненты брать из core:designsystem; никаких desktop-пунктов, MAX/99 или второго UI-кита.

## Реализация по рабочим срезам

1. **Навигация и ручная проверка владельцем.** Общие ScannerKey/VolunteersKey только с eventId/entry ID, без credential/PII. Внутренние открытия сохраняют таб/родителя по исправлению `555a2a0`. Hilt VM принадлежит entry, связывает UI с существующим ScannerController; его scope закрывается при удалении владельца. Сначала получить рабочий путь MyEvents → Scanner → manual → Active становится Used → повтор AlreadyUsed.
2. **Волонтёры.** Owner-only list/grant email-or-ID/revoke с подтверждением; loading/empty/error/retry/submitting. Права только относительно E. Повтор grant не создаёт дубль; неизвестный аккаунт — ошибка, без приглашения. Grant/revoke обновляет общий MyEvents. Владелец сканирует без self-grant. Сохранить собственный ticket/participation и историю после revoke. Дать владельцу вход в Volunteers/Scanner из существующего MyEvents, не реализовывать ради этого dashboard T08.
3. **Результат и manual code.** Одна entry сканера, результат — state, без растущего стека result routes. Admitted / AlreadyUsed(first time/checker если есть) / Invalid без чужой PII; legacy Used.first=null честно без выдуманного времени. Timeout/network/storage — отдельная ошибка связи, не INVALID/допуск. Следующее сканирование только после явного действия; Back из результата возвращает ready, затем — прежнего родителя. Показать существующий ManualCode на текущем собственном билете и гостевом receipt; история без QR/code. Не подменять code печатным номером. Не вводить guest lookup/cache и не ломать старый SavedStateHandle receipt.
4. **Камера.** Выбрать один CameraX+QR decoder с проверенными по официальным источникам версиями, совместимыми с текущими Gradle/SDK. Уже есть ZXing core для генерации QR — учесть до добавления зависимостей. Не обновлять остальной стек без причины. Platform adapter не знает fake/repository; VM/Compose не знают DTO/хранение. CAMERA manifest/runtime permission, объяснение отказа/settings по необходимости, ручной ввод остаётся доступным. Не запрашивать media/storage permissions ради сканирования.
5. **Lifecycle.** Binding только при visible/foreground/current access и permission; hidden-tab/Back/background/logout/revoke освобождают камеру и приостанавливают анализ. Каждый ImageProxy закрывается в finally, backpressure не создаёт очередь кадров. До ответа repository один запрос, повторы кадров/несколько QR не создают гонки. Не восстанавливать credential/Admitted из SavedStateHandle после нового процесса; состояние ready/access проверяется заново. Late callback/result/finally не меняет новое поколение/личность. Forbidden очищает результат/PII и уводит в доступный MyEvents; билет пользователя остаётся.

Не добавлять dashboard/участников/экспорт, полный профиль/удаление, HTTP/production auth, push или offline admission. Fake остаётся за DI и общими ports. Изменять foundation лишь при доказанной потребности UI/исправлении с regression test, сохраняя schema 4 и существующие данные. Debug seed QR/manual примеры только через demo UI/артефакт, не production logs.

## Проверки и передача

- Адресные state/VM tests для wiring, одноразовой отправки, permission/manual, cancellation/revoke/hidden tab, Back и старого guest receipt. Не дублировать все существующие ScannerController tests тестами деталей реализации.
- Существующие domain/fake/app/platform JVM suites, assemble app/test APK, lint и verifyDemoIsolation. JBR 21/Gradle wrapper из проекта, версии не выбирать по памяти. Не запускать параллельные Gradle builds в общем checkout.
- Минимальный device proof для route/permission lifecycle, скрытого таба, возврата и настоящего decoder на контролируемом изображении/кадре; явно отделить его от физической камеры/фокуса. Не сбрасывать пользовательское устройство. Если device недоступен, не придумывать успешный результат.
- `python scripts/verify-architecture-docs.py`, `git diff --check`. Обновить актуальные navigation/state ownership/responsibilities/operations/invariants и docs/21, README, план; прежние результаты сохранить как историю. Если пришлось менять API-предложение, только связанные страницы и отдельно отметить отсутствие backend-согласования.
- Новой сборке увеличить versionCode относительно фактического текущего (13 на постановке); предоставить сохранённый APK, version/code/hash/seed/аккаунты, точные команды и результаты. Не приписывать прежние проверки новой сборке.
- Человеку короткий smoke: grant → volunteer scanner → Active/повтор Used; WrongEvent/Cancelled/Unknown; manual; отказ камеры; background/tab/Back; revoke при открытой камере; сохранение личного билета; QR с другого экрана/бумаги/слабый свет. Полный H3 после T08.
- До человеческой camera/UI-приёмки T07 — READY_FOR_HUMAN, не DONE. T05/T06 уже приняты; не переоткрывать их без найденной регрессии.
- После проверок отдельный локальный коммит только своих изменений, без push. Не менять CODE_REVIEW. Итог: готовые сценарии, APK, проверки/ограничения и следующая T08. Незакоммиченные сторонние документы не присваивать своему коммиту.
