# Code Review — последний коммит Android-приложения

Дата: 27 октября 2026, Europe/Moscow.

## Область и итог

- Коммит: `be2079903c2a7904f1d5d5b1f14298f1a86955e2` — `feat: add verified T01 Android skeleton`.
- База сравнения: `ae45acdc618a6575097dcdb055c0582ac1fd0e8b` (`HEAD^`).
- Объём diff: 45 файлов, 1632 добавленные и 18 удалённых строк; технический каркас T01, тесты, сборочная конфигурация и сопутствующая документация.
- Перед проверкой рабочее дерево было чистым. Просмотрены добавленные Kotlin-исходники, тесты, ресурсы, манифесты, конфигурация модулей, wrapper properties, скрипт проверки process death и изменения документации. Проверка выполнена по локальному скиллу `android-code-review`.

**Подтверждённых дефектов P0–P3 в области этого коммита не обнаружено.** Это результат статического ревью и перечисленных ниже локальных проверок, а не утверждение об отсутствии ошибок во всём приложении. Поведение на устройстве в этой сессии не воспроизводилось.

Коммит соответствует ограниченному объёму T01: три вкладки, один вложенный demo-экран, Hilt ViewModel, repository port, сохранение состояния и demo-ссылки. Отсутствие входа, регистрации, QR, прав на мероприятия и backend не является дефектом этого коммита: эти сценарии относятся к последующим задачам. Полноценный UI-кит и визуальное соответствие Figma относятся к T03. Статус READY_FOR_HUMAN не подменён статусом DONE.

## Проверенные области

| Область | Свидетельство и вывод | Предел проверки |
|---|---|---|
| Соответствие документации | README, задача T01 в `docs/06-delivery-plan.md`, ADR-14–16 и `docs/15-t01-validation.md` согласуются с наличием пяти модулей, единственного demoDebug и минимального demo-среза. Сверены `docs/14-engineering-rules.md` и актуальные продуктовые ответы B01–B08. | Будущие сценарии T02–T10 не реализованы; их корректность не оценивалась как готовый функционал. |
| MVVM и зависимости | `DemoRoute` получает ViewModel и собирает StateFlow с учётом lifecycle. `DemoScreen` получает только UiState и callbacks. `DemoViewModel` зависит от `DemoRepository`, без concrete fake, DTO, Context и хранилища данных. Domain — чистый Kotlin. | Пока есть только один технический сценарий, без transport errors и предметных операций. |
| SOLID и объём абстракций | UI, координация состояния, repository port, fake и parser имеют отдельные обязанности. Конкретная реализация выбирается в Hilt-модуле demo. Нет обязательных пустых use cases, BaseViewModel или каркаса для всех будущих фич. Нарушений с конкретным негативным последствием не выявлено. | Расширяемость под ещё не согласованный API не доказывается этим срезом. |
| Навигация | `NavigationState` сохраняет отдельные стеки, повторный выбор вкладки удаляет вложенную запись, повтор той же ссылки не создаёт новую запись, Back не удаляет корень стека. Новому визиту назначается новый entryId. Навигационные unit-тесты проходят. | Системный Back, анимации и быстрые последовательные действия в этой сессии не проверялись на устройстве. |
| Владение состоянием | Ключи реализуют NavKey и сериализуются. Для каждого стека создаются saveable-state и ViewModel-store decorators, в том числе для скрытых вкладок. Счётчик находится в SavedStateHandle записи; Activity не хранит постоянный набор экранных данных. Unit-тест проверяет чтение восстановленного счётчика. | Очистка реального Hilt owner, recreation и process death требуют device-проверок; старый отчёт не считается новым запуском. |
| Demo-ссылки | `MainActivity` разделяет холодный запуск и onNewIntent; потреблённая ссылка не применяется заново при обычном восстановлении Activity. Parser ограничивает scheme/host, запрещает userInfo/port/query/fragment и принимает один короткий ID. Проверка допустимых и запрещённых ссылок проходит. | Это demo scheme; production App Links, владение доменом и серверная авторизация не проверены и не реализованы. |
| Корутины и данные | Чтение repository — Flow, экранное состояние — StateFlow через viewModelScope и WhileSubscribed. UI не выполняет IO, CancellationException не перехватывается. Fake возвращает явный NotFound через отсутствие записи, не имитирует успешную предметную операцию. | Текущий fake не выполняет сетевые мутации; retry, отмена запросов и серверные конфликты здесь отсутствуют. |
| Изоляция demo | Fake указан в demoImplementation, Hilt binding — в src/demo. Включён только demoDebug; release отключён. Задача verifyDemoIsolation проходит. | Задача проверяет текущие варианты и прямую зависимость implementation; это не универсальный анализ всех будущих транзитивных зависимостей. |
| Ресурсы и безопасность | Тексты вынесены в strings.xml, секреты и реальные пользовательские данные не вводятся и не логируются. Нет guest cache, глобального User.role, лишних разрешений камеры/сети и кода серверного распределения мест. | Безопасность будущих auth/QR/API не проверена; реальных механизмов пока нет. |

Применённая схема сохранения сериализуемых ключей и привязки ViewModel к NavEntry согласуется с [официальным руководством Navigation 3](https://developer.android.com/guide/navigation/navigation-3/save-state). Раздельная декорация стеков предусмотрена [документацией NavEntryDecorator](https://developer.android.com/guide/navigation/navigation-3/naventrydecorators). Разделение владельца состояния и composable с данными/callbacks сверено с [руководством Compose по state hoisting](https://developer.android.com/develop/ui/compose/state-hoisting). Эта сверка подтверждает выбор подхода, но не заменяет проверку фактического lifecycle на устройстве.

## Выполненные проверки

Рабочая папка: `F:\ASProjects\MospolytechProject`. Использован JBR из установленной Android Studio1, без изменения глобальных настроек.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
.\gradlew.bat :app:assembleDemoDebug :app:lintDemoDebug :app:testDemoDebugUnitTest :core:platform:testDebugUnitTest :data:fake:test :app:verifyDemoIsolation --offline --console=plain --max-workers=2
```

Результат: **BUILD SUCCESSFUL**, 151 задача, 12 выполнены / 139 up-to-date. Assemble успешно проверен инкрементально; полностью чистая сборка и скачивание зависимостей с нуля не выполнялись. Lint: **0 ошибок, 6 предупреждений** о наличии более новых версий Gradle/AGP/Coroutines/Serialization. Само наличие новой версии не доказывает дефект зафиксированного набора; обновления не выполнялись. Результат lint не является проверкой актуальности всех библиотек и отсутствия уязвимостей.

Чтобы отдельно исключить только кешированный результат unit-тестов, запущено:

```powershell
.\gradlew.bat :app:testDemoDebugUnitTest --rerun :core:platform:testDebugUnitTest --rerun :data:fake:test --rerun --offline --console=plain --max-workers=2
```

Результат: **BUILD SUCCESSFUL**, все три test-задачи выполнены повторно. XML-отчёты подтверждают **7 тестов, 0 failures, 0 errors, 0 skipped**:

| Набор | Тестов |
|---|---:|
| DemoViewModelTest | 2 |
| NavigationStateTest | 3 |
| DemoLinkParserTest | 1 |
| InMemoryDemoRepositoryTest | 1 |

Дополнительно:

- `git diff --check HEAD^ HEAD` — без замечаний.
- `adb devices` через установленный SDK — список устройств пуст. Device-тесты не запускались.
- `Get-FileHash gradle/wrapper/gradle-wrapper.jar -Algorithm SHA256` — `497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7`, совпадает со значением в отчёте T01. Независимая загрузка официальной контрольной суммы в этой сессии не выполнялась.

Обычный запуск команд первоначально не работал из-за `helper_unknown_error: setup refresh had errors`; чтение и проверки выполнены после разрешённого запуска команд вне неисправного ограниченного окружения. Это ограничение среды не трактовалось как дефект приложения.

## Допущения, вопросы и ограничения

1. Область ревью — последний коммит относительно его непосредственного родителя. Готовность всего MVP не оценивалась. Блокирующих продуктовых вопросов для оценки T01 не возникло.
2. Результаты 3 device-тестов и реальной смерти процесса из `docs/15-t01-validation.md` — свидетельства предыдущего этапа. В этом ревью прочитаны соответствующие тесты и скрипт, но их результаты самостоятельно не повторены.
3. Runtime API 28/37, физическое устройство, predictive Back, системные отступы, крупный шрифт и визуальная читаемость не проверены. Человеческая приёмка T01 остаётся ожидающей; H1–H4 не отмечаются пройденными.
4. Release/R8/signing, production App Links, реальные письма, доступ по eventId и серверная авторизация не входят в реализованный каркас. Отсутствие этих проверок нельзя переносить на будущую production-готовность.
5. Проверка saved count в unit-тесте не доказывает сохранность будущего GuestTicketSnapshot: при его реализации понадобятся собственные проверки payload и срока жизни flow по B02.

## Следующий шаг

Выполнить ручной smoke T01 по `docs/15-t01-validation.md` на конкретной сборке. Независимые T02/T03 могут продолжаться в соответствии с планом. Подтверждённых исправлений по этому ревью не требуется; исходники, статусы задач, решения и зависимости не изменялись. Единственный добавленный отслеживаемый результат ревью — этот отчёт; обычные сборочные артефакты и test/lint outputs обновлены проверками.

---

# Code Review — 0effb8d, T02.1 domain и общий fake

Дата: 27 октября 2026, Europe/Moscow.

## Область и результат

Коммит: `0effb8db5270371bf871ba94a945de0c670b0d23` — `feat: add T02 domain models and shared demo repositories`.
База: `be2079903c2a7904f1d5d5b1f14298f1a86955e2` (`0effb8d^`). На момент ревью указанный коммит является HEAD. В diff 17 файлов, 1065 добавленных и 10 удалённых строк.

**Подтверждённых дефектов P0–P3 в области коммита не обнаружено.** Вывод ограничен реализованной T02.1, статическим анализом и проверками ниже. Он не подтверждает готовность полного T02, Android-пользовательских сценариев или production-интеграции.

Прочитаны AGENTS.md и локальный скилл android-code-review; README, актуальные решения, план T02, продуктовые правила, архитектура, контракты, относящиеся к срезу проверки A/S, инженерные правила и отчёт `docs/16-t02-domain-fake.md`. Учтён приоритет ответов B01–B08 над ранними сценариями. Прочитаны все новые модели, валидатор, repository interfaces, общий fake, фикстуры и тесты, изменения demo DI и сборки domain, сопутствующий diff документации. Проверены вызывающие места: продуктовые порты пока подключены только в demo DI и тестах; UI T01 их не использует.

До ревью единственным неотслеживаемым файлом был существующий CODE_REVIEW.md. Предыдущий отчёт сохранён; в него добавлена эта запись. Исходники, зависимости, решения и статусы задач не изменены.

## Соответствие документации и сценариев

| Область | Проверенный результат | Свидетельство / предел |
|---|---|---|
| Границы T02.1 | Реализованы модели, policy/validation и три порта; T02 целиком остаётся IN_PROGRESS. | README, docs/06 и docs/16 явно отделяют завершённую итерацию от оставшихся auth, persistence и команд. Отсутствие этих частей не классифицировано как регрессия. |
| Независимые связи и права на E | Нет глобального User.role. Владение и grant определяются для конкретных eventId/userId; билет не даёт сканирование. У владельца есть управление/экспорт, у волонтёра их нет. | EventAccessPolicy; domain-тест и mixedMembershipSurvivesRevokingOnlyThatEventsGrant. Отзыв grant сохраняет собственный билет и связи с другими E. Закрытых owner/scan команд ещё нет, их repository guards не объявлены проверенными. |
| Гостевая заявка | Новая успешная заявка создаёт Registration с userId=null и Ticket, возвращает snapshot и добавляет demo outbox item, не создавая Account. | guestTicketUpdatesPublicAvailabilityWithoutCreatingAnAccount. QR payload не содержит email/имя; токен демонстрационный. |
| Повторная заявка | Повтор не возвращает старый snapshot/QR, не выдаёт новый билет и не добавляет второе письмо. | duplicateGuestSubmissionDoesNotRecoverQrOrCreateAnotherTicket; ExistingRegistration содержит только email. |
| Существующая почта | Для email существующего аккаунта — RequiresLogin, без изменения мест/списка билетов/outbox и без сохранения отправленной формы. | existingEmailRequiresLoginWithoutSavingAnswersOrIssuingTicket. Возврат после реального входа и повторное заполнение UI пока не реализованы. |
| Билеты после отдельного создания аккаунта | Создание/подтверждение demo-аккаунта само по себе не создаёт сессию. После явного входа с подтверждённым email доступны выданные ранее билеты. | separateAccountOnSameEmailSeesEarlierTicketsOnlyAfterVerifiedSignIn. Другой аккаунт их не получает; после выхода observeMyTickets возвращает AuthenticationRequired. ScenarioController моделирует сессию, не полноценный auth. |
| Места и время | Unlimited отличается от Limited; нулевой остаток закрывает регистрацию. StartsAt закрывает приём, endsAt/manual Finished дают FINISHED. Submit заново проверяет Clock. | Domain-тесты и submissionChecksTheClockEvenWhenAnOldPageSaidOpen. Остаток вычисляет fake backend, алгоритм распределения последнего места на Android не добавлен. |
| Анкета и валидация | Проверяются версия формы, consent, непустое имя без ограничения письменности/числа слов, телефон по policy, типы/required вопросов и ID вариантов. | RegistrationRulesTest; consentAndStaleFormFailuresHaveNoSideEffects; policyChangesDoNotRewriteExistingRegistrationAndAnswersAreCopied. Исходная изменяемая map ответов копируется; её последующая правка не меняет сохранённую регистрацию. |
| Сбои и reset | Управляемый Timeout возвращается ошибкой без автоматического retry, билета или письма. Reset очищает новую сессию/заявки/outbox и восстанавливает seed. | failureIsControlledAndDoesNotSilentlyRetryOrWriteAnOutboxItem и resetRestoresSeedAndRemovesSessionAndNewSubmissions. Настоящего transport нет. |

Не выявлено противоречий реализованного среза с B01–B08. Матрица относится к логике domain/fake; она не означает прохождение UI-приёмки или полного S02 с QR, навигацией и process death.

## Архитектура, SOLID и Android practices

- Domain остаётся чистым Kotlin: модели, типизированные исходы и порты не зависят от Android, Hilt, Compose, DTO или конкретного хранения. Ошибки представлены типами/полями, без Android R и текстов сервера.
- Три repository interfaces разделяют event read/refresh, guest submit и собственные билеты. FakeEventPassEnvironment координирует один in-memory источник данных. Для текущего размера это сохраняет причинную связь между заявкой, местами и списком билетов; отдельный универсальный backend framework не создан.
- DemoModule предоставляет один singleton environment и его порты. UI/ViewModel не получили зависимости на concrete fake или ScenarioController. Fake остаётся в demoImplementation/src/demo, production-реализация не заявлена готовой.
- Внешнее чтение идёт через Flow, однократные команды — suspend. StateFlow находится внутри fake. Shared state обновляется под одним lock; в критической секции нет IO, задержек или suspend-вызовов. Блокирующей сетевой/дисковой работы и GlobalScope в изменении нет; CancellationException не перехватывается.
- Clock инъектируется; seed, ошибки и выдача ID детерминированы. Запросы не зависят от случайных задержек. Отдельного ID provider нет, последовательность находится внутри fake; для текущих повторяемых сценариев это не признано дефектом.
- ScenarioController отделён от публичных domain-портов и используется как demo/test control. Его putEvent/signInAs не являются проверенными бизнес-командами редактирования или входа, что прямо указано в документации.

Форма API (Flow для наблюдения, suspend для однократных операций, доступ к данным через repository) сверена с [официальным руководством Android по data layer](https://developer.android.com/topic/architecture/data-layer). Отсутствие блокирующего IO в текущих suspend-операциях оценивалось с учётом [Android coroutine best practices](https://developer.android.com/kotlin/coroutines/coroutines-best-practices). Эта сверка не доказывает свойства будущих remote adapters и не требует добавлять dispatcher/use case без фактической необходимости.

## Выполненные проверки

Рабочая папка: `F:\ASProjects\MospolytechProject`. Использован JBR из Android Studio1. Зависимости не загружались; глобальные настройки не менялись.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
.\gradlew.bat :core:domain:test --rerun :data:fake:test --rerun :app:assembleDemoDebug :app:lintDemoDebug :app:testDemoDebugUnitTest :core:platform:testDebugUnitTest :app:verifyDemoIsolation --offline --console=plain --max-workers=2
```

Результат: **BUILD SUCCESSFUL in 9s**, 153 задачи, 4 executed / 149 up-to-date. Domain и fake test-задачи запущены с --rerun; остальные перечисленные Gradle-задачи, кроме verifyDemoIsolation и связанных выполняемых задач, в основном проверены инкрементально. Полностью чистая сборка с загрузкой toolchain/dependencies не выполнялась.

XML-результаты: **24 теста, 0 failures, 0 errors, 0 skipped**.

| Набор | Количество | Свежесть в этой сессии |
|---|---:|---|
| RegistrationRulesTest | 6 | Выполнены заново |
| FakeEventPassEnvironmentTest | 11 | Выполнены заново |
| InMemoryDemoRepositoryTest | 1 | Выполнен заново |
| DemoViewModelTest | 2 | Test-задача up-to-date |
| NavigationStateTest | 3 | Test-задача up-to-date |
| DemoLinkParserTest | 1 | Test-задача up-to-date |

Итого 18 тестов domain/fake выполнены повторно; 6 app/platform имеют успешные актуальные кешированные результаты. Новых regression tests не добавлялось: подтверждённого дефекта для воспроизведения не найдено, исходники оставлены неизменными.

- Lint: **0 errors, 6 warnings** из существующего отчёта, lint-задача up-to-date; предупреждения относятся к доступным новым версиям зависимостей/сборочных инструментов. Это не проверка отсутствия уязвимостей или актуальности всех версий.
- verifyDemoIsolation: успешно, включён только demoDebug, fake отсутствует в main implementation.
- `git diff --check 0effb8d^ 0effb8d`: без замечаний.
- Gradle/Hilt сборка demoDebug проходит инкрементальную проверку; runtime-создание новых продуктовых экранов не проверялось, поскольку они отсутствуют.

Обычное выполнение команд вновь остановилось на ошибке среды `helper_unknown_error: setup refresh had errors`. Чтение и проверки выполнены через разрешённый запуск вне неисправного ограниченного окружения. Ошибка среды не классифицирована как ошибка приложения.

## Вопросы, допущения и ограничения

Блокирующих вопросов в пределах T02.1 не возникло. Exact email match после trim, синтаксическая проверка телефона и фиксированный Clock рассмотрены как явно описанные demo defaults, не как согласованный серверный контракт.

1. Все пользовательские результаты проверены на уровне unit/contract-like тестов, без прохождения реального UI. Android сохранение GuestTicketSnapshot, построение QR и срок жизни гостевого flow относятся к T05 и здесь не доказаны.
2. Device-тесты, реальная смерть процесса и ручная приёмка не запускались. T01 остаётся READY_FOR_HUMAN; H1–H4 не отмечены пройденными. Этот коммит не меняет UI и навигацию T01.
3. Хранилище только в памяти; перезапуск процесса создаёт seed заново. Это документированное ограничение T02.1. Перед H2 требуется сохранение данных fake-сервера при сохранении запрета постоянного клиентского guest QR-cache.
4. observePublicEvent не обновляется фоновым таймером при смене Clock: требуется refresh или изменение состояния. Это явно описано; submit проверяет время самостоятельно. Проверка live-подписки на refresh отдельно не добавлялась; текущий тест проверяет значение после refresh.
5. AuthRepository, отмена/Used/check-in, изменения формы владельцем, сохранение черновиков, real API/transport errors и остальные S/A-сценарии не реализованы. Наличие моделей/ScenarioController не считается подтверждением этих бизнес-команд.
6. Детерминированные demo QR-токены, точное сопоставление email и доступные debug controls не должны переноситься в production как механизм безопасности. Сейчас release-вариант отключён, fake изолирован в demo.

## Следующий шаг

Продолжать T03, затем T04/T05 с расширением T02 по потребностям следующей фичи. При появлении продуктового UI нужны проверки отображения ошибок/отправки, запрета повторной отправки и lifecycle гостевого snapshot. Подтверждённых исправлений по этому ревью нет; прежний отчёт T01 сохранён, изменён только CODE_REVIEW.md. Сборочные/test outputs могли обновиться обычными проверками и не входят в исходники.

---

# Code Review — a263c21, T03 UI-кит и H1-галерея

Дата: 27 октября 2026, Europe/Moscow.

## Область и итог

Коммит: `a263c21564037d3df4896fb4d4cf39a11829ef92` — `feat: add T03 design system and H1 component gallery`.
База сравнения: `0effb8db5270371bf871ba94a945de0c670b0d23` (`a263c21^`). Указанный коммит является HEAD. Diff: 70 файлов, 1812 добавленных и 87 удалённых строк.

**Найден один подтверждённый дефект P2: общая оболочка не защищает верхнюю панель и контент от горизонтальных системных insets.** На Pixel 7 / Android 13 в landscape с трёхкнопочной навигацией верхняя кнопка профиля размещается под боковой системной панелью и не реагирует на tap. P0/P1 не обнаружены. Исправления в исходники не вносились.

Прочитаны AGENTS.md, скилл android-code-review, README, решения и задача T03, карта документации, 04-design-system, инженерные правила, актуальные продуктовые ответы, навигационные требования и отчёт 17-t03-validation. Просмотрены Kotlin-компоненты UI-кита, tokens/theme/previews/icons, галерея, ресурсы и происхождение assets, изменения оболочки/Activity/manifest, unit/device-тесты и документационный diff. Domain/fake не менялись и повторно полностью не ревьюились.

До начала существовали: изменённый `docs/06-delivery-plan.md`, неотслеживаемые `docs/prompts/` и CODE_REVIEW.md. Их наличие зафиксировано. Добавленный пользователем абзац в плане не является частью ревьюируемого коммита. Предыдущие записи CODE_REVIEW.md сохранены побайтно, новая запись добавлена в конец.

## Подтверждённая находка

### [P2] Сохранить горизонтальные safe insets в оболочке и TopBar

Основное место: `app/src/main/kotlin/ru/eventpass/app/navigation/EventPassApp.kt:76–78`, в особенности `contentWindowInsets = WindowInsets(0)`.
Связанное место: `core/designsystem/src/main/kotlin/ru/eventpass/core/designsystem/EventPassNavigation.kt:40–45` — TopBar по умолчанию применяет только `WindowInsets.statusBars`.

**Условие:** альбомная ориентация, системная навигационная панель справа или слева. Воспроизведено на существующем Pixel_7 AVD, Android 13/API 33, 2400×1080 px, density 420, трёхкнопочная навигация.

**Сценарий:** открыть корень «Билеты», повернуть устройство в landscape, нажать аватар профиля в правом верхнем углу.

**Фактический результат:** аватар рисуется в области правой системной navigation bar; tap по нему не переводит в профиль. Экран остаётся «Мои билеты». Нижняя панель при этом учитывает свой navigationBars inset, что не защищает верхнюю панель и содержимое Scaffold.

**Доказательства текущего прогона:**

- `dumpsys window`: `mDisplayFrame=Rect(0, 0 - 2400, 1080)`; `ITYPE_NAVIGATION_BAR frame=[2274,0][2400,1080]`, `visible=true`, right inset 126 px.
- UI hierarchy: иконка с content-desc «Профиль» имеет bounds `[2279,132][2311,164]`, целиком внутри системной полосы.
- `adb shell input tap 2295 148` не открывает профиль; повторный dump содержит «Мои билеты», без текста корня профиля.
- Runtime-снимок: [.gradle/review-t03-landscape.png](F:/ASProjects/MospolytechProject/.gradle/review-t03-landscape.png).
- Hierarchy до и после tap: [.gradle/review-t03-landscape.xml](F:/ASProjects/MospolytechProject/.gradle/review-t03-landscape.xml), [.gradle/review-t03-landscape-tap.xml](F:/ASProjects/MospolytechProject/.gradle/review-t03-landscape-tap.xml).

**Причина и последствие:** Scaffold больше не передаёт стандартные горизонтальные системные отступы; TopBar ограничен statusBars, а content получает только inner padding при нулевых contentWindowInsets. Обработка navigationBars внутри BottomBar локальна для него. В результате доступное действие общей оболочки попадает в системную область и становится недоступным. Устройства с боковым cutout/freeform caption также требуют проверки, но отдельное воспроизведение их поведения не заявляется.

**Направление исправления:** явно распределить horizontal safeDrawing/systemBars/displayCutout insets между общей оболочкой, TopBar и контентом; сохранить единственную обработку top/bottom/IME без двойных отступов. Не ограничиваться дополнительным фиксированным padding для аватара: sidebar inset динамический. После исправления проверить portrait/landscape с gesture и three-button navigation, боковой cutout, доступность кнопок и клавиатуру. Регрессионная проверка должна подтверждать размещение/клик действия вне системной области, а не конкретное значение padding.

Основание: [официальная документация Android по window insets](https://developer.android.com/develop/ui/compose/system/insets) различает statusBars, navigationBars и безопасные области; Insets имеют также левую/правую стороны. Отсутствие полной H1 не отменяет этот уже воспроизведённый дефект.

## Остальные результаты ревью

| Область | Вывод и границы |
|---|---|
| Объём T03 | Реализованы базовые компоненты, локальный Inter, семантические цвета/размеры, галерея и панели. T03 отмечена READY_FOR_HUMAN, H1 не присвоена автоматически. Отсутствие продуктовых экранов T04/T05 не является дефектом этого коммита. |
| Архитектура / SOLID | UI-компоненты получают состояние и callbacks; нет Hilt, repository, DTO или бизнес-прав внутри designsystem. Галерея находится в src/demo, не вызывает операции fake/backend. Небольшие компоненты разделены по назначению; повода вводить ViewModel для локальных demo-переключателей нет. |
| Навигация | Сохранены три таба и decorators всех стеков. Галерея открывается отдельной записью, не заменяя underlying demo-entry. Повторный вход не дублируется, возврат/переключение табов/recreation сохраняют счётчик. Повтор demo link из галереи возвращает к прежней записи. Unit и device-проверки проходят. |
| Действия и состояния | EventPassButton блокирует onClick при disabled/loading. ConfirmDialog во время loading блокирует подтверждение и dismiss. ErrorState передаёт retry наружу, локальные примеры меняют только demo-счётчик. Новых подтверждённых ошибок в этих ветках не найдено; каждый визуальный вариант не прокликивался. |
| Поля и секреты | FormField использует BasicTextField с label/error semantics и KeyboardOptions. PasswordField переключает визуальное скрытие, не сохраняет пароль на диск; gallery text state через remember намеренно не переживает recreation. Disabled-пароль блокирует и переключатель видимости. Тест ввода и visibility проходит. |
| Выбор и доступность | Checkbox/switch/radio/tab имеют роли и checked/selected семантику, клики подняты на контейнеры. Действия имеют min touch size, высоты текста допускают рост; статусы содержат текст. Основные checked/selected/editable состояния проверены device-тестом. Это не полная TalkBack-проверка. |
| Тема, шрифты, assets | Inter упакован локально, веса объявлены в tokens, сетевой загрузки на старте нет. Присутствуют SVG-источники, VectorDrawable и тексты OFL/Apache; выборочно сверены исходные пути/размеры glyph. Полная независимая векторная/шрифтовая экспертиза и Figma pixel comparison не выполнялись. |
| Границы продукта | Domain/fake, auth, event-scoped права и гостевые QR-правила не менялись. Галерея явно помечена как демонстрация и не обещает реальных заявок/писем/билетов. |

Роли selection controls и минимальные touch targets сверены с [официальными accessibility defaults Compose](https://developer.android.com/develop/ui/compose/accessibility/api-defaults). Просмотрен существующий снимок `fields-360-font2.png` как материал предыдущего этапа; это не новая проверка всей матрицы размеров и не человеческая приёмка.

## Проверки в этой сессии

Рабочая папка: `F:\ASProjects\MospolytechProject`; JBR из установленной Android Studio1. Глобальные настройки Java/Git/Studio не менялись.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
.\gradlew.bat :app:assembleDemoDebug :app:lintDemoDebug :app:testDemoDebugUnitTest --rerun :core:domain:test :data:fake:test :core:platform:testDebugUnitTest :app:verifyDemoIsolation --offline --console=plain --max-workers=2
```

**BUILD SUCCESSFUL in 4s**, 153 задачи, 3 executed / 150 up-to-date. App unit test-задача выполнена заново: 6 тестов (2 ViewModel, 4 navigation), 0 failures/errors/skipped. Остальные 19 unit-тестов имеют успешные up-to-date результаты: domain 6, fake 12, platform 1. Всего по XML **25/25**, из них в этом прогоне повторно исполнены 6. Assemble и lint проверены инкрементально. Lint-отчёт: **0 errors, 6 прежних warnings о новых версиях**; это не проверка отсутствия уязвимостей. verifyDemoIsolation исполнена и успешна.

Для адресного воспроизведения использован существующий Pixel_7 AVD, запущенный отдельным экземпляром `emulator-5580` с `-read-only -no-snapshot -no-window -no-audio`; изменения userdata/настроек не сохраняются в исходный AVD. В него установлен текущий demo APK, временно включены three-button overlay и landscape. После воспроизведения возвращены portrait/gesture, затем выполнено:

```powershell
$env:ANDROID_SERIAL = 'emulator-5580'
.\gradlew.bat :app:connectedDemoDebugAndroidTest --offline --console=plain --max-workers=2
```

**BUILD SUCCESSFUL in 1m 28s**, **5/5 device-тестов на Pixel_7 / Android 13, API 33**, 0 failed/ignored. Проверены Hilt entry cleanup, вкладки/Back/recreation, deep links, возврат из галереи к существующему ViewModel и editable/checked/selected/password visibility. Прогон API 36.1 из отчёта T03 в этой сессии не повторялся. Успех пяти тестов не опровергает находку P2: они не включают landscape с боковой системной навигацией. Проверочный эмулятор остановлен через `adb -s emulator-5580 emu kill`.

Дополнительно:

- `git diff --check a263c21^ a263c21` — без замечаний.
- APK SHA-256: `7912b8c741a3b9f8b88704604a5ded3e112acbf4c1b280a5fbb3864499172601`, размер 32658438 байт. Совпадает со сборкой из отчёта T03; дефект воспроизведён именно на ней.
- Для воспроизведения: включить `com.android.internal.systemui.navbar.threebutton` через `cmd overlay enable-exclusive --category`, задать `accelerometer_rotation=0`, `user_rotation=1`, запустить MainActivity, снять `uiautomator dump`/`dumpsys window` и нажать аватар. Эти настройки применялись только к проверочному read-only экземпляру.
- Обычный запуск команд вновь завершился `helper_unknown_error: setup refresh had errors`; чтение и проверки выполнены после разрешённого запуска вне неисправного ограниченного окружения. Это не дефект Android-приложения.

## Ограничения и следующий шаг

Найденную P2 следует исправить перед завершением приёмки общей оболочки. В этом ревью исправление не выполнялось. Физическое устройство, API 28/37, вся матрица 360–412dp/fontScale, все keyboard/focus/error/loading варианты и полный TalkBack не проверены. Новая реальная смерть процесса не моделировалась; device-тесты проверяют recreation. Файл Figma в этой сессии не открывался: анализ соответствия опирался на локальные требования, происхождение assets и текущую реализацию; точная визуальная H1 остаётся человеку.

T03/H1 и T01 не отмечены принятыми. После исправления и относящейся к H1 проверки следующий этап по плану — T04 и нужная часть T02. Исходники, существующие пользовательские правки плана и docs/prompts не изменены; добавлена только эта запись CODE_REVIEW.md. Сборочные отчёты и адресные диагностические capture-файлы находятся в игнорируемых outputs/.gradle.

---

# Code Review — 4e3d0de, исправление боковых системных отступов T03

Дата: 27 октября 2026, Europe/Moscow.

## Область и итог

Коммит: `4e3d0de7e02479099d22933b7f17f7267a41440b` — `fix: keep T03 controls clear of side system bars`.
База: `a263c21564037d3df4896fb4d4cf39a11829ef92`. На момент проверки указанный коммит — HEAD. Diff: 9 файлов, 160 добавленных и 17 удалённых строк.

**Новых подтверждённых дефектов P0–P3 в изменённом коде приложения не обнаружено. Исходный P2 ревью a263c21 подтверждён как исправленный в проверенном сценарии с трёхкнопочной навигацией.** При этом весь device-прогон нельзя считать успешным: завершённый тест в жестовом режиме упал на системной инъекции ACTION_DOWN. Его причина и стабильность остаются отдельным вопросом проверки, описанным ниже. Ручная H1 не пройдена.

Прочитаны AGENTS.md и android-code-review, README, решения ADR-18 и связанные инженерные правила, задача T03, карта документации, актуальные B01–B08, изменение правил UI-кита и отчёт T03. Изучены весь изменённый код оболочки/TopBar, новый WindowInsetsRegressionTest, его контекст навигации, изменения версии и документационный diff. Это ревью исправления, не повторный полный аудит UI-кита и будущих продуктовых сценариев.

Перед началом существовали пользовательская правка `docs/06-delivery-plan.md`, `docs/prompts/` и CODE_REVIEW.md. Правки плана и prompts сохранены. Предыдущие записи отчёта сохранены побайтно; эта запись добавлена в конец. Исходники не изменялись.

## Проверка исправления прежнего P2

В `EventPassApp.kt` горизонтальные `safeDrawing` insets теперь применяются вокруг всего Scaffold через windowInsetsPadding. Это защищает одновременно верхнюю панель, нижнюю панель и контент. TopBar по умолчанию учитывает safeDrawing сверху и по горизонтали; внутри оболочки уже потреблённая горизонтальная часть не повторяется. IME padding остаётся у оболочки, вертикальные insets — у панелей; NavDisplay продолжает потреблять Scaffold inner padding.

Применённые windowInsetsPadding автоматически потребляют использованный inset, что соответствует [официальной документации Compose](https://developer.android.com/develop/ui/compose/system/insets-ui#inset-consumption). Статически оснований ожидать двойной горизонтальный padding в этом дереве не найдено.

Независимый прогон нового теста на Pixel_7 AVD / Android 13, API 33 / три кнопки прошёл: **1 тест, 0 failures**, три ориентации и реальные нажатия через систему. Свежие координаты:

| Режим / поворот | Безопасный горизонтальный диапазон | Bounds контента | Bounds profile action |
|---|---|---|---|
| Три кнопки, landscape 90° | 136…2274 px | 136…2274 px | 2106…2232 px |
| Три кнопки, reverse landscape 270° | 126…2264 px | 126…2264 px | 2096…2222 px |

В portrait проверка также прошла. Профиль открывался после инъекции экранного нажатия, выбранная вкладка подтверждалась тестом. Совпадение границ контента с безопасным диапазоном подтверждает отсутствие повторного горизонтального отступа в этих случаях.

Локальные доказательства: `.gradle/review-4e3-threebutton.log` и `.gradle/review-4e3-threebutton-insets.txt`. Результат старого red-прогона на a263c21 взят из отчёта разработки и заново в этой сессии не выполнялся.

## Незавершённый вопрос: жестовый device-тест падает на вводе

Место диагностики: `app/src/androidTest/kotlin/ru/eventpass/app/WindowInsetsRegressionTest.kt:85–94`, точка падения — строка 93, `assertTrue(automation.injectInputEvent(down, true))`.

На завершённом прогоне в gesture mode геометрические проверки profile/content прошли во всех трёх ориентациях. Для reverse landscape зафиксированы:

- insets: left=0, top=74, right=136, bottom=63 px;
- profile action: Rect(2096, 85, 2222, 211), то есть внутри безопасной области;
- content: Rect(0, 221, 2264, 849), также внутри безопасной области.

После этого `injectInputEvent(ACTION_DOWN, true)` вернул false. В системном logcat есть `Input event injection ... timed out`; JUnit завершился AssertionError на строке 93. **Фактический результат: BUILD FAILED, 1 тест, 1 failure, 0 errors/skipped.** Это не failure геометрического assert и не доказательство возвращения исходного перекрытия. Причина timeout (готовность системного input после rotation, тестовая синхронизация или особенность окружения) не установлена; ни одна из этих гипотез не объявляется подтверждённой находкой P2.

Дополнительно проверено обычное экранное нажатие вне instrumentation: APK установлен заново, приложение открыто в reverse landscape 270°, gesture mode; `adb shell input tap 2159 148` открыл профиль. Состояние подтверждено hierarchy-текстом «Это демонстрационная оболочка без входа и аккаунта.». Снимок до нажатия — [.gradle/review-4e3-gesture-reverse.png](F:/ASProjects/MospolytechProject/.gradle/review-4e3-gesture-reverse.png), hierarchy после — `.gradle/review-4e3-gesture-reverse-after.xml`. Предварительная попытка обычного нажатия пришлась на portrait и не используется как доказательство reverse landscape; итоговая проверка сопровождалась dumpsys с ROTATION_270 и снимком 2400×1080.

Сохранены `.gradle/review-4e3-gesture-retry.log` и `.gradle/review-4e3-gesture-completed-failure.txt`, стандартный connected XML также содержит failure. Отдельный более ранний жестовый запуск был прерван мной преждевременной остановкой эмулятора; он не засчитывается. Затем выполнен описанный выше завершённый запуск, в котором ошибка воспроизвелась без остановки эмулятора. Одна попытка перезапуска ожидала boot_completed и завершилась до запуска теста; она также не является тестовым результатом.

По [API UiAutomation](https://developer.android.com/reference/android/app/UiAutomation#injectInputEvent(android.view.InputEvent,%20boolean)) этот метод синхронизируется с системными window animations/surface operations. Это объясняет, почему успешные Compose geometry assertions сами по себе не гарантируют успешную системную инъекцию; точный источник timeout требует отдельного разбора. Следующий технический шаг — проверить устойчивость системного input-сценария после поворота и добиться воспроизводимого успешного gesture-прогона, сохранив реальную проверку нажатия. Скрывать timeout или заменять результат на «все тесты прошли» нельзя.

## Архитектура, сценарии и документация

- Изменение узкое: динамические системные отступы вместо фиксированных компенсаций под конкретный телефон. Insets находятся на границе Android UI, без новых зависимостей domain/fake и без влияния на event-scoped права.
- Контракты UI-компонентов сохранены: TopBar получает callbacks/параметры, не знает repository, ViewModel или NavController. Дополнительных абстракций и нарушений SOLID с конкретным последствием не выявлено.
- Типы маршрутов, stacks/decorators и entry ownership не менялись. Сохранение состояния T01 и продуктовые правила B01–B08 не переписывались. Новых корутин, хранилищ, доступа к персональным данным или сетевых операций нет.
- Новый тест проверяет наблюдаемое поведение: safe bounds и переход по настоящему экранному нажатию в portrait/landscape/reverse landscape. Он не зеркалит конкретное числовое значение padding. Его текущая воспроизводимость с жестами ограничена описанным failure.
- Версия 0.3.1-t03-demo / versionCode 3, README, ADR-18 и отчёт T03 согласованы с кодом. Записи о прошлых успешных device-прогонах разработки не подменяют текущий независимый результат. T03 остаётся READY_FOR_HUMAN; человек не отмечен принявшим H1.

## Запущенные проверки

PowerShell, рабочая папка `F:\ASProjects\MospolytechProject`, JBR из Android Studio1:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
.\gradlew.bat :app:assembleDemoDebug :app:lintDemoDebug :app:testDemoDebugUnitTest --rerun :app:verifyDemoIsolation --offline --console=plain --max-workers=2
```

**BUILD SUCCESSFUL in 4s**, 144 задачи, 3 executed / 141 up-to-date. Все **6 app unit-тестов исполнены повторно**, 0 failures/errors/skipped: DemoViewModelTest 2, NavigationStateTest 4. Assemble/lint инкрементальные; lint-отчёт — **0 ошибок / 6 прежних version warnings**. Demo isolation исполнена и успешна. Неизменённые domain/fake/platform в этом ревью повторно не тестировались; полный набор 25 unit-тестов не заявляется новым прогоном.

Адресная device-команда запускалась отдельно после выбора three-button и gesture overlay:

```powershell
$env:ANDROID_SERIAL = 'emulator-5580'
.\gradlew.bat :app:connectedDemoDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=ru.eventpass.app.WindowInsetsRegressionTest' --offline --console=plain --max-workers=2
```

- Three-button: **BUILD SUCCESSFUL in 1m 2s**, 1/1, все три ориентации.
- Gesture, завершённый независимый прогон: **BUILD FAILED in 1m 16s**, 1 тест / 1 failure на injectInputEvent; геометрия прошла, последнее нажатие автоматически не подтверждено.
- Весь набор из 6 device-тестов заново не запускался: это ограниченное исправление, проверялся непосредственно добавленный regression test. Результаты полного набора из docs/17 — результаты этапа разработки.

Использован отдельный read-only экземпляр существующего Pixel_7: `-port 5580 -read-only -no-snapshot -no-window -no-audio`. Userdata, режим навигации и повороты исходного AVD не сохранялись. После завершения диагностик эмулятор остановлен; глобальные настройки Java/Git/Studio не менялись.

Дополнительно:

- `git diff --check 4e3d0de^ 4e3d0de` — без замечаний.
- APK SHA-256: `cb6d2bf9247e11c7a54c7a37eb08135fadd47bb995df79b67e47af9b06f0115b`, совпадает с README/отчётом T03.
- Пользовательский diff плана остаётся только добавлением абзаца-ссылки на prompt; SHA-256 файла до/после проверок: `48ffcc1d87ccc15ec036fd86083a6aa6770dc8aae2f42273a6bd32c371ab2403`.
- Обычная командная среда по-прежнему не запускается: helper_unknown_error/setup refresh. Чтение и проверки выполнены через разрешённый запуск вне неисправного окружения; эта ошибка не считается дефектом приложения.

## Ограничения и следующий шаг

Новые source findings не сформированы, но жестовый автоматический regression check неуспешен и требует разбора до заявления о полностью зелёной матрице. Исправление исходного P2 с боковой navigation bar подтверждено; полный H1, TalkBack, IME во всех ориентациях, физическое устройство, API 28/37 и вся матрица fontScale/ширин остаются непроверенными. Никакие H-пункты и статусы задач агентом не изменены.

Исходники и посторонние изменения не тронуты. Результат — эта добавленная запись отчёта и обычные игнорируемые test/build/diagnostic outputs. Следующее действие: разобраться с gesture input timeout, затем ручная H1 на текущей сборке; дальнейший этап плана — T04 и соответствующая часть T02.

---

# Дополнение ревью — 28 октября 2026 — 2c035a8

## Область и итог

Проверен коммит `2c035a80f51d6e65bfb90e29f4fb2835ff858b18` (`test: synchronize system taps after display rotation`) относительно родителя `4e3d0de`. HEAD на момент проверки совпадает с этим коммитом. Изменены `WindowInsetsRegressionTest.kt`, README, план и отчёт T03; исходники приложения, зависимости и версия не менялись.

**Новых подтверждённых дефектов P0–P3 в пределах этого diff не обнаружено.** Доработка теста сохраняет проверку исходного сценария и даёт успешные результаты в сохранённых прогонах API 33. Прежний вопрос о неуспешном gesture regression check обновлён: окончательная версия теста имеет четыре успешных адресных прогона и успешный полный набор с жестами. Это подтверждено чтением журналов/XML разработки; новый независимый device-прогон в этом дополнении не выполнялся. Исторический failure из ревью `4e3d0de` остаётся достоверной записью своего запуска и сохранён выше.

Проверка выполнена по локальному `$android-code-review` с учётом прочитанных AGENTS.md, README, решений, задачи T03, карты документации и инженерных правил. Это дополнение по исправлению автоматической проверки, а не повторный полный аудит приложения. Статус T03 остаётся **READY_FOR_HUMAN**, H1 ожидается.

## Проверка теста и пользовательского сценария

Место: [WindowInsetsRegressionTest.kt](F:/ASProjects/MospolytechProject/app/src/androidTest/kotlin/ru/eventpass/app/WindowInsetsRegressionTest.kt).

- Строки 35–56: сохранены portrait, landscape и reverse landscape с прямым переходом 90° → 270°. Тест требует принятия системного поворота и ждёт совпадения конфигурации, фактического поворота display и фокуса окна, затем idle и кадра окна. Это устраняет прежнюю опору только на готовность Compose/configuration.
- Строки 57–69 и 78–87: профиль и контент по-прежнему проверяются относительно реальных systemBars/displayCutout по всем четырём сторонам. Центр кнопки переводится в экранные координаты с учётом положения decorView. После системного нажатия обязательны видимый экран профиля и выбранная вкладка «Профиль». Замены этого перехода на семантическое нажатие нет; `performClick` используется для возврата на «Билеты» между проверками.
- Строки 91–102: ожидание кадра выполняется через callback на API 29+ с отдельной веткой для API 28, сигнал ожидается вне `onActivity`. Ожидание latch ограничено пятью секундами. Проверка фактического открытия профиля остаётся необходимой: готовность кадра сама по себе не доказывает доставку ввода.
- Строки 105–114: используется `input touchscreen -d <displayId> tap <x> <y>` через UiAutomation; координаты вычисляются из bounds, descriptor закрывается через `use`. Пустой stdout не является самостоятельным доказательством успешного нажатия, поэтому проверки экрана на строках 67–69 существенны и сохранены.
- Строки 72–75: в `finally` возвращается исходный поворот либо снимается блокировка при включённом автоповороте; отказ системы не подавляется.

В изменении нет повторных попыток нажатия, перехвата timeout с объявлением успеха или удаления проверок безопасных границ. Подтверждённых нарушений архитектуры/SOLID с наблюдаемым последствием не выявлено: изменение находится в androidTest, не добавляет зависимостей в ViewModel/Compose приложения, repository/DI/domain и не меняет сценарии доступа по eventId. Будущие этапы T04 и далее в область этого diff не входят.

README, план и `docs/17-t03-validation.md` согласованы с содержимым теста: перечислены итоговые и промежуточные результаты, сохранены версия APK и ожидающая H1. Неподтверждённая причина системной заморозки не объявлена установленным дефектом приложения.

## Сверка сохранённых доказательств

Ниже — результаты запусков разработки, выполненных 27 октября; при дополнении отчёта прочитаны их журналы, XML и выбранные строки logcat. Они не выдаются за новые запуски ревьюера.

| Проверка | Прочитанное доказательство | Результат |
|---|---|---|
| Четыре адресных gesture-прогона | `.gradle/t03-input-frame-1.log` … `t03-input-frame-4.log` | Каждый завершил 1 тест и BUILD SUCCESSFUL: 22 / 18 / 20 / 18 секунд |
| XML адресных повторов 2–4 | `.gradle/t03-input-evidence/frame-2`, `frame-3`, `frame-4` | По 1 тесту, failures/errors/skipped = 0; отдельный XML первого повтора в этом наборе не сверялся |
| Полный набор с жестами | `.gradle/t03-input-frame-gesture-suite.log`, XML в `final-gesture` | 6 тестов (suite 1 + 4 + 1), failures/errors/skipped = 0, BUILD SUCCESSFUL in 42s |
| Полный набор с тремя кнопками | `.gradle/t03-input-frame-threebutton-suite.log`, XML в `final-threebutton` | 6 тестов (suite 1 + 4 + 1), failures/errors/skipped = 0, BUILD SUCCESSFUL in 42s |

В logcat окончательных полных наборов видны profile/content bounds и завершение системного нажатия для всех трёх поворотов. Для reverse landscape с жестами: profile `2096…2222 px`, content `0…2264 px`, безопасная правая граница `2264 px`; с тремя кнопками: profile `2096…2222 px`, content `126…2264 px`, безопасный диапазон `126…2264 px`. Отсутствие failure в тесте также подтверждает прохождение последующих assertions открытия профиля, а не только строки «tap completed».

В `.gradle/t03-input-dispatcher.txt:1210` действительно записано `DispatchFrozen: true`, на строке 1303 — `InboundQueue: length=1`. В следующем снимке `.gradle/t03-input-dispatcher-2.txt:1210` — `DispatchFrozen: false`, очередь пуста. Это подтверждает наблюдение замороженного диспетчера и последующее восстановление; **точный внутренний источник заморозки не установлен**. Неуспешные промежуточные варианты перечислены в отчёте T03 и не засчитываются в итоговую зелёную матрицу.

## Проверки, выполненные при дополнении ревью

PowerShell, рабочая папка `F:\ASProjects\MospolytechProject`:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
.\gradlew.bat :app:assembleDemoDebugAndroidTest :app:lintDemoDebug :app:testDemoDebugUnitTest :app:verifyDemoIsolation --offline --console=plain --max-workers=2
```

**BUILD SUCCESSFUL in 24s**, 162 actionable tasks: 2 executed / 160 up-to-date. Сборка тестового APK проверена инкрементально; анализ lint и задача unit-тестов — UP-TO-DATE. Demo isolation исполнена и успешна. Это не новый прогон шести unit-тестов и не повторный полный lint-анализ; заявленные разработкой 6/6 unit и 0 ошибок / 6 прежних lint warnings остаются результатами этапа разработки. Весь набор domain/fake/platform заново не запускался.

Дополнительно выполнены:

- `git diff --check 2c035a8^ 2c035a8` — замечаний нет.
- Проверка diff: в коммите ровно четыре файла, изменения приложения и APK в него не входят.
- SHA-256 текущего `app/build/outputs/apk/demo/debug/app-demo-debug.apk`: `cb6d2bf9247e11c7a54c7a37eb08135fadd47bb995df79b67e47af9b06f0115b`, совпадает с предыдущим ревью и отчётом T03; сборка для H1 остаётся 0.3.1-t03-demo / code 3.
- Git status до записи: пользовательская правка `docs/06-delivery-plan.md`, неотслеживаемые `docs/prompts/` и `CODE_REVIEW.md`. SHA-256 плана: `f51f78c737a90e2bab0fe914dd00f819c18ae4f73276781907d7735529847d7b`.

Обычная командная среда не запускается из-за `helper_unknown_error: setup refresh had errors`; чтение, проверки и сохранение отчёта выполнены через разрешённый запуск вне неё. Это ограничение среды, не дефект проекта.

## Оставшиеся ограничения и следующий шаг

На основании кода и прочитанных доказательств прежний вопрос о падении gesture regression check **закрыт для проверенной матрицы Pixel 7 AVD / API 33**. Четыре повтора не доказывают отсутствие нестабильности во всех окружениях; frame/idle синхронизация и shell tap не устанавливают внутреннюю причину прежнего DispatchFrozen. Подтверждённое ранее исправление исходного P2 не пересматривается.

Новый device-прогон ревьюера, runtime ветки API 28, физическое устройство, повторные проверки API 36/37, полная TalkBack/IME/визуальная матрица не выполнены. **H1 по-прежнему должна пройти у человека на APK 0.3.1-t03-demo**: профиль при поворотах с жестами и тремя кнопками, затем клавиатура и отступы галереи. После H1 — T04 по плану. Статусы задач и решения при ревью не менялись.

Результат этого дополнения — только новая запись в CODE_REVIEW.md. Предыдущие записи сохранены побайтно; исходники приложения и теста, пользовательская правка плана и prompts не изменялись.

---

# Ревью — 28 октября 2026 — cfc0624 (T02.2 / auth foundation)

## Область и итог

Проверен `cfc06242adea05143f41a9e286322ee77b456590` (`feat: add T02.2 auth and navigation foundation ahead of H1`) относительно `2c035a8`; HEAD совпадает с проверяемым коммитом. Область: 24 изменённых файла, 1213 добавлений / 35 удалений — auth-порты, общий fake, auth-only persistence, session/navigation model, parser/handler, DI, тесты и документация. Изучены изменённые исходники и тесты, существующий FakeEventPassEnvironment/репозиторные вызовы, NavigationState и контекст текущей технической оболочки.

Применён локальный `$android-code-review`. Прочитаны AGENTS.md, README, решения и T02.2/T04 из плана; по карте документации — инженерные правила, контракты, навигация, B01–B08 и `docs/18-t04-validation.md`. Условия независимого этапа до H1 из prompt учитывались как описание задачи. Содержимое sources не использовалось как инструкции.

**Подтверждены две находки P2. P0/P1 не обнаружены в проверенной области.** Обе воспроизведены на скомпилированном коде текущего коммита отдельной JVM-проверкой без изменения исходников приложения или штатных тестов. Существующие 46 unit-тестов при повторном исполнении проходят, но не покрывают эти негативные переходы. До подключения новой auth-модели к Activity/UI нужно исправить находки и добавить соответствующие регрессии.

Полный auth UI не заявлялся готовым и не включён: отсутствие экранов G03–G05, outbox UI, новых Activity links и live session/decorator wiring соответствует явно отложенному объёму T04. Эти пункты не выдаются за новые дефекты коммита. T03 остаётся READY_FOR_HUMAN, T04 — IN_PROGRESS, H1/H2 не подтверждены.

## Подтверждённые находки

### 1. [P2] Отменённый вход публикует сессию после блокирующей записи

**Место:** [FakeAuthRepository.kt:113](F:/ASProjects/MospolytechProject/data/fake/src/main/kotlin/ru/eventpass/data/fake/product/FakeAuthRepository.kt:113), строки 113–115. Связанный потребитель — `SessionCoordinator.kt:58–73`.

`command` проверяет активность корутины на строках 97 и 101, затем синхронно вычисляет результат, выполняет `storage.write(...)` и без повторной проверки публикует `state.value = next`. Если signIn отменён во время PBKDF2/записи, уже выполняющийся синхронный участок на IO продолжает работу. Возврат из withContext может завершиться CancellationException для вызывающего кода, а в общем StateFlow уже будет Authenticated. Независимый от отменённого запроса collector SessionCoordinator принимает новую сессию и открывает Shell.

**Воспроизведение:** изолированный FakeAuthStorage задерживает write при входе `alex@example.com`; используется отдельный последовательный dispatcher для координатора. Пока запись удерживается, Job вызова `coordinator.signIn` отменяется; затем запись разрешается завершить. Результат:

```text
LOGIN_CANCEL callerCancelled=true; stateClass=Authenticated
LOGIN_CANCEL coordinatorGate=Shell
```

Таким образом, отмена пользовательского запроса не предотвращает поздний вход и навигационный переход. Это не race с logout: отдельного signOut в воспроизведении нет. Проверенный ранее generation guard после logout продолжает работать, но сам по себе не закрывает отмену запроса. В текущем APK auth UI не подключён; дефект относится к уже реализованному repository/coordinator и проявится при использовании этой модели для отменяемого входа.

**Направление исправления:** определить границу принятия сессии и проверять актуальность/активность операции после длительного синхронного IO/вычислений перед публикацией новой сессии. Для signIn сессия не записывается на диск, поэтому проверка перед её публикацией не требует откатывать сохранённую сессию. Отдельно сохранить корректную семантику уже зафиксированных изменений аккаунта/challenge. Добавить регрессию с отменой во время write и живым collector координатора: результат не должен переводить отменённый вход в Shell. Не преобразовывать CancellationException в сетевую ошибку.

**Основание:** инженерные правила требуют контролировать устаревшие результаты и coroutine lifecycle; существующий cancellation-тест отменяет только hook перед критической секцией и этот случай не проверяет. Поведение возврата withContext с отменой соответствует [официальному описанию prompt cancellation](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/with-context.html); конкретная проблема подтверждена на зависимостях проекта, а не выведена только из документации другой версии.

### 2. [P2] Временный отказ подтверждения навсегда помечает requestId обработанным

**Место:** [AuthLinkHandler.kt:11](F:/ASProjects/MospolytechProject/app/src/main/kotlin/ru/eventpass/app/navigation/auth/AuthLinkHandler.kt:11), строки 11–17.

requestId добавляется в `handled` до вызова `confirmEmail`. Если репозиторий возвращает Network/Timeout/StorageUnavailable, обработчик возвращает Failed, но ID остаётся в наборе. Следующая явная попытка обработать тот же delivery возвращает Duplicate, не обращаясь к репозиторию. Подтверждение фактически не состоялось, challenge не потреблён, но обработчик больше не допускает продолжение этой операции. Смена requestId или создание нового handler обходит запрет; штатного способа повторить тот же неуспешный запрос в контракте нет.

**Воспроизведение:** создать аккаунт, взять валидную ссылку из demo outbox, установить `failNextAuth(Network)`, вызвать один handler с `delivery-1` дважды, затем с `delivery-2`:

```text
LINK_TRANSIENT first=Failed(error=Network); retrySameId=Duplicate; freshDelivery=EmailConfirmed(email=review@example.com)
```

Успех с новым delivery показывает, что ссылка оставалась валидной: блокировка вызвана преждевременной дедупликацией на клиенте. Это отдельная проблема от одноразовости challenge на backend. Тест успешного подтверждения/повторного consumed challenge проходит и не покрывает временный отказ.

**Направление исправления:** различать выполняющийся запрос и успешно обработанный результат; при временном отказе либо отмене освобождать возможность явного повтора, сохраняя защиту от параллельных дублей. При необходимости повторно отдавать сохранённый успешный результат вместо повторной мутации. Добавить проверки Network → ручной retry того же requestId → success и сохранения дедупликации после успеха. Автоматический retry не требуется. Activity-доставка ещё не подключена, поэтому это дефект подготовленного handler, а не заявление о сломанном текущем UI flow.

## Документация, сценарии и архитектура

- B02/B03/B04 отражены в реализованном срезе: guest submit не создаёт аккаунт; signUp/confirm/reset не логинят; после отдельного подтверждённого входа билеты выбираются по email аккаунта; return destination содержит только Event/eventId или RootTab без анкеты и автоматического submit. Проверено кодом и повторным исполнением unit-тестов.
- AuthRepository и модели находятся в чистом domain; fake подключён за портом через demo DI. Account/session используются тем же environment, что события и билеты; глобального User.role не добавлено. Новых нарушений MVVM/SOLID с отдельным подтверждённым последствием сверх находок выше не установлено. DTO, Android Context и UI resources в domain не внесены.
- AuthSecret и confirm-link redacted при toString, тип ссылки с секретом не сериализуемый. Snapshot записывает account/proof/hash/deadline/consumption, не содержит активную сессию, raw password/token/code, outbox или guest QR cache. Android adapter расположен в src/demo, использует noBackupFilesDir и AtomicFile. Это demo backend, не проверка production-протокола авторизации или готовности криптографической схемы к реальным аккаунтам.
- Подтверждение одноразово, resend заменяет предыдущий challenge, cooldown/expiry управляются Clock; reset code выдаёт grant, новый пароль переживает восстановление backend, сброс не подтверждает email. IOException не публикует подготовленный success. Неподдерживаемая/повреждённая схема приводит к Unknown/StorageUnavailable; сценарий reset явно служебный.
- Успешно проверены обычный вход → Tickets, разрешённый возврат с однократным потреблением, Unknown/Guest, смена scope, logout/expiry и поздний ответ после logout, Forbidden без login loop на уровне модели. Наличие `clearPrivateState()` не доказывает уничтожение root ViewModel/decorators; эта интеграция явно оставлена на следующий этап в docs/18.
- Parser принимает allowlist искусственных event/confirm путей, отвергает неожиданные host/port/userinfo/query/fragment/encoded paths. MainActivity/manifest и продуктовый host в diff не подключались; cold/warm auth UI flow не заявляется проверенным.
- README, ADR-19, архитектура, контракты и отчёт T04 согласованно обозначают границу foundation. Обобщённое утверждение о проверенной отмене следует читать с ограничением текущих тестов: отмена во время write не покрыта и воспроизвела находку 1. Статусы и документация при ревью не исправлялись.

## Выполненные независимые проверки

PowerShell, рабочая папка `F:\ASProjects\MospolytechProject`, JBR из Android Studio1:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
.\gradlew.bat :core:domain:test --rerun :data:fake:test --rerun :app:testDemoDebugUnitTest --rerun :core:platform:testDebugUnitTest --rerun :app:assembleDemoDebug :app:assembleDemoDebugAndroidTest :app:lintDemoDebug :app:verifyDemoIsolation --offline --console=plain --max-workers=2
```

**BUILD SUCCESSFUL in 11s**, 186 actionable tasks: 6 executed / 180 up-to-date. Все четыре unit-задачи исполнены повторно; XML сверен:

| Набор | Tests | Failures / errors / skipped |
|---|---:|---|
| core:domain | 7 | 0 / 0 / 0 |
| data:fake | 25 | 0 / 0 / 0 |
| app | 12 | 0 / 0 / 0 |
| core:platform | 2 | 0 / 0 / 0 |
| Итого | 46 | 0 / 0 / 0 |

Assemble обоих APK и lint analysis инкрементальные, не полная пересборка/повторный анализ с нуля. Существующий `app/build/reports/lint-results-demoDebug.xml` содержит **0 errors / 4 warnings**; новых baseline/exceptions не добавлено. Demo isolation исполнена: только demoDebug, fake отсутствует в main implementation.

Для двух находок выполнена отдельная диагностическая Java-программа на реальном unit runtime classpath, JBR 21; приложение и штатные тесты не менялись. Она вызывает скомпилированные FakeEventPassEnvironment/AuthLinkHandler/SessionCoordinator и блокирует только инъектируемый FakeAuthStorage, использует отдельный UI dispatcher и cancellation Job. Обе проверки детерминированно подтвердили описанные неправильные результаты, включая Shell после отмены; диагностические assertions прошли. Это успешное воспроизведение дефектов, не зелёная проверка ожидаемого поведения.

Артефакты в игнорируемом `.gradle/review-cfc0624/`: `ReviewAuthRepro.java`, `ReviewAuthRepro.class`, `repro.log`, `classpath.init.gradle`, `classpath.txt`. Команды получения classpath и воспроизведения:

```powershell
.\gradlew.bat :app:reviewAuthClasspath -I .gradle/review-cfc0624/classpath.init.gradle --offline --console=plain --max-workers=2
$reviewRuntime = [IO.File]::ReadAllText((Join-Path (Get-Location).Path '.gradle/review-cfc0624/classpath.txt'))
$reviewWork = Join-Path (Get-Location).Path '.gradle/review-cfc0624'
& "$env:JAVA_HOME\bin\javac.exe" -cp $reviewRuntime -d $reviewWork (Join-Path $reviewWork 'ReviewAuthRepro.java')
& "$env:JAVA_HOME\bin\java.exe" -cp ($reviewWork + ';' + $reviewRuntime) ReviewAuthRepro
```

Первый диагностический вызов задачи classpath завершился ошибкой чтения Gradle provider до выполнения producer task. Во внешнем init script добавлена зависимость от существующей unit-задачи; повтор успешен за 2s (сама unit-задача UP-TO-DATE). Это ошибка первоначальной организации диагностики, не дополнительная находка в приложении. Конфигурация проекта не менялась.

Дополнительно:

- `git diff --check cfc0624^ cfc0624` — без замечаний.
- APK SHA-256: `f65c299c266351317ce03ef39958dc373e27eca2c177048323f375d8440d9f95`, совпадает с README/отчётом T04. Текущая сборка — 0.4.0-t04-foundation-demo / code 4; прежний hash T03 относится к исторической сборке.
- `adb devices` — подключённых устройств нет. Эмулятор и пользовательские AVD при этом ревью не запускались.
- Сверены сохранённые `.gradle/t04-final-device.log` и connected XML: 7 тестов API 33 (1 AuthFoundationContract + 1 EntryOwnership + 4 NavigationSmoke + 1 WindowInsetsRegression), failures/errors/skipped = 0, BUILD SUCCESSFUL in 47s. XML timestamp `2026-10-28T14:36:45`. **Это результаты разработки, а не новый независимый device-прогон ревьюера.**

## Вопросы, допущения и ограничения

- Принята документированная граница до H1: host lifecycle/foreground expiry refresh, уничтожение приватных saved-state/owners, UI ошибок, явный retry, outbox controls и доставка Activity links должны быть проверены при оставшейся интеграции T04. Наличие моделей не заменяет эти проверки.
- Нового runtime-прогона API 33, проверки API 28/37, физического устройства, настоящего process death auth, TalkBack, клавиатуры/шрифтов и auth UI smoke не было. Копия backend в unit/device contract доказывает чтение snapshot новым экземпляром, а не сама по себе полноценную смерть Android-процесса.
- Figma при этом ревью не перечитывалась: UI не менялся; проверялись согласованные сценарии и foundation, а точная визуальная приёмка остаётся H1/следующим UI-этапом.
- Сроки/password policy, auth snapshot и demo-сессия рассматриваются как временные defaults; refresh/JWT/OAuth/API/real mail/App Links и release-готовность не подтверждаются.
- Исходный CODE_REVIEW.md сохранён побайтно с добавлением этой записи. Исходники, зависимости, решения и статусы задач не менялись. До и после записи пользовательский `docs/06-delivery-plan.md` сохранён (SHA-256 `a7bf80502b7eb5b302f96b255d598a63a2f9654c884737c1f21d9c77dcc43035`); `docs/prompts/` не изменялся. Кроме отчёта созданы обычные игнорируемые диагностические/build outputs.

**Следующий технический шаг:** исправить две находки отдельным разрешённым изменением и добавить регрессии на позднюю отмену и временный отказ ссылки; затем продолжить оставшуюся T04 в соответствии с условием H1. Ни H1, ни H2 агентом не закрыты.

---

# Ревью — 28 октября 2026 — 055c14b (исправления auth)

## Область и итог

Проверен `055c14b4ce82c69affb37fae6f39483ae17e732b` (`fix: respect auth cancellation and retry failed confirmation deliveries`) относительно `cfc0624`. HEAD совпадает с проверяемым коммитом. В diff 10 файлов: 262 добавления / 29 удалений — FakeAuthRepository, AuthLinkHandler, шесть регрессий, версия приложения и документация. Исходники рассматриваются только для чтения.

Применён `$android-code-review`; учтены AGENTS.md, README, ADR-19 и прочие относящиеся решения, T02.2/T04 из плана, карта документации, правила repository/coroutine lifecycle из docs/14, B01–B08 и актуальный отчёт docs/18. Изучены полный изменённый command/handler, новые тесты, контекст SessionCoordinator и общего fake, неизменённые правила snapshot и текущая граница UI-интеграции.

**Две находки P2 из cfc0624 подтверждены как исправленные в проверенных сценариях. Найдена одна новая P2: принятие сессии на caller захватывает монитор, который другие операции удерживают во время IO, и может блокировать UI-поток.** P0/P1 в проверенной области не обнаружены. Существующие 52 unit-теста повторно проходят; новая находка воспроизведена отдельно на compiled runtime проекта.

Это ревью узкого исправления, не полный повторный аудит всех модулей или будущих экранов. Auth UI, Activity links и живое подключение coordinator/decorators по-прежнему явно отложены до H1; отсутствие этой интеграции не оформляется как дефект коммита. T04 остаётся IN_PROGRESS, T03 — READY_FOR_HUMAN, H1/H2 не подтверждены.

## Подтверждённая находка

### 1. [P2] Не блокировать caller общим монитором, удерживаемым на время дисковой записи

**Место:** [FakeAuthRepository.kt:139](F:/ASProjects/MospolytechProject/data/fake/src/main/kotlin/ru/eventpass/data/fake/product/FakeAuthRepository.kt:139), строки 139–140. Связанный участок: строки 108–129, где тот же `lock` охватывает вычисление `action(s)` и `storage.write(...)`.

Новая фаза session acceptance выполняется после возврата из `withContext(Dispatchers.IO)` на контексте caller и использует обычный `synchronized(lock)`. SessionCoordinator предполагает вызов на UI dispatcher и напрямую вызывает auth.signIn. Другая auth-команда может занять этот монитор на IO между подготовкой signIn и возобновлением caller. Тогда UI-поток синхронно ждёт завершения чужой записи/вычислений; dispatch на IO внутри предыдущей фазы не делает весь signIn безопасным для вызова с main.

**Условие:** signIn уже подготовил результат на IO, его caller ещё не возобновлён; параллельная команда (например, обработка подтверждения/запрос reset-кода) начинает долговечную мутацию и удерживает общий монитор во время медленной записи. Возобновлённый signIn упирается в строку 139. Для воспроизведения не нужны повторный signIn, повреждённый snapshot, logout или нарушение generation; это две разрешённые операции общего AuthRepository.

**Независимое воспроизведение:** отдельный queue dispatcher задаёт границу после IO signIn. Затем `requestPasswordResetCode("host@example.com")` выполняется в другой корутине, инъектируемый storage задерживает её write. Продолжение signIn запускается на последовательном потоке `review-ui-caller`; следующая задача этого же executor не может выполниться. ThreadMXBean фиксирует:

```text
CALLER_LOCK state=BLOCKED; uiTickBlocked=true; owner=DefaultDispatcher-worker-1
CALLER_LOCK frame=app//ru.eventpass.data.fake.product.FakeAuthRepository.command(FakeAuthRepository.kt:139)
CALLER_LOCK released=true; loginCompleted=true
```

Тестовая UI-задача не исполнилась за 250 ms и продолжила работу только после освобождения storage. Число 250 ms — диагностический интервал проверки, не оценка длительности реального IO. На устройстве ANR не воспроизводился: доказана блокировка caller на реальном коде с контролируемой задержкой; при вызове с main такая зависимость означает зависание обработки UI на время чужой операции и риск ANR при длительном ожидании. Этот класс проблемы описан в [официальных рекомендациях Android о lock contention](https://developer.android.com/topic/performance/views/vitals/anr-views).

**Последствие:** после подключения auth к UI медленная параллельная auth-операция блокирует кадры и обработку ввода во время завершения входа. Пока монитор занят, отмена корутины сама по себе не освобождает заблокированный поток; проверка активности внутри synchronized исполнится лишь после захвата монитора. Текущий APK ещё не подключает auth UI, поэтому это дефект реализованного foundation, а не заявление о наблюдавшемся ANR текущей оболочки.

**Направление исправления:** согласовать session acceptance с изменениями общего backend через приостанавливаемую синхронизацию либо другой механизм, при котором main не ожидает монитор длительной IO-секции. Сохранить проверки activity/generation/account/expiry и отсутствие отменяемого перехода dispatcher после публикации сессии. Не возвращать публикацию в исходный IO-участок, поскольку это вернёт прежний P2. Добавить регрессию «подготовленный вход + другая заблокированная запись»: UI-marker должен выполняться до освобождения записи, а cancellation/logout по-прежнему не должны принимать устаревшую сессию.

## Проверка двух прежних P2

### Отмена signIn — исправлена в проверенных границах

На строках 123–126 IO возвращает подготовленную сессию без её публикации. После отменяемого return hop выполняются проверки активности, а под общей блокировкой — generation, healthy, неизменность аккаунта и срок сессии. При успехе актуальное состояние копируется с новой сессией, не перезаписывая чужие изменения целиком. После публикации нет дополнительного suspend/смены dispatcher. Это закрывает отмену во время write и отмену до возобновления caller, включая отсутствие промежуточного Authenticated у наблюдателя.

Повторено независимое воспроизведение из прошлого ревью на исправленном коде с живым SessionCoordinator и отменой во время write:

```text
LOGIN_CANCEL callerCancelled=true; stateClass=Guest
LOGIN_CANCEL coordinatorGate=Login
```

Новые штатные тесты дополнительно проверяют cancellation перед возобновлением caller, logout в той же паузе, отсутствие промежуточного Authenticated и возможность последующего нормального входа. Все прошли.

Документированная граница корректна: отмена уже принятого входа не равнозначна logout; долговечные account/challenge изменения, которые успели записаться, публикуются в memory для согласованности snapshot. Тест отменённого во время записи signUp проверяет ту же неподтверждённую учётную запись в холодном backend. Этот случай не выдаётся за гарантию отката серверной мутации при cancellation.

### Повтор подтверждения после отказа/отмены — исправлен

AuthLinkHandler разделяет `inFlight` и `handled`, коротко синхронизирует доступ к наборам, выполняет репозиторный вызов вне monitor, освобождает inFlight в finally. AuthLinkResult.Failed не фиксируется как обработанный; успешный результат и локальный Invalid фиксируются. Нет автоматического retry или второго вызова для параллельного дубля.

Независимый повтор прежнего Network-сценария:

```text
LINK_TRANSIENT first=Failed(error=Network); retrySameId=EmailConfirmed(email=review@example.com); freshDelivery=Failed(error=ConsumedChallenge)
```

Retry того же delivery теперь подтверждает почту. Новый requestId после уже совершённого подтверждения корректно получает ConsumedChallenge от backend. Штатные регрессии проверяют Network/Timeout/StorageUnavailable, отсутствие автоматических повторов, Duplicate после успеха, параллельный дубль и retry после отмены в hook. Сессия при подтверждении не создаётся.

## Соответствие документации, архитектуре и сценариям

- README, план, ADR-19, navigation/contracts и docs/18 отражают новые границы отмены/retry, актуальную версию 0.4.1-t04-foundation-demo / code 5 и ожидающую H1. Версии зависимостей и snapshot schema 1 не менялись.
- Изменение сосредоточено в repository implementation и handler; domain-порт, UI/Compose, DI bindings и модели event-scoped доступа не переписывались. Fake по-прежнему скрыт за AuthRepository; глобальные роли, постоянный guest cache, сохранение анкеты и автоматическая регистрация после login не добавлены.
- SignUp/confirm/reset не создают сессию; reset и подтверждение требуют ручного входа. Общие accounts/session для events/tickets сохранены. Пароли/коды/URI не добавлены в SavedStateHandle/serializable routes; snapshot не сохраняет raw secrets, активную сессию или outbox.
- Prepared session проверяется по generation и актуальному account/expiry перед принятием; успешная acceptance сохраняет текущие accounts/challenges/прочие изменения. Старый ответ после logout не активирует прежний scope. Эти сценарии покрыты новыми и существующими тестами.
- Дополнительных подтверждённых нарушений SOLID/MVVM с самостоятельным последствием не найдено. Новая проблема относится к безопасности вызова suspend API с UI dispatcher, а не к стилю синхронизации.
- Сценарии live root owners/decorators, foreground refresh, новый Login startup, Activity confirmation и outbox UI остаются явно запланированной интеграцией после H1; не объявляются реализованными по наличию модели и unit-тестов.

## Выполненные проверки

PowerShell, `F:\ASProjects\MospolytechProject`, JBR Android Studio1:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
.\gradlew.bat :core:domain:test --rerun :data:fake:test --rerun :app:testDemoDebugUnitTest --rerun :core:platform:testDebugUnitTest --rerun :app:assembleDemoDebug :app:assembleDemoDebugAndroidTest :app:lintDemoDebug :app:verifyDemoIsolation --offline --console=plain --max-workers=2
```

**BUILD SUCCESSFUL in 8s**, 186 actionable tasks: 6 executed / 180 up-to-date. Четыре unit-задачи заново исполнены, XML сверен:

| Модуль | Tests | Failures / errors / skipped |
|---|---:|---|
| core:domain | 7 | 0 / 0 / 0 |
| data:fake | 25 | 0 / 0 / 0 |
| app | 18 | 0 / 0 / 0 |
| core:platform | 2 | 0 / 0 / 0 |
| Всего | 52 | 0 / 0 / 0 |

Шесть новых тестов AuthFoundationRegressionTest входят в 18 app tests. Сборка приложения/тестового APK и lint analysis инкрементальные. Существующий lint XML: **0 errors / 4 warnings**. verifyDemoIsolation исполнена и успешна; новые baseline/исключения не добавлялись.

Отдельно скомпилированы и исполнены две диагностические Java-программы на реальном unit runtime classpath текущей сборки, с JBR 21 и зависимостями проекта. Они не изменяют приложение/штатные тесты и используют только инъектируемый storage, корутины и управляемый dispatcher:

- `ReviewAuthFixed` — независимое подтверждение исправления двух старых сценариев, результат Guest/Login и успешный ручной retry.
- `ReviewCallerLock` — воспроизведение новой блокировки caller, с проверкой Thread.State.BLOCKED и stack frame строки 139. Assertions подтверждают наличие проблемы, а не ожидаемое безопасное поведение.

Доказательства — `.gradle/review-055c14b/fixed.log`, `caller-lock.log`; исходники диагностик и classpath/init script находятся рядом. Вспомогательная внешняя задача classpath выполнена успешно за 2s, unit-задача при ней UP-TO-DATE. Конфигурация проекта не менялась. Для повторения:

```powershell
.\gradlew.bat :app:reviewAuthClasspath -I .gradle/review-055c14b/classpath.init.gradle --offline --console=plain --max-workers=2
$reviewWork = Join-Path (Get-Location).Path '.gradle/review-055c14b'
$reviewRuntime = [IO.File]::ReadAllText((Join-Path $reviewWork 'classpath.txt'))
& "$env:JAVA_HOME\bin\javac.exe" -cp $reviewRuntime -d $reviewWork (Join-Path $reviewWork 'ReviewAuthFixed.java') (Join-Path $reviewWork 'ReviewCallerLock.java')
& "$env:JAVA_HOME\bin\java.exe" -cp ($reviewWork + ';' + $reviewRuntime) ReviewAuthFixed
& "$env:JAVA_HOME\bin\java.exe" -cp ($reviewWork + ';' + $reviewRuntime) ReviewCallerLock
```

Дополнительно:

- `git diff --check 055c14b^ 055c14b` — без замечаний.
- SHA-256 APK: `518e3c5b3fcd88bae3c339083e9858c1b802d5b0e38f847438fd504031257f46`, совпадает с README/docs/18; текущая сборка 0.4.1-t04-foundation-demo / code 5.
- `adb devices` — устройств нет, эмулятор в этой проверке не запускался.
- Прочитаны `.gradle/t04-p2-device.log` и отдельные XML из `.gradle/t04-p2-evidence`: 7 тестов (1 AuthFoundationContract, 1 EntryOwnership, 4 NavigationSmoke, 1 WindowInsetsRegression), failures/errors/skipped = 0, timestamp `2026-10-28T16:19:35`, BUILD SUCCESSFUL in 46s. Это сохранённые результаты разработки; новым независимым device-прогоном они не называются.

## Ограничения, вопросы и следующий шаг

Проверка блокировки моделирует последовательный UI dispatcher на JVM; настоящая Android main thread/ANR на устройстве не запускалась. Условие конкурентной медленной записи создано управляемым storage. Частота такого совпадения на реальном устройстве не измерена, но ожидание общего монитора в caller и остановка его очереди подтверждены. В текущем APK новый auth UI ещё не подключён.

Нет нового device smoke, runtime API 28/37, физического устройства, реального process death auth, полной TalkBack/IME/visual/fontScale матрицы или production API/App Links/писем. UI не менялся, Figma заново не читалась. H1/H2 должны быть пройдены человеком на конкретной сборке, агент не менял их статусы.

**Следующий технический шаг:** исправить блокирующее ожидание в фазе session acceptance отдельным разрешённым изменением, сохранив шесть новых регрессий и добавив проверку отзывчивости caller при конкурентном IO; после H1 продолжить запланированную UI-интеграцию T04.

Предыдущие записи CODE_REVIEW.md сохранены побайтно. Исходники приложения/тестов, зависимости, конфигурация, решения и статусы не менялись. Пользовательская правка плана сохранена (SHA-256 `d369950f41c3e6a4071ea288328d7076403ed31dd5d43212751a18c125314102`), `docs/prompts/` не изменялся. Результат ревью — добавление этой записи; остальные созданные материалы являются игнорируемыми диагностическими/build outputs.

---

# Ревью — 28 октября 2026 — 893486e (coroutine Mutex общего fake)

## Область и вывод

Проверен `893486eea25c202e94440d9d85037f6fdf514098` (`fix: suspend shared fake state contention during session acceptance`) относительно `055c14b`. HEAD соответствует проверяемому коммиту. Diff: 9 файлов, 142 добавления / 40 удалений — общий Mutex, auth/guest/scenario mutations, две регрессии, версия и документы. Это ревью исправления синхронизации и связанных сценариев, не полный повторный аудит всего приложения.

Применён локальный `$android-code-review`. Прочитаны AGENTS.md, README, относящиеся решения и T02.2/T04 в плане; по карте документации учтены инженерные правила, контракты и актуальный отчёт T04. B01–B08 сохраняют приоритет над ранней Figma. Изучены diff, затронутые пути записи общего state, связанный command/session acceptance и восемь auth-регрессий, неизменённые handler/coordinator и вызывающие места служебного API.

**Новых подтверждённых дефектов P0–P3 в проверенном коде не обнаружено. P2 из ревью 055c14b подтверждён как исправленный в проверенных сценариях.** После перехода на единый coroutine Mutex ожидающая session acceptance освобождает поток caller; отмена ожидания сохраняет Guest. Предыдущие исправления поздней отмены и retry подтверждения не регрессировали в повторно исполненных тестах. Это не утверждение об отсутствии ошибок во всём проекте или готовности полной T04.

T03 остаётся READY_FOR_HUMAN, T04 — IN_PROGRESS, H1/H2 ожидаются. Нового auth UI, Activity links и live подключения session gate/decorators в этом diff нет; они явно оставлены на этап после H1 и не оформляются как дефект данного исправления.

## Проверка закрытия P2 055c14b

**Основные места:** [FakeAuthRepository.kt:139](F:/ASProjects/MospolytechProject/data/fake/src/main/kotlin/ru/eventpass/data/fake/product/FakeAuthRepository.kt:139), `FakeEventPassEnvironment.kt:48,88,197`; регрессии [AuthFoundationRegressionTest.kt:111](F:/ASProjects/MospolytechProject/app/src/test/kotlin/ru/eventpass/app/navigation/AuthFoundationRegressionTest.kt:111) и строка 115.

- Один Mutex создаётся в FakeEventPassEnvironment и передаётся auth backend. Initialization, command, refresh/signOut, session acceptance, reset, guest submit и ScenarioController mutations используют тот же механизм. Поиск всех присваиваний `state.value` не выявил другого пути записи, оставленного под прежним Java-монитором. IssueGuestTicket вызывается внутри общего withLock.
- Новая фаза acceptance ждёт `lock.withLock` на caller без синхронного ожидания монитора. После получения доступа повторно проверяются activity, generation, healthy, актуальный аккаунт и expiresAt. До публикации возможна отмена ожидания; после публикации нет нового suspend/dispatcher hop.
- Копируется текущее, а не подготовленное до чужой операции состояние: конкурентное создание аккаунта/challenge не теряется при успешном принятии сессии.
- Password proof и snapshot IO остаются на Dispatchers.IO. Debug/test reset сам переключается в IO; изменяющие ScenarioController методы теперь suspend, чтения accounts/outbox остаются синхронными снимками. Domain-порты не изменены. Штатные JVM-тесты и сборка Android test APK подтверждают компиляцию существующих мест вызова после изменения API.
- Небольшие synchronized-секции AuthLinkHandler относятся к отдельным наборам inFlight/handled, не охватывают suspend/IO и не являются вторым механизмом блокировки product state.

Семантика приостанавливаемого и отменяемого ожидания соответствует [официальному описанию Mutex.lock](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.sync/-mutex/lock.html). Для вывода об исправлении использованы реальные запуски на coroutines 1.10.2 из проекта; текущая online-документация не считается подтверждением обновления библиотеки.

### Независимый повтор прежнего воспроизведения

Отдельная JVM-диагностика на compiled runtime текущего коммита подготавливает signIn до возврата на caller, затем задерживает write **другой** auth-команды — requestPasswordResetCode для host. Это дополняет штатную регрессию с конкурентным signUp. Продолжение signIn и marker запускаются на последовательном executor, моделирующем UI. Marker должен исполниться до освобождения writer, а login в этот момент не должен завершиться.

Обычное ожидание:

```text
CALLER_LOCK state=WAITING; uiTickBlocked=false; owner=null
CALLER_MUTEX released=true; loginCompleted=true; stateClass=Authenticated
```

`WAITING` здесь — свободный executor после обработки marker, ожидающий следующей задачи; прежнего BLOCKED на FakeAuthRepository.command:139 нет. Диагностический marker исполнился до release storage. После записи чужого challenge login завершился успешно.

Отмена ожидающей acceptance:

```text
CALLER_LOCK state=WAITING; uiTickBlocked=false; owner=null
MUTEX_CANCEL completedBeforeWrite=true; cancelled=true; stateClass=Guest
CALLER_MUTEX released=true; loginCompleted=true; stateClass=Guest
```

Отмена завершилась до освобождения занятого writer, сессия не была принята и после освобождения Mutex. Оба набора диагностических assertions прошли. Это JVM-модель последовательного UI dispatcher; настоящая Android main thread/ANR не запускалась и не заявляется проверенной.

Штатные новые тесты дополнительно держат запись конкурентного signUp, проверяют сохранение нового аккаунта, Gate Shell после нормального продолжения и Gate Login после отмены; прежние шесть регрессий проверяют позднюю отмену, generation/logout, durable signup и retry/deduplication confirmation. Все восемь исполнены и прошли.

## Сценарии, архитектура и документация

- Общие accounts/session для auth, событий и билетов сохранены. Global User.role, role-specific копии экранов и обход event-scoped политики не добавлены. Mutex относится к локальному fake; это не реализация транзакций настоящего сервера.
- SignUp/confirm/reset не создают авторизованную сессию; нужен ручной SignIn. ReturnDestination не содержит анкету/секрет, автоматического submit после входа нет. Новый guest cache или автосоздание аккаунта при guest submit не появились.
- MVVM/repository/DI границы не менялись: исправление находится в fake behind domain ports, Compose/ViewModel не узнают о storage/реализации. Подтверждённых новых нарушений SOLID с наблюдаемым последствием не найдено; замена механизма общей синхронизации решает воспроизведённую проблему.
- Snapshot schema, набор сохраняемых account/proof/hash/deadline полей и noBackup adapter прежние. Активная сессия, raw password/code/token, outbox и guest ticket cache не persist. Обработка IOException/типизированных ошибок и CancellationException не заменена подавлением ошибки.
- Уже совершённые долговечные account/challenge изменения при отмене продолжают публиковаться согласованно со snapshot; не обещается автоматический откат. Для недолговечной signIn-сессии сохранена отдельная активная точка принятия на caller.
- README, ADR-19, контракты и верхний актуальный раздел docs/18 отражают общий Mutex, suspend debug API, границу отмены и 0.4.2-t04-foundation-demo / code 6. Auth UI и H-гейты не объявлены завершёнными.

## Запущенные проверки

Рабочая папка `F:\ASProjects\MospolytechProject`, PowerShell, JBR Android Studio1:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
.\gradlew.bat :core:domain:test --rerun :data:fake:test --rerun :app:testDemoDebugUnitTest --rerun :core:platform:testDebugUnitTest --rerun :app:assembleDemoDebug :app:assembleDemoDebugAndroidTest :app:lintDemoDebug :app:verifyDemoIsolation --offline --console=plain --max-workers=2
```

**BUILD SUCCESSFUL in 8s**, 186 actionable tasks: 6 executed / 180 up-to-date. Четыре unit-задачи исполнены повторно. Проверены XML:

| Модуль | Tests | Failures / errors / skipped |
|---|---:|---|
| core:domain | 7 | 0 / 0 / 0 |
| data:fake | 25 | 0 / 0 / 0 |
| app | 20 | 0 / 0 / 0 |
| core:platform | 2 | 0 / 0 / 0 |
| Всего | 54 | 0 / 0 / 0 |

В app входят все 8 AuthFoundationRegressionTest. Сборка обоих APK и lint analysis инкрементальные; это не полная пересборка/анализ с нуля. Существующий lint XML содержит **0 errors / 4 warnings**. Demo isolation исполнена и успешна, baseline/исключения не добавлялись.

Независимая диагностика Mutex выполнена в двух вариантах (normal/cancel), JBR 21, реальные compiled classes и зависимости проекта. Артефакты: `.gradle/review-893486e/ReviewCallerMutex.java`, compiled helpers, `contention.log`, `contention-cancel.log`, init script и classpath. Внешняя задача получения classpath успешна за 2s; при ней unit-задача UP-TO-DATE. Для повтора:

```powershell
.\gradlew.bat :app:reviewAuthClasspath -I .gradle/review-893486e/classpath.init.gradle --offline --console=plain --max-workers=2
$reviewWork = Join-Path (Get-Location).Path '.gradle/review-893486e'
$reviewRuntime = [IO.File]::ReadAllText((Join-Path $reviewWork 'classpath.txt'))
& "$env:JAVA_HOME\bin\javac.exe" -cp $reviewRuntime -d $reviewWork (Join-Path $reviewWork 'ReviewCallerMutex.java')
& "$env:JAVA_HOME\bin\java.exe" -cp ($reviewWork + ';' + $reviewRuntime) ReviewCallerMutex
& "$env:JAVA_HOME\bin\java.exe" -cp ($reviewWork + ';' + $reviewRuntime) ReviewCallerMutex cancel
```

Дополнительно:

- `git diff --check 893486e^ 893486e` — замечаний нет.
- Текущий APK SHA-256: `54c078050e87905d2cea567196249bbf1fbc39c13ea62eb68d3fafe994e43ce0`, совпадает с README/docs/18, версия 0.4.2-t04-foundation-demo / code 6.
- `adb devices` — подключённых устройств нет, эмулятор не запускался.
- Сверены сохранённый `.gradle/t04-lock-device.log` и connected XML из `.gradle/t04-lock-evidence`: 7 тестов (1 AuthFoundationContract, 1 EntryOwnership, 4 NavigationSmoke, 1 WindowInsetsRegression), failures/errors/skipped = 0, timestamp `2026-10-28T17:20:09`, BUILD SUCCESSFUL in 47s. Это **результаты разработки**, не новый device-прогон ревьюера.
- Проверены сохранённые red/green свидетельства: `.gradle/t04-lock-red.xml` содержит 2 tests / 2 failures, red log BUILD FAILED; `.gradle/t04-lock-green.log` — BUILD SUCCESSFUL. Старый код заново в этом ревью не переключался и red-прогон не повторялся; сохранённые результаты согласуются с предыдущим независимым воспроизведением в записи 055c14b.

## Вопросы, допущения и ограничения

В нижних сводных строках рабочего `docs/06-delivery-plan.md` (T02/T04, строки 218/220) остаются прежние 52 unit и версия 0.4.1, хотя актуальные разделы выше, README, docs/18 и фактический APK уже указывают 54 unit / 0.4.2. Это остаточная редакционная несогласованность сводки, не свидетельство другой собранной версии; результаты этого ревью привязаны к проверенному commit/hash выше. План по условиям read-only ревью не правился.

Проверки отзывчивости/отмены — JVM с управляемой очередью и storage; частота гонок и настоящий ANR на устройстве не измерялись. Нового device-прогона, runtime API 28/37, физического устройства, настоящего process death auth, gesture-повтора, полного auth UI/Activity smoke, TalkBack/IME/fontScale/визуальной матрицы не было. UI не менялся, Figma заново не читалась. Production API/реальные письма/App Links/refresh/signing не подтверждаются этим исправлением.

Неподключённые host/decorator/root-owner cleanup, foreground expiry refresh, Login startup и UI ошибок/outbox остаются объёмом оставшейся T04 после H1. Человеческая приёмка H1/H2 не отмечалась пройденной; наличие 54 unit и 7 сохранённых device success не заменяет её.

Предыдущие записи CODE_REVIEW.md сохранены побайтно, исходники приложения/тестов, зависимости, конфигурация, решения и статусы не менялись. Пользовательская правка `docs/06-delivery-plan.md` сохранена (SHA-256 `a5b10cdba2df7c62cb92ae24292cd98bd2e50b706d2c3a1b6f60ebc12f58422c`), `docs/prompts/` не изменялся. Кроме добавления отчёта созданы только обычные игнорируемые build/diagnostic outputs.

**Следующий шаг:** ручная H1 на конкретной актуальной сборке, затем оставшаяся UI-интеграция T04 с живой проверкой session/decorator ownership, logout/Back, expiry и auth links. Новых подтверждённых source findings, требующих исправления до этого шага по данному diff, нет.

---

# Ревью — 29 октября 2026 — 646a1e2 (auth UI / session navigation)

## Область и итог

Проверен `646a1e2431bbc95779e270cb68bee844339fcb69` (`feat: integrate auth UI and session-scoped navigation`) относительно `893486e`. HEAD совпадает с проверяемым коммитом. Область: 30 файлов, 1137 добавлений / 61 удаление — auth ViewModel/Screen, Activity host, ссылки, session-scoped owners, demo tools, тесты и документация. Рабочие исходники совпадают с коммитом; существующие незакоммиченные изменения относятся к документации и пользовательскому daemon JVM configuration.

Применён `$android-code-review`. Прочитаны AGENTS.md, README, актуальные решения (включая ADR-20), задача T04 и карта документации; учтены инженерные правила, B01–B08, навигационные контракты и отчёт docs/18. Изучены новые auth routes/ViewModels/screens, AppHost/AppSessionViewModel, полный изменённый PrivateShell/NavigationState, MainActivity, demo tools/DI/Clock, новые unit/device-тесты и связанные неизменённые repository/coordinator/handler boundaries. Текущие пользовательские правки документации отделены от diff коммита и не исправлялись.

**Новых подтверждённых дефектов P0–P3 в проверенном diff не обнаружено.** Независимо повторно исполнены 56 unit-тестов, сборка app/test APK, lint task и isolation завершились успешно. Сохранённые 13 device-результатов сверены по журналу и XML; нового device-прогона ревьюера не было. Вывод ограничен проверенным кодом и сценариями ниже и не означает завершение всего MVP или ручной приёмки auth UI.

H1 уже подтверждена пользователем по актуальной документации, T03 — DONE. **T04 — READY_FOR_HUMAN; ручной auth smoke ожидается, H2 относится к T05 и не закрыта.** Старые записи отчёта о pending H1 остаются историей соответствующих коммитов.

## Сценарии и состояние auth

- Гостевой старт реализован через SessionGate.Login/PublicNavigation без нижних табов. Login, SignUp, Verification и Reset имеют отдельные типизированные entry; временные event/demo приёмники не маскируются под готовые T05 tickets/анкеты.
- LoginViewModel получает состояние email/password/busy/error, блокирует повторную отправку до результата и позволяет явный retry после типизированного отказа. Email сохраняется отдельно; пароль остаётся только в UiState памяти и очищается при успехе. Ошибки маппятся в Android string resources; domain не получает R.string/русские сообщения.
- AccountFlowViewModel координирует signup/verification либо reset email → code → password → done через AuthRepository. Пароль очищается после выдачи challenge, код — после выдачи grant, grant/новый пароль — после успешного reset. Challenge и секретный grant принадлежат памяти flow entry. Новый экземпляр после process recreation начинает с email/Input, без восстановления кода/grant/пароля из SavedStateHandle. Реальную смерть OS-процесса этот вывод сам по себе не проверяет.
- SignUp/confirm/reset не принимают авторизованную сессию. Подтверждение возвращает к Login с предзаполнением email через StateFlow, требуется ручной пароль. Reset изменяет пароль через существующий backend; последующий Login проверяет новый пароль. B03 и сохранённые инварианты foundation соблюдены в рассмотренном срезе.
- Busy/typed error/cooldown состояния отделены от успеха; автоматического retry мутаций нет. Resend вызывает существующую политику cooldown, UI пересчитывает оставшееся время только в STARTED. CancellationException не преобразуется в Network. Долговечный результат не заменяется одноразовой ненадёжной командой из recomposition.
- Возврат после входа хранит только ReturnDestination, без анкеты/CompleteRegistration/автоматического submit по B04. Public EventReceiver устанавливает pending destination; PrivateShell потребляет его для нового PrivateScope один раз. EventReceiver открыто сообщает о будущем T05, не изображает реализованную регистрацию/выдачу QR.

## Навигация, lifecycle и безопасность

**Места:** `AppSessionViewModel.kt`, `AppHost.kt`, `EventPassApp.kt`, `MainActivity.kt` и `NavigationState.kt`.

- Activity ViewModel владеет coordinator, transient delivery/retry и parent ViewModelStore владельцами. Приватные parent stores разделены по табам, композиция PrivateShell ключуется PrivateScope. Все три стека продолжают декорироваться и при скрытых табах. Очистка включает root/hidden stores, а не только списки маршрутов.
- Смена scope/generation, logout и expiry проходят существующую SessionCoordinator policy и clearOwners. PrivateShell дополнительно проверяет актуальность scope через repository snapshot. Сохранённый route не объявляется доказательством авторизации; глобальная User.role не появилась. Полное серверное разграничение прав настоящего API этим не заменяется.
- При STARTED выполняется refreshSession и ожидается один deadline expiresAt. repeatOnLifecycle/collectLatest ограничивают жизнь таймера состоянием Activity и текущей gate; polling/фоновый сервис не добавлены. DemoClock действует на auth-время, demo-кнопка сдвига также вызывает refreshSession.
- MainActivity передаёт cold ACTION_VIEW и warm onNewIntent в retained ViewModel и очищает data/action переданного Intent. При recreation с savedInstanceState уже доставленная launch-ссылка не обрабатывается заново. Очередь и retry URI хранятся transient в ViewModel, не в SavedStateHandle/route. Успех сбрасывает retryDelivery, а действующий handler сохраняет различие inFlight/handled и возможность явного retry после отказа.
- Allowlist parser и manifest demo scheme/host сохранены; неизвестные/запрещённые пути не превращаются в произвольный return URL. Cold Activity тест и warm/rotation тест проверяют разные способы доставки. Production App Links, домен и signing-протокол не заявляются готовыми.
- Forbidden/Unauthenticated policy подключена к host. Demo canScan берётся из существующего EventRepository по eventId; Forbidden показывает сообщение без login loop, Unauthenticated устанавливает безопасное назначение. Настоящие Scanner/Dashboard и закрытые T05+ действия пока не реализованы; наличие demo guard не выдаётся за проверку их будущих экранов.
- Concrete fake, outbox с демонстрационными секретами и управляющие операции находятся в src/demo. Основные auth ViewModels/screens используют порты и состояние; DTO/storage implementation не вынесены в Compose. Текущий проект по-прежнему поддерживает только demoDebug; production variant не проверен и не обещан.

Подтверждённых новых нарушений MVVM/SOLID с конкретным наблюдаемым последствием не установлено. Password/code/grant не добавлены в serializable route или SavedStateHandle, постоянный клиентский guest cache и автосоздание аккаунта при guest submit не появились. Domain/fake auth инварианты предыдущих исправлений покрываются повторёнными тестами.

## Документация и границы реализации

README, ADR-20, верхняя актуальная часть плана/docs/18 согласованно описывают реализованный auth срез, H1 пользователя, ожидающий auth smoke и следующий T05. Исторические foundation-разделы сохраняют старые версии/статусы; результаты этого ревью привязаны только к code 7 и SHA-256 ниже.

Документация открыто фиксирует отсутствие свежего Figma comparison из-за ошибок соединения при разработке. В этом ревью live Figma и точная визуальная геометрия G03/G04/G05 заново не проверялись. UI построен с использованием существующих core:designsystem компонентов; соответствие макетам, TalkBack, IME/ширины/fontScale остаются предметом ручного auth smoke. H1 UI-кита не переоткрывается автоматически из-за появления новых auth-экранов.

При начале ревью уже существовали пользовательские правки README, docs/04,06,07,08,17,18, неотслеживаемые docs/prompts, CODE_REVIEW.md и gradle/gradle-daemon-jvm.properties. Они не включались в исправления ревью; исходники приложения и штатных тестов не редактировались. Критерий daemon JVM 21 из пользовательского файла учтён без изменения файла или глобальной Java-конфигурации.

## Независимо выполненные проверки

PowerShell, рабочая папка `F:\ASProjects\MospolytechProject`, JBR Android Studio1:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
.\gradlew.bat :core:domain:test --rerun :data:fake:test --rerun :app:testDemoDebugUnitTest --rerun :core:platform:testDebugUnitTest --rerun :app:assembleDemoDebug :app:assembleDemoDebugAndroidTest :app:lintDemoDebug :app:verifyDemoIsolation --offline --console=plain --max-workers=2
```

**BUILD SUCCESSFUL in 8s**, 186 actionable tasks: 6 executed / 180 up-to-date. Журнал текущего независимого вызова — `.gradle/review-646a1e2-build.log`. Все четыре unit-задачи исполнены повторно; XML сверен:

| Модуль | Tests | Failures / errors / skipped |
|---|---:|---|
| core:domain | 7 | 0 / 0 / 0 |
| data:fake | 25 | 0 / 0 / 0 |
| app | 22 | 0 / 0 / 0 |
| core:platform | 2 | 0 / 0 / 0 |
| Всего | 56 | 0 / 0 / 0 |

В app входят две новые AuthViewModelsTest и восемь прежних auth-регрессий. Assemble app/test APK и lint analysis инкрементальные; это не новая полная пересборка/анализ с нуля. Существующий lint XML содержит **0 errors / 4 warnings**. Demo isolation исполнена и успешна: только demoDebug, fake вне main implementation. Новые baseline/исключения не добавлены.

Дополнительно:

- `git diff --check 646a1e2^ 646a1e2` — без замечаний.
- SHA-256 текущего APK: `aed6b318515dc0ffb7ee438aa2bdc4d83ede65a46db74d14cc5667d135f5eeac`, совпадает с README/docs/18. Версия 0.4.3-t04-demo / code 7. APK не устанавливался на подключённое устройство при ревью.
- `adb devices` обнаружил подключённое устройство; никакие его приложения, данные, настройки и режим навигации ревьюером не менялись. Новый эмулятор не запускался.
- Обычная командная среда снова не создаёт процесс (`helper_unknown_error: setup refresh had errors`). Чтение, проверки и запись отчёта выполнены через разрешённые запуски вне неисправного окружения. Ошибка среды не считается дефектом Android-приложения.

## Сверка сохранённых device-результатов

Это **запуски разработки**, не новые device-тесты ревьюера. Прочитаны `.gradle/t04-ui-final.log`, существующие connected XML и новые device-исходники. Журнал завершает 13 тестов, BUILD SUCCESSFUL in 2m 21s; XML timestamp `2026-10-29T10:41:02`:

| Suite | Tests | Failures / errors / skipped |
|---|---:|---|
| AuthColdActivityTest | 1 | 0 / 0 / 0 |
| AuthFoundationContractTest | 1 | 0 / 0 / 0 |
| AuthUiIntegrationTest | 5 | 0 / 0 / 0 |
| EntryOwnershipTest | 1 | 0 / 0 / 0 |
| NavigationSmokeTest | 4 | 0 / 0 / 0 |
| WindowInsetsRegressionTest | 1 | 0 / 0 / 0 |
| Всего | 13 | 0 / 0 / 0 |

Проверяемые сценарии включают signup/outbox/confirm/manual login/reset, Network→явный retry confirmation после rotation, холодный onCreate confirmation без сессии, возврат к E и recreation, foreground expiry, root/hidden owner cleanup со сменой аккаунта, event-scoped guards. Старые insets/системные нажатия и navigation tests сохранены с новым явным auth setup.

Root owner probes проверяют отмену прежних ViewModelScope и новые экземпляры при смене аккаунта. Это конкретное доказательство соответствующего сценария, не универсальная гарантия всех вариантов восстановления Android-задачи. Cold Activity в существующем процессе и новый экземпляр backend/AtomicFile не подменяют OS process-death test. Промежуточные неуспешные прогоны разработки описаны в docs/18; они не объявляются результатами текущего ревью.

## Ограничения и следующий шаг

Нового instrumented/device запуска, OS process-death auth, runtime API 28/37, независимого физического/gesture-прогона, полноценного TalkBack/IME/fontScale/визуального сравнения новых auth-экранов не было. H1 уже подтверждена пользователем; **ручной auth smoke и H2 этим ревью не закрыты**. Контракты будущего backend, реальные письма/App Links, production signing и T05+ продуктовые экраны не проверены как реализованные функции.

Разрешённый результат — эта новая запись CODE_REVIEW.md; прежнее содержимое сохранено побайтно. Пользовательские файлы документации/prompts/daemon configuration проверены по SHA-256 до/после добавления записи и сохранены. Исходники, зависимости, конфигурация проекта и статусы задач ревьюером не менялись; кроме отчёта создан игнорируемый журнал независимой сборки.

**Следующий шаг:** ручной auth smoke на APK 0.4.3-t04-demo / code 7 по docs/18 с записью устройства/результата, затем T05/H2 по плану. Новых подтверждённых source findings, требующих исправления по этому diff, не сформировано.

---

# Ревью — 29 октября 2026 — 1e54b80 (регистрация / гостевой QR / билеты)

## Область и итог

Проверен `1e54b805a65cd2c2a07cbe7e7041f604d40e465b` (`feat: implement event registration and persistent tickets`) относительно `646a1e2`. HEAD совпадает с проверяемым коммитом. Diff: 51 файл, 2095 добавлений / 182 удаления, включая лицензию ZXing и диагностические скрипты. Основной срез — общий EventDetails/форма, guest saved-state/QR, authenticated submit, Tickets/отмена, общий product snapshot v2 и миграция auth v1.

Применён локальный `$android-code-review`. Прочитаны AGENTS.md, README, ADR-21 и связанные решения, задача T05/карта документации, engineering rules состояния/гостевого QR/отмены, продуктовые B01–B08 и отчёт docs/19. Изучены новые Event/Tickets ViewModels и screens, GuestReceiptState, QR composables/encoder, навигационный diff, FakeTicketOperations/ProductSnapshotCodec, связанные auth/storage changes, validation/domain contracts и соответствующие тесты. Пользовательские незакоммиченные правки документов/daemon configuration отделены от коммита.

**Подтверждена одна находка P2: поздний finally отменённой отправки сбрасывает busy-флаг нового запроса и допускает ещё одну параллельную отправку.** P0/P1 в проверенной области не обнаружены. Все 71 штатный unit-тест при независимом повторном исполнении проходят; указанный переход они не покрывают. Находка воспроизведена отдельной JVM-диагностикой на compiled EventViewModel, без изменения исходников приложения/тестов.

T03/T04 отмечены DONE по пользовательским подтверждениям в актуальных документах; **T05 — READY_FOR_HUMAN, H2 ожидается**. Этот отчёт не меняет статусы и не засчитывает ручную приёмку.

## Подтверждённая находка

### 1. [P2] Завершение старой отправки снимает блокировку нового запроса

**Место:** [EventViewModel.kt:110](F:/ASProjects/MospolytechProject/app/src/main/kotlin/ru/eventpass/app/feature/event/EventViewModel.kt:110), безусловная запись `submitting = false` в finally. Связанные участки: `leaveForm` отменяет submitJob/увеличивает formVersion и сразу снимает submitting; новая отправка запускается через `submit` и перезаписывает submitJob.

Проверка `version != formVersion` защищает результат старой операции, но finally выполняется независимо от версии. Отмена корутины не означает мгновенного завершения уже начатой блокирующей/долговечной работы repository. Если пользователь ушёл с формы, вернулся в ту же EventKey и отправил её снова до завершения предыдущего вызова, поздний finally первой операции меняет состояние второго запроса.

**Последовательность:**

1. Запрос A ожидает завершения repository.
2. Уход с формы вызывает leaveForm: A отменяется, submitting становится false. Его фактическое завершение ещё задержано.
3. Возврат к сохранённой entry и новая отправка запускают B; submitting=true.
4. Поздно завершившийся A отбрасывает результат по formVersion, но в finally записывает submitting=false, хотя B ещё не завершён.
5. Кнопка/поля снова доступны; повторное нажатие запускает C параллельно B.

**Независимое доказательство:** диагностический RegistrationRepository удерживает завершения вызовов по отдельности, моделируя задержанное окончание отменённой операции. Используются реальный EventViewModel, реальные данные EventRepository/validation и Main dispatcher под управлением теста. После leaveForm и отправки B завершается только A:

```text
BEFORE_OLD_FINAL calls=2; submitting=true
AFTER_OLD_FINAL calls=2; submitting=false
AFTER_EXTRA_TAP calls=3; secondStillPending=true
```

Assertions проверяют наличие именно третьего вызова при незавершённом втором. Это не новый device-прогон и не заявление о двойной выдаче билета: fake отдельно защищает существующий ticket на email/E. Подтверждена потеря UI-блокировки и запуск лишних мутационных запросов; пользователь также получает возможность менять поля до завершения актуальной операции. Такое поведение нарушает правило «отправка блокирует повторное нажатие до результата».

**Направление исправления:** привязать снятие busy к идентификатору актуальной отправки/Job или её generation, а не к любому завершившемуся coroutine. При смене формы/запроса старый finally не должен менять состояние новой отправки. Сохранить отмену, проверку устаревших результатов и отсутствие автоматического retry. Добавить регрессию: отменить A с задержанным завершением → запустить B → завершить A → B всё ещё submitting и дополнительное нажатие не запускает C. Не блокировать main ожиданием завершения IO.

Текущий `duplicatePressAndLeavingFormCannotPublishAnOldResult` отменяет кооперативный deferred до repository mutation и не проверяет одновременное существование старого finally и нового submit; поэтому зелёный штатный тест не опровергает находку.

## Пользовательские сценарии и границы состояния

- B02/B04 отражены в рассмотренном коде: guest submit не создаёт аккаунт, RequiresLogin очищает форму до перехода, pending destination содержит E без ответов/автоматического submit. Авторизованная команда не принимает доверенный userId/email с UI: identity берётся из актуальной подтверждённой сессии repository.
- Guest receipt создаётся только из нового Issued результата; GuestReceiptState записывает компактные значения в SavedStateHandle до публикации UI. Восстановление не выполняет lookup гостевого билета по email/ticketId. Public EventKey имеет отдельную identity; соседний auth flow может оставить receipt в исходном owner, явное закрытие/удаление entry его очищает. Незавершённая анкета на диск/saved state не записывается.
- Изменение PublicNavigation сохраняет один registry при Loading→Guest; AppSessionViewModel не очищает публичный receipt при первоначальном разрешении гостевой сессии. Переход в private scope и прежние private root/hidden cleanup остаются отдельными границами. Эти изменения учитывают различие recreation/восстановления той же задачи и новой задачи.
- Авторизованный submit возвращает собственный существующий действующий/Used ticket либо создаёт новый. Tickets выбирает выданный ID, перечитывает только собственные данные; другой аккаунт не получает билеты по произвольно введённому email/ID. Владелец/grant не превращаются в глобальную роль или право владения чужим билетом.
- Cancel проверяет actor/generation и ownership внутри repository, Used отвергается; повтор cancel не создаёт дополнительного освобождения места, поскольку capacity считается по неотменённым ticket. Новая заявка после cancel получает новый UUID payload. Статусы Used/Cancelled/Finished/end time учитываются при показе текущего QR, начало само по себе не переносит Active в историю.
- Refresh failures отличаются от пустого списка/Unlimited; Event сохраняет последние данные и запрещает submit при readError. Transport/storage outcomes типизированы; UI показывает локализованные ошибки и явный retry. Полный набор временных/платформенных сочетаний этими тестами не доказан.

## Persistence, архитектура и QR

- Общие auth/events/registrations/tickets/grants/policy/nextId используют один FakeEventPassEnvironment/Mutex. IO и сериализация выполняются вне main. ProductSnapshotCodec v2 хранит fake-server records, не клиентский постоянный guest cache; payload в закрытой server ticket-записи не используется для восстановления чужого гостевого UI.
- Legacy auth v1 восстанавливается вместе с seed product data; последующие auth и ticket команды пишут полный product snapshot. Unsupported/corrupt snapshot не стирается автоматической следующей командой. Публикация state идёт после успешной записи; IOException оставляет typed failure. Уже совершённая durable mutation при поздней отмене не обещает rollback.
- Сессия, raw auth secrets, outbox, незавершённая форма и UI GuestReceipt не входят в product snapshot. Аккаунты/proofs/challenge hashes, регистрации/ответы и билеты — server-side demo data. Snapshot находится в прежнем noBackup файле, AtomicFile adapter сохраняется.
- ViewModels/Compose зависят от repository ports/domain models, concrete fake tools остаются в src/demo. Domain не содержит Context/DTO/UI resources; MVVM и общий EventDetails/Tickets сохранены. Дополнительных подтверждённых нарушений SOLID с самостоятельным последствием не установлено; замечания по размеру классов/предпочтениям оформления не выдаются за дефекты.
- QR строится из выданного payload на Default dispatcher, не сохраняется bitmap. Encoder задаёт UTF-8 и quiet zone 4; чёрно-белые pixels и fullscreen brightness принадлежат временной поверхности. Параметр margin/рендеринг сверены с [исходником QRCodeWriter версии 3.5.4](https://raw.githubusercontent.com/zxing/zxing/zxing-3.5.4/core/src/main/java/com/google/zxing/qrcode/QRCodeWriter.java). Лицензия/происхождение включены в core:platform assets. Это не проверка серверной подлинности ticket или реализация check-in.

README, ADR-21, contracts/navigation и docs/19 согласованно обозначают текущую code 8, демо-почту, snapshot v2 и ожидающую H2. Новые T06+ editor/scanner/реальные письма/API не считаются реализованными по наличию T05 моделей. Live Figma и вся визуальная матрица в этом ревью заново не сверялись.

## Независимо выполненные проверки

PowerShell, `F:\ASProjects\MospolytechProject`, JBR Android Studio1:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
.\gradlew.bat :core:domain:test --rerun :data:fake:test --rerun :app:testDemoDebugUnitTest --rerun :core:platform:testDebugUnitTest --rerun :app:assembleDemoDebug :app:assembleDemoDebugAndroidTest :app:lintDemoDebug :app:verifyDemoIsolation --offline --console=plain --max-workers=2
```

**BUILD SUCCESSFUL in 9s**, 186 actionable tasks: 6 executed / 180 up-to-date. Журнал `.gradle/review-1e54b80-build.log`. Все четыре unit-задачи исполнены повторно, XML сверен:

| Модуль | Tests | Failures / errors / skipped |
|---|---:|---|
| core:domain | 7 | 0 / 0 / 0 |
| data:fake | 34 | 0 / 0 / 0 |
| app | 27 | 0 / 0 / 0 |
| core:platform | 3 | 0 / 0 / 0 |
| Всего | 71 | 0 / 0 / 0 |

Assemble app/test APK и lint analysis инкрементальные; существующий lint XML содержит **0 errors / 4 warnings**. Demo isolation исполнена и успешна; baseline/исключения не добавлены.

Адресное воспроизведение P2 выполнено отдельной Java-программой на compiled unit runtime проекта/JBR 21. Подменяется только RegistrationRepository с управляемыми завершениями; исходники приложения и штатных тестов не менялись. Артефакты в `.gradle/review-1e54b80`: `ReviewSubmitRace.java`, compiled class, `submit-race.log`, init script/classpath. Assertions подтверждают наличие неправильного перехода, а не безопасное поведение. Для повтора:

```powershell
.\gradlew.bat :app:reviewAuthClasspath -I .gradle/review-1e54b80/classpath.init.gradle --offline --console=plain --max-workers=2
$reviewWork = Join-Path (Get-Location).Path '.gradle/review-1e54b80'
$reviewRuntime = [IO.File]::ReadAllText((Join-Path $reviewWork 'classpath.txt'))
& "$env:JAVA_HOME\bin\javac.exe" -cp $reviewRuntime -d $reviewWork (Join-Path $reviewWork 'ReviewSubmitRace.java')
& "$env:JAVA_HOME\bin\java.exe" -cp ($reviewWork + ';' + $reviewRuntime) ReviewSubmitRace
```

Вспомогательная задача classpath успешна за 3s; повторного исполнения app tests при ней не было (UP-TO-DATE). Конфигурация проекта не менялась.

Дополнительно: `git diff --check 1e54b80^ 1e54b80` — без замечаний. APK SHA-256 `d631ac0839624986f103e99cdfa2f6c1031e1d2a0f5f1ee9f665800d37662337` совпадает с README/docs/19, версия 0.5.0-t05-demo / code 8. На подключённое устройство APK не устанавливался, данные/настройки не менялись; новый эмулятор не запускался.

Обычная командная среда не создаёт процесс (`helper_unknown_error: setup refresh had errors`); чтение, проверки и запись отчёта выполнены разрешёнными запусками вне неисправного окружения. Это не дефект приложения.

## Сохранённые device/process доказательства

Это **результаты разработки**, не новые device/process запуски ревьюера. Сверены XML `.gradle/t05-evidence/device` и `device_layout`:

- Полный набор: 18 tests, failures/errors/skipped = 0 (AuthColdActivity 1, AuthFoundationContract 1, AuthUiIntegration 5, EntryOwnership 1, NavigationSmoke 4, RegistrationTickets 5, WindowInsetsRegression 1).
- После последней геометрической правки G01: 6 tests, failures/errors/skipped = 0 (RegistrationTickets 5 + WindowInsetsRegression 1). Остальные 12 не выдаются за повторный прогон на последней геометрии.

Прочитан `.gradle/t05-layout-process.log`: decoder сообщает совпадение issued QR на screenshot; зафиксированы смена PID `11103 → 11561`, та же task `211`, отсутствие receipt в новой task и сохранение server registration. Это согласуется с описанным процессным proof на итоговом APK, но самостоятельно повторной смертью процесса/декодированием ревьюера не является. Исходный сценарий отделяет background am kill от последующего создания новой задачи и не заменяет их ActivityScenario.recreate.

## Ограничения и следующий шаг

Нового device-прогона, реального OS process death, отдельного декодирования screenshot, API 28/37, физического/gesture-повтора, live Figma comparison и полной TalkBack/IME/fontScale/яркости/ширин матрицы в этом ревью не было. JVM-диагностика P2 управляет временем завершения repository; частота такого совпадения на реальном устройстве не измерялась. Подтверждён UI race, но двойная серверная выдача билета не заявляется.

H1 и auth smoke уже приняты пользователем; **H2 T05 остаётся ожидающей**. Следующий технический шаг — отдельным разрешённым изменением привязать submitting/finally к актуальной отправке и добавить регрессию позднего завершения, затем ручной H2 на конкретной исправленной сборке. После H2 — T06 по плану.

Предыдущие записи CODE_REVIEW.md сохранены побайтно. Исходники приложения/тестов, зависимости, решения и статусы не менялись. Пользовательские README/docs/prompts/daemon JVM configuration сохранены с проверкой хешей до/после записи; дополнительные файлы этого ревью — обычные игнорируемые build/diagnostic outputs.

---

# Ревью — 29 октября 2026 — dbbbea1 (исправление позднего finally заявки)

## Область и итог

Проверен `dbbbea1bce3f06bf8129aef6eef20b14c3b85e63` (`fix: preserve submission lock after cancelled request finishes`) относительно `1e54b80`. HEAD совпадает с проверяемым коммитом. Diff: 7 файлов, 105 добавлений / 9 удалений — проверка поколения в EventViewModel.finally, две регрессии, версия APK и документация. Ревью ограничено этим исправлением и его вызывающими сценариями, а не повторным полным аудитом всего T05.

Применён `$android-code-review`; прочитаны AGENTS.md, README, относящиеся решения/ADR-21, T05 из плана и карта документации. Учтены engineering rules состояния/повторной отправки и B01–B08. Изучены весь EventViewModel, diff новых тестов, документационные изменения и связь leaveForm/submit/результата/finally с существующим EventRoute.

**Новых подтверждённых находок P0–P3 в проверенном diff не обнаружено. Прежняя P2 из 1e54b80 подтверждена как исправленная в проверенных сценариях.** Все 73 unit-теста повторно исполнены успешно; независимый повтор исходного сценария на compiled EventViewModel также прошёл. Это не утверждение об отсутствии ошибок во всём проекте и не замена ручной H2.

T03/T04 остаются DONE по прежним подтверждениям пользователя. **T05 — READY_FOR_HUMAN, H2 ожидается на 0.5.1-t05-demo / code 9.** Статусы задач и приёмок ревьюером не менялись.

## Проверка закрытия P2

**Место исправления:** [EventViewModel.kt:112](F:/ASProjects/MospolytechProject/app/src/main/kotlin/ru/eventpass/app/feature/event/EventViewModel.kt:112), строки 110–113.

Теперь finally снимает submitting только при `version == formVersion`. Захваченное поколение уже использовалось для отбрасывания устаревшего результата; исправление применяет ту же границу к завершающему изменению busy. leaveForm увеличивает поколение до отмены старой работы и явно сбрасывает текущую форму/busy. Поэтому поздний finally A после leaveForm не может изменить B, запущенную на новом поколении.

Проверены связанные ветви:

- Результат A по-прежнему отбрасывается по поколению, а её finally также не трогает новую форму/ошибку/receipt/action.
- Пока B ожидает ответ, submit и edit блокируются её submitting. Только завершение актуальной B снимает busy; её ошибка показывается, ручной retry после завершения остаётся разрешён.
- RequiresLogin и StaleForm вызывают leaveForm, который сам снимает busy и увеличивает поколение. Пропуск finally после такого перехода не оставляет форму навсегда заблокированной.
- close использует leaveForm и очищает receipt; уничтожение owner очищает данные и отменяет ViewModelScope. Нового ожидания завершения IO, монитора/блокировки main или автоматического retry не добавлено.
- Код до/после submit выполняется на прежнем ViewModel dispatcher; поколение не сериализуется и не становится доступом к ticket. Domain/repository/DI и границы авторизации не менялись.

### Новые штатные регрессии

В EventViewModelTest добавлены `cancelledGuestFinallyCannotUnlockAnotherSubmission` и `cancelledAuthenticatedFinallyCannotUnlockAnotherSubmission` на общей проверке. Используются реальные EventRepository/validation и repository с управляемыми continuations; они задерживают окончание отменённого вызова, а не предполагают мгновенную кооперативную отмену.

Последовательность: A → leaveForm → B → завершить A → попытаться изменить поле/отправить ещё раз. Проверяются два вызова, сохранение busy/формы B, отсутствие старой ошибки/receipt/action. Затем завершается B: busy снимается, Network отображается и новый явный retry разрешён. В finally теста завершаются все удержанные continuations и сбрасывается тестовый Main dispatcher. Оба новых теста вошли в независимый успешный прогон всех семи EventViewModel-тестов.

### Независимое повторение прежнего доказательства

Отдельная JVM-диагностика повторяет исходный guest-сценарий ревью 1e54b80 на compiled классе текущего коммита. После запуска B завершается только отменённая A; B остаётся незавершённой. Получено:

```text
BEFORE_OLD_FINAL calls=2; submitting=true
AFTER_OLD_FINAL calls=2; submitting=true
AFTER_EXTRA_TAP calls=2; secondStillPending=true
AFTER_B_FINISHED_RETRY calls=3; retryAllowed=true
```

Assertions подтверждают сохранение блокировки и отсутствие C до завершения B; третий вызов появляется только при разрешённом явном повторе после B. Это независимо закрывает исходную P2. Двойная выдача серверного билета не проверялась и не заявляется: находка относилась к UI-блокировке и числу repository-вызовов. Authenticated-ветка проверена новым штатным тестом, а не этой отдельной guest-диагностикой.

## Документация, архитектура и сценарии

README, ADR-21, текущие строки T05 в плане и верхний раздел docs/19 согласованно описывают исправление, две новые гонки, 73 JVM-теста и 0.5.1/code 9. Старые device/process proof явно отнесены к code 8. Версия приложения изменена с code 8 на code 9; библиотеки/toolchain, seeds и snapshot schema 2 не менялись.

Изменение локально для состояния отправки в MVVM. Compose/Route, repository ports, fake/DI, ProductSnapshotCodec, QR encoder и permissions не переписывались. Подтверждённых новых нарушений SOLID/Android lifecycle best practices с самостоятельным наблюдаемым последствием не установлено. Дополнительные абстракции ради минимального исправления не требуются.

По B02/B04 сохранены прежние границы: форма не переносится через вход, гостевой QR принадлежит текущему flow/его saved state, постоянный guest cache и автосоздание аккаунта не вводятся. Actor для authenticated submit по-прежнему разрешает repository; глобальная роль/обход event-scoped доступа не добавлены. Успешный receipt и безопасное навигационное назначение остаются состоянием, а не автоматическим повтором мутации. Требования будущих T06+ и production API не применяются как уже реализованные функции этого diff.

## Выполненные проверки

PowerShell, рабочая папка `F:\ASProjects\MospolytechProject`, JBR Android Studio1:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
.\gradlew.bat :core:domain:test --rerun :data:fake:test --rerun :app:testDemoDebugUnitTest --rerun :core:platform:testDebugUnitTest --rerun :app:assembleDemoDebug :app:assembleDemoDebugAndroidTest :app:lintDemoDebug :app:verifyDemoIsolation --offline --console=plain --max-workers=2
```

**BUILD SUCCESSFUL in 10s**, 186 actionable tasks: 6 executed / 180 up-to-date. Журнал текущего независимого вызова — `.gradle/review-dbbbea1-build.log`. Все четыре unit-задачи заново исполнены; результаты сверены по XML:

| Модуль | Tests | Failures / errors / skipped |
|---|---:|---|
| core:domain | 7 | 0 / 0 / 0 |
| data:fake | 34 | 0 / 0 / 0 |
| app | 29 | 0 / 0 / 0 |
| core:platform | 3 | 0 / 0 / 0 |
| Всего | 73 | 0 / 0 / 0 |

Assemble app/test APK и lint analysis инкрементальные, не полная пересборка/анализ с нуля. Существующий lint XML содержит **0 errors / 4 warnings**. Demo isolation исполнена и успешна. В app сохранены восемь auth cancellation/generation/Mutex регрессий; новых baseline/исключений не добавлено.

Отдельная диагностическая программа использует JBR 21 и настоящий unit runtime classpath; подменяется только RegistrationRepository с управляемыми завершениями. Исходники приложения/штатных тестов не изменены. Материалы находятся в `.gradle/review-dbbbea1`: `ReviewSubmitFixed.java`, compiled class, `submit-fixed.log`, init script и classpath. Вспомогательная задача classpath завершилась успешно за 3s; app unit task при ней UP-TO-DATE. Повтор:

```powershell
.\gradlew.bat :app:reviewAuthClasspath -I .gradle/review-dbbbea1/classpath.init.gradle --offline --console=plain --max-workers=2
$reviewWork = Join-Path (Get-Location).Path '.gradle/review-dbbbea1'
$reviewRuntime = [IO.File]::ReadAllText((Join-Path $reviewWork 'classpath.txt'))
& "$env:JAVA_HOME\bin\javac.exe" -cp $reviewRuntime -d $reviewWork (Join-Path $reviewWork 'ReviewSubmitFixed.java')
& "$env:JAVA_HOME\bin\java.exe" -cp ($reviewWork + ';' + $reviewRuntime) ReviewSubmitFixed
```

Дополнительно:

- `git diff --check dbbbea1^ dbbbea1` — замечаний нет.
- SHA-256 APK: `7012bf6f5733c9e5e0f0ad48d34bc3244618454e281c19839d577c5153dc51d7`, совпадает с README/docs/19. Текущая версия 0.5.1-t05-demo / code 9.
- Сверены сохранённые RED/GREEN: `.gradle/t05-submit-fix-evidence/red-EventViewModelTest.xml` содержит 2 tests / 2 failures; red log содержит 2 failed и BUILD FAILED. Green log — BUILD SUCCESSFUL; текущий независимый полный прогон выше также зелёный. Старый код при ревью не переключался, RED заново не выполнялся.
- `adb devices` — список устройств пуст на момент проверки. Команда запустила отсутствовавший adb daemon; устройство/эмулятор и runtime tests не запускались, APK не устанавливался.
- Обычная командная среда не создаёт процесс (`helper_unknown_error: setup refresh had errors`); чтение, проверки и запись отчёта выполнены через разрешённые запуски вне неисправного окружения. Это не дефект Android-приложения.

## Ограничения и следующий шаг

Для code 9 нового device, OS process-death/QR screenshot proof, API 28/37, физического/gesture-повтора или визуального/IME/TalkBack/fontScale прогона нет. Прежние 18 device + 6 повторов и процессное доказательство относятся к code 8 и не выдаются за новую проверку исправленного APK. Условия позднего завершения детерминированно заданы JVM-тестами; частота совпадения на реальном устройстве не измерялась.

UI-геометрия и persistence в этом diff не менялись; live Figma заново не читалась. Исправление подтверждено в заявленной области; полноценная H2 всё ещё должна быть выполнена человеком на **0.5.1-t05-demo / code 9**. После H2 — T06 по плану. Новых подтверждённых source findings, требующих исправления по данному diff, не сформировано.

Результат ревью — добавление этой записи CODE_REVIEW.md; предыдущие записи сохранены побайтно. Исходники приложения/тестов, зависимости, решения и статусы не менялись. Пользовательские README/docs/prompts/daemon configuration сохранены с проверкой хешей до/после записи. Остальные созданные материалы — обычные игнорируемые build/diagnostic outputs.

---

# Ревью — 30 октября 2026 — c569152 (редактор и организаторский UI T06)

## Область и итог

Проверен `c56915269b45a59064f43fcd35779c4f4c5a1658` (`feat: integrate event editor and organizer UI`) относительно родителя коммита. HEAD совпадает с проверяемым commit. Diff: 27 файлов, 1086 добавлений / 22 удаления — MyEvents, Editor/ViewModel/Screen, managed cover adapter/preview/DI, интеграция навигации, unit/device tests и документы. Незакоммиченные правки README/docs, prompts и daemon criteria существовали до ревью; исходники приложения совпадают с проверяемым коммитом.

Применён локальный `$android-code-review`. Прочитаны AGENTS.md, README, актуальные ADR-22/23 и связанные решения, задача T06 из плана и карта документации. Учтены engineering rules для состояния/поздних ответов, B01–B08 и актуальная часть docs/20. Изучены новые UI/VM, navigation/DI diff, импорт/preview/GC обложки, domain editing policy, связанные read/owner guards существующего fake и новые тесты. Это ревью UI-интеграции T06 с проверкой её границ, не повторный полный аудит всей предшествующей foundation.

**Новых подтверждённых находок P0–P3 в проверенном diff не обнаружено.** Независимо повторены 101 unit-тест, собраны app/test APK, успешно выполнены lint task и demo isolation. Сохранённые 9 device-результатов сверены по итоговым instrumented-журналам; нового device-прогона ревьюера не было. Отсутствие находок не означает безошибочность всего MVP или принятую визуальную/ручную организаторскую часть.

H2/T05 приняты пользователем по актуальной документации; T03/T04 также остаются принятыми. **T06 — READY_FOR_HUMAN; организаторский smoke ожидается, H3/H4 не закрыты.** Никакие приёмки или статусы ревьюером не менялись.

## Пользовательские сценарии и документация

- MyEvents использует один repository-поток, три вкладки organizing/volunteering/drafts и SavedStateHandle для выбранной вкладки. Временный read failure сохраняет прежний Ready content и показывает ошибку; отсутствие данных/загрузка не объявляются пустым успешным списком. Owner и grant не взаимоисключающие; editing/copy actions владельца отделены от открытия общего публичного E.
- Редактор открывается по ID draft/event, а не по копии чувствительного документа в route. Чужие поля не отображаются при Forbidden. В UI не вводится глобальный User.role; read-for-editing и mutation используют существующие event-scoped guards repository, а сохранённый back stack не даёт полномочий.
- Явное сохранение допускает неполный draft, публикация проходит полную доменную validation. EditorInput отдельно держит raw date/capacity строки, не объявляя частично набранную дату сохранённым Instant. Парсинг строгий в выбранной IANA zone; gap/неоднозначность DST отклоняются. Неизвестная зона сохранённого неполного draft обрабатывается без падения formatter. Требования будущего backend к timezone/DTO этим не объявляются согласованными.
- Dirty Back предлагает save/discard/stay; save failure не отмечает saved/exit. На mutation повторные действия заблокированы. Повтор текущего таба и toolbar Back передаются editor callback, переключение на другой таб сохраняет entry. Unsaved input живёт в ViewModel при rotation; постоянное восстановление обеспечивается только явно сохранённым repository draft, autosave не заявляется.
- При dirty/busy input редактор не перенимает новую revision из наблюдения и не перезаписывает внешний документ молча. Conflict сохраняет ввод. После результата suspend current проверяет cancellation/generation; старый finally не снимает busy новой entry и отменённое save не запускает следующий publish. Новая регрессия проверяет поздний A после rebind/B, а прежние T05 busy/auth regressions сохранены.
- Вопросы трёх типов имеют стабильные ID, required и порядок; reorder не пересоздаёт ID. Первая регистрация блокирует схему по истории, а не по текущему количеству Active tickets. UI объясняет блокировку, а data guard остаётся независимым от выключенных кнопок. Вопросы после отмены всех регистраций автоматически не разблокируются.
- Publication result остаётся в состоянии editor entry с реальной ссылкой eventId; Clipboard/Sharesheet не являются дополнительной публикацией. Открывается общий EventRoute. Close registration/finish используют guarded repository commands и revision; dirty input требуется сначала сохранить/отменить. Started/terminal ограничения остаются централизованными в domain policy.
- Description/sections не получили нового UI editor и сохраняются из исходных полей. Это соответствует объявленному объёму; не требуются будущие organizer Dashboard/Scanner/Volunteers из T07/T08 как уже реализованные функции.

README, ADR-23, navigation/contracts и текущая часть docs/20 согласованно описывают code 11, границы ручной приёмки и сохранённые результаты. Исторические foundation/H2 записи ниже в документах не используются как текущий статус. Свежая Figma-проверка разработки была частичной; в этом ревью live Figma и точная геометрия O01/V01/O02/O03 заново не сверялись.

## Обложка, lifecycle и архитектура

**Места:** `AndroidEventCoverStore.kt`, `CoverPreviewViewModel.kt`, `EditorViewModel.kt`, `EditorScreen.kt`, demo DI и `retainedCoverAssetIds`.

- ViewModel использует domain CoverImporter; preview вынесен за EventCoverPreview interface. Context, content resolver, файлы и Bitmap decoder находятся в platform adapter; конкретный fake остаётся в demo DI. Доменный EventCover содержит проверенный opaque assetId/MIME/byte count, не произвольный путь/URI/bitmap.
- OpenDocument выбирает PNG/JPEG; URI используется для немедленной копии в собственный noBackup storage. Полученные данные не требуют дальнейшего доступа к source URI. Такой user-selected доступ соответствует [официальному Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files). Широких media permissions в этом diff не добавлено.
- Import принимает content URI, ограничивает поток 5 MiB, проверяет decoded MIME/dimensions и пробный sampled decode, не доверяя provider MIME или расширению. IO и декодирование выполняются на IO dispatcher; изображение проверяется до rename из временного файла в managed asset. При ошибке/отмене tmp удаляется в finally, CancellationException не подменяется успешным импортом.
- Preview ограничивается sampleSize, EXIF-преобразования выполняются адаптером. Изменение/снятие обложки отменяет предыдущую preview job и очищает UI image; entry-scoped VM не является глобальным cache. Это не новая проверка bitmap памяти на всех Android/реальных файлах.
- Startup GC вызывается перед первым импортом нового процесса; retained IDs берутся из всех persisted events/drafts под общим Mutex. При нездоровом snapshot очистка пропускается. GC не запускается на каждом импорте и не удаляет новые несохранённые assets других живых editor entries в том же процессе. Схема 3 и авторизация foundation не переписывались.
- Root/hidden owners и PrivateScope из T04 сохраняются; основной UI/VM не знает fake/storage DTO. Подтверждённых новых нарушений MVVM/SOLID с самостоятельным наблюдаемым последствием не установлено. Гостевой QR/cache, account/guest инварианты и серверное распределение мест не менялись этим срезом.

## Независимо выполненные проверки

PowerShell, рабочая папка `F:\ASProjects\MospolytechProject`, JBR Android Studio1:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
.\gradlew.bat :core:domain:test --rerun :data:fake:test --rerun :app:testDemoDebugUnitTest --rerun :core:platform:testDebugUnitTest --rerun :app:assembleDemoDebug :app:assembleDemoDebugAndroidTest :app:lintDemoDebug :app:verifyDemoIsolation --offline --console=plain --max-workers=2
```

**BUILD SUCCESSFUL in 9s**, 187 actionable tasks: 8 executed / 179 up-to-date. Журнал `.gradle/review-c569152-build.log`. Все четыре unit-задачи исполнены повторно; XML сверен:

| Модуль | Tests | Failures / errors / skipped |
|---|---:|---|
| core:domain | 11 | 0 / 0 / 0 |
| data:fake | 51 | 0 / 0 / 0 |
| app | 36 | 0 / 0 / 0 |
| core:platform | 3 | 0 / 0 / 0 |
| Всего | 101 | 0 / 0 / 0 |

Новые семь EditorViewModel-тестов проверяют incomplete save/reload/publish, busy/no duplicate, dirty Back/storage failure, timezone/DST/date errors, forbidden route/logout, dirty read retry, stale revision и поздний cancelled save/finally. Это проверка конкретных инвариантов, а не всей визуальной поверхности. Полная foundation миграция/storage не заявляется заново вручную аудированной по одному зелёному набору.

Сборка app/test APK и lint analysis инкрементальные; clean/full lint analysis с нуля не выполнялись. Существующий lint XML — **0 errors / 4 warnings**, без новых baseline/exceptions. Demo isolation исполнена и успешна: только demoDebug, fake вне main implementation. Сочетание установленных библиотек подтверждено этой сборкой, будущая совместимость не заявляется.

Дополнительно:

- `git diff --check c569152^ c569152` — без замечаний.
- Текущий APK SHA-256 `ab1567bb070cab86dca460830feb9298144495b38a0eeeccf87b9aadcb7d028d` совпадает с README/docs/20; версия 0.6.1-t06-demo / code 11.
- `adb devices` видит подключённое устройство; оно не использовалось для установки/прокликивания/смены настроек в этом ревью. Новый эмулятор не запускался.
- Обычная командная среда не создаёт процесс (`helper_unknown_error: setup refresh had errors`). Чтение, проверки и запись отчёта выполнены разрешёнными запусками вне неисправного окружения; это не Android-дефект.

## Сохранённые device-доказательства

Это **результаты разработки**, не новые instrumented-запуски ревьюера. Прочитаны `.gradle/t06-ui-evidence/device-delivery.log` и `device-final-apk.log`: оба завершаются `OK (9 tests)`, итоговый delivery run — 56.991 s. Это ручной am instrument журнал, его INSTRUMENTATION_CODE=-1 с OK соответствует успешному завершению, а не новому тестовому failure.

По исходникам и docs/20 состав — 4 EventEditorIntegrationTest + 5 NavigationSmoke/EntryOwnership. Проверяются guarded foreign editor, incomplete draft/recreation→publish/Clipboard→общий E→guest answer→блокировка схемы, новый backend на реальном AtomicFile с восстановленной draft/cover и Guest session, content/oversize/unsupported URI, managed copy после удаления source, retained/unreferenced GC и EXIF orientation. Прежние скрытые owners/Back/tab/warm link проверки входят в выбранный набор; все остальные тесты приложения не объявляются повторно выполненными на устройстве по этим девяти.

Импорт через test provider не доказывает реальный системный picker/cloud provider/отказ grant/Cancel; подготовленный Sharesheet не доказывает выбор настоящего share target. Новый backend и Activity recreation не подменяют OS process-kill proof. Test-only fixture provider добавлен в androidTest sources/manifest, не production application manifest.

## Ограничения и следующий шаг

Нового device-прогона, OS process-death editor, landscape/gesture/API 28/API 37, реального picker/Share target selection, ширин/fontScale/IME/TalkBack и полного визуального сравнения новых экранов не было. Физический телефон не использовался. Организаторский smoke T06 остаётся за человеком на конкретном code 11; H3/H4 этим ревью не закрыты. Production API/письма/signing/App Links, камера/check-in и редактор произвольных будущих серверных данных не подтверждаются этим изменением.

**Следующий шаг:** ручной организаторский smoke по текущему checklist docs/20 (draft/save/restart/questions/cover/publish/share/guest submit/schema lock/close/finish), затем T07 по плану. Новых подтверждённых source findings, требующих исправления по проверенному diff, не сформировано.

Предыдущие записи CODE_REVIEW.md сохранены побайтно. Исходники приложения/тестов, зависимости, конфигурация, решения и статусы ревьюером не менялись. Существующие пользовательские README/docs/prompts/daemon criteria сохранены с проверкой хешей до/после добавления отчёта. Дополнительный результат выполнения — только обычный игнорируемый build log.

---

# Ревью — 1 ноября 2026 — 555a2a0 (системный Back и сохранение родителя)

## Область и состояние

Проверен коммит `555a2a03498c52b35461abee3c6a34ad724add48` (`fix: preserve parent screens on system back`, 1 ноября 2026, HEAD) относительно его непосредственного родителя. В коммите 13 файлов, 259 добавленных и 12 удалённых строк. Это адресное ревью исправления Back T05/H2 и связанных переходов T06, а не повторный аудит всех T01–T07.

Production diff: `TicketsScreen.kt`, `NavigationState.kt`, `EventPassApp.kt` и versionCode/versionName в `app/build.gradle.kts`. Дополнительно проверены новые `ProductBackNavigationTest`, четыре новых `NavigationStateTest`, усиление `EventEditorIntegrationTest`, README/план/ADR-25 и отчёт `docs/23-navigation-back-validation.md`. Для проверки предположений прочитаны связанные TicketsViewModel/SavedStateHandle, EventRoute, editor Back wiring, QR/confirmation dialogs, декораторы private стеков и вызывающие места openEvent/openLinkedEvent.

Использован локальный `$android-code-review`; прочитаны AGENTS.md, README, карта документации, относящиеся решения/задачи, инженерные правила и ответы B01–B08. Текущие `docs/03-navigation.md` и `docs/architecture/state-ownership.md` описывают исправленное поведение. Они содержат предсуществующие незакоммиченные изменения: их содержимое учтено как текущий рабочий контекст, но не приписано целиком этому коммиту. Значительная пользовательская переработка архитектурных/API-документов, AGENTS.md, prompts и Gradle daemon criteria сохранена.

## Находки

**Новых подтверждённых P0, P1, P2 или P3 в проверенном diff не обнаружено.** Это вывод об указанном исправлении и прочитанном связанном коде, а не утверждение об отсутствии ошибок во всём приложении. Адресная человеческая перепроверка Back ещё нужна.

Два исходных замечания пользователя имеют обоснованное исправление:

1. **Карточка своего билета → Back → список.** В `app/src/main/kotlin/ru/eventpass/app/feature/tickets/TicketsScreen.kt:29–32` добавлен BackHandler для выбранного билета. Он вызывает существующий `TicketsViewModel.select(null)`, меняющий одновременно UiState и сохранённый `ticketSelection`. Корневой стек действительно не содержит отдельной entry карточки, поэтому обычный pop не мог обеспечить этот возврат. Сегмент current/history не сбрасывается; при отсутствии выбора handler выключен. Изменение не создаёт второго источника выбора в shell.
2. **Внутреннее открытие мероприятия → Back → прежний родитель.** В `app/src/main/kotlin/ru/eventpass/app/navigation/NavigationState.kt:66–77` внутренний openEvent добавляет EventKey в текущий стек и сохраняет вкладку. Повтор одинакового верхнего eventId не добавляет entry. Вызовы MyEvents, Tickets и Editor используют этот путь; удаление EventKey оставляет прежний root/редактор/результат публикации. Внешняя доставка и ReturnDestination.Event в `EventPassApp.kt:59,67` явно направлены в openLinkedEvent с прежними Tickets/dedup/reset правилами. Другие вкладки этим внешним входом не очищаются.

Пункты выше — проверка исправлений, не новые замечания с приоритетом.

## Сценарии, архитектура, SOLID и Android

| Область | Проверенный результат и предел доказательства |
|---|---|
| Карточка и история | Back очищает только выбор, сохраняя историю. Новый device-тест проверяет recreation до Back и ещё раз после него: удалённый ID не должен восстановиться из SavedStateHandle. History проверяется отдельным сценарием. |
| Fullscreen QR и отмена | Новый handler отключён при fullscreen/confirming. QR использует Dialog(onDismissRequest), отмена — AlertDialog с существующим dismiss; состояние карточки остаётся. При выполняющейся отмене dismiss остаётся заблокирован прежним loading guard. Сохранённый device-прогон проверяет закрытие обоих окон прежде карточки и ticket → E → card → list. |
| MyEvents | Владельцем списка и сегмента остаётся прежний root VM. Новый тест проходит Owned → E → recreation → Back и Volunteer → E → другая вкладка → Events → Back, проверяя нужный таб/сегмент. Root/hidden entry decorators продолжают жить у прежних владельцев. |
| Публикация | EditorKey сохраняется под EventKey. Усиленный integration-тест проверяет E → системный Back → published-screen в Events → повторное открытие E; затем продолжает прежний guest/schema-lock сценарий. |
| Внешние ссылки | PrivateShell использует openLinkedEvent в обоих местах доставки. Новый unit-тест проверяет Tickets-родителя, отсутствие дублей и сохранение чужого стека MyEvents. Public host и парсеры ссылок этим коммитом не менялись. |
| MVVM и ответственность | Route связывает системное действие с существующей командой VM; Screen не получил repository/NavController. Shell выбирает навигационную политику, NavigationState меняет стек, VM владеет ticketSelection/history. Разделение внутреннего перехода и внешней доставки соответствует различию их сценариев; новых инфраструктурных абстракций не требуется. |
| SOLID/DI | Новых зависимостей между feature VM, обходов domain ports или протекания fake/DTO/storage в UI нет. Наблюдаемого нарушения контрактов существующих вызовов не выявлено; стиль/количество методов не выдаются за дефекты SOLID. |
| Доступ, данные, корутины | EventKey по-прежнему несёт ID и новый UUID flow, а не прошлое разрешение или QR. Guards/repository authorization, PrivateScope cleanup, guest receipt owner, schema 4, submit/cancellation/generation logic не изменены. Нового persistent guest cache, глобальной роли и автосоздания аккаунта нет. Эти неизменённые подсистемы полностью заново не аудировались. |
| Android Back | Handler задаётся без условного удаления composable, включение зависит от текущего состояния. Снятие локальной карточки и pop отдельной EventKey соответствуют различию UI-state и entry. Успех KEYCODE_BACK не доказывает predictive animation или работу жеста на всех API. |

Для принципов управления стеком и локальным Back сверены актуальные первичные источники: [Navigation 3 basics](https://developer.android.com/guide/navigation/navigation-3/basics) и [custom Back в Compose](https://developer.android.com/guide/navigation/custom-back). Документация описывает владение стеком приложением и включаемый по состоянию BackHandler; вывод о конкретном исправлении сделан по исходникам и тестам проекта. Общая совместимость всех зафиксированных версий стека с будущими API этим не заявляется.

## Свежие независимые проверки ревьюера

PowerShell, JBR 21 из установленной Android Studio, существующий wrapper, offline:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
.\gradlew.bat :app:testDemoDebugUnitTest --rerun :app:assembleDemoDebug :app:assembleDemoDebugAndroidTest :app:lintDemoDebug :app:verifyDemoIsolation --offline --console=plain --max-workers=2
python scripts/verify-architecture-docs.py
git diff --check 555a2a0^ 555a2a0
```

- Gradle: **BUILD SUCCESSFUL in 27s**, 178 actionable tasks: **3 executed, 175 up-to-date**. `--rerun` относится к app unit Test task: она фактически исполнена; это не clean/rebuild всех задач.
- XML `app/build/test-results/testDemoDebugUnitTest/TEST-*.xml`: **41 тест, 0 failures, 0 errors, 0 skipped**. В NavigationStateTest всего 8 тестов, из них 4 добавлены этим коммитом. Device-тесты в это число не входят.
- App/test APK доступны через успешные assemble tasks, обе UP-TO-DATE. Компиляция Hilt и всех sources заново не объявляется выполненной. Lint task успешна, анализ/отчёт инкрементальны; XML содержит **0 errors / 4 warnings**. Новых baseline/исключений не добавлялось.
- `verifyDemoIsolation` фактически исполнена: только demoDebug включён, fake отсутствует в main implementation.
- Архитектурный валидатор: **OK**, 11 current pages, **195 local links**, 9 Mermaid blocks, 18 mobile IDs, S01–S14 и named test references. Это проверка текущего рабочего дерева с пользовательскими документами; скрипт не проверяет rendering Mermaid и семантику Kotlin. Число 194 в development-отчёте не подменяет свежие 195.
- `git diff --check 555a2a0^ 555a2a0`: успешно, замечаний нет.
- Проверены размер и SHA-256 выходного APK и архивного `artifacts/apk/eventpass-0.7.1-t07-foundation-demo-code13.apk`: **34 079 002 байт**, одинаковый SHA-256 **`5afa868a6eb19e301e24b83f9da1018985c2a03a7e6e8b11d271f3cf40ff41c8`**. VersionName **0.7.1-t07-foundation**, demo suffix в variant, versionCode **13**; значения совпадают с README и отчётом разработки.

Свежий build log: `.gradle/review-555a2a0-build.log`. Обычный sandbox PowerShell не стартовал с `helper_unknown_error: setup refresh had errors`; тот же инструмент успешно выполнен с разрешённым повышением. Это ограничение среды запуска, а не найденный дефект приложения. Domain/fake/platform JVM suites заново не запускались: их source/contracts в данном коммите не изменены. Прежние 126 JVM T07 не считаются новым результатом ревью.

## Сохранённые device-доказательства разработки

Нового instrumented-запуска в этом ревью **не было**. Прочитаны исходники тестов и сохранённые `.gradle/back-navigation-baseline/red-device.log`, `green-device.log`, `history-diagnostic.log`, `final-device.log`.

- RED: 2 теста / 2 failures на прежнем code 12 — после Back карточки потеряна Compose hierarchy и E из MyEvents не остаётся во вкладке Events. Это сохранённое воспроизведение, а не свежий запуск ревьюера.
- Первый GREEN набор завершился неуспешно: 3/4; history сразу после доставки Back ещё видел selection. Отдельный diagnostic — OK (1 test); гипотеза о клавиатуре как причине не подтверждена. Текущий тест закрывает IME после login и ждёт наблюдаемое изменение selection; не повторяет Back и не подавляет failure.
- Итоговый `final-device.log`: **OK (10 tests), 78.843s**, INSTRUMENTATION_CODE=-1 при успешном итоговом OK. Состав: 4 ProductBackNavigationTest + 4 NavigationSmokeTest + 1 EntryOwnershipTest + 1 усиленный editor integration. Журнал согласуется с docs/23.
- По development-отчёту это отдельный API 33 AVD, portrait, три кнопки, update code 12 → code 13, тестовая fixture; пользовательский телефон не использовался. Ревьюер не выполнял install/reset/settings на каком-либо устройстве.

Эти данные повышают уверенность в реальной доставке KEYCODE_BACK и порядке закрытия окон; их недостаточно для объявления всей Android runtime-матрицы проверенной.

## Вопросы, допущения и ограничения

Блокирующих вопросов по конкретному diff не выявлено. Внешний E после входа по-прежнему имеет Tickets-родителя: это явно принятая политика ADR-25, а не случайное продолжение внутреннего открытия. Не требуются будущие Scanner/Volunteers UI, Dashboard или настоящий backend для приёмки этого исправления.

Не проверены свежим runtime-прогоном: жест Back/predictive animation и отмена жеста, landscape, API 28/37, физическое устройство, OS process death, IME/font scale/TalkBack и визуальное соответствие Figma. Activity recreation в сохранённом тесте не равна уничтожению процесса ОС. Figma в рамках узкого Back diff заново не открывалась. Security/API/storage/performance всего приложения не покрыты полным аудитом.

**Ожидается адресная человеческая перепроверка Back H2 на code 13** по docs/23: current/history, fullscreen/confirmation, ticket → E → card, MyEvents обоих сегментов → E → свой список, publication → E → свой result. Организаторский smoke T06 остаётся отдельным ожидающим шагом на текущей сборке; H3/H4 этим ревью не закрыты. После подтверждения T06 — оставшаяся UI-интеграция T07 по плану. Ревью не меняет статусы задач или приёмок.

Предыдущие записи CODE_REVIEW.md сохранены побайтно. Исходники приложения/тестов, документация вне отчёта, зависимости, конфигурация и пользовательские изменения ревьюером не менялись; неизменность контролируется SHA-256 перед проверками и после добавления записи. Дополнительные игнорируемые артефакты — build log и baseline хешей в .gradle.
---

# Ревью — 1 ноября 2026 — 48c09b9 (Volunteers UI и общий QR Scanner)

## Область и состояние

Проверен `48c09b995c4f3e508789295c3942b39eda6e1e28`, HEAD, `feat: integrate volunteer UI and shared QR scanner`, 1 ноября 2026, относительно непосредственного родителя. Diff: 28 файлов, 919 добавленных / 22 удалённые строки. Область — Android UI/lifecycle T07, CameraX/ZXing, выдача/отзыв прав, manual code, навигационное подключение, manifest/dependencies и относящиеся проверки/документы. Foundation ScannerController/domain/fake/schema 4 прочитана в вызываемой части; отдельное полное ревью предыдущего foundation-коммита этим не подменяется.

Использован `$android-code-review`. Прочитаны корневые инструкции, README, относящиеся решения/задача T07 из 08/06, маршрут 00, правила 14, B01–B08, текущие navigation/state ownership/invariants и новая lifecycle-страница T07. Текущие README и документы содержат предсуществующие пользовательские изменения; они учтены как рабочий контекст, не приписаны целиком коммиту. Исходники приложения и тестов чисты относительно HEAD. Незакоммиченные документы/API/prompts/конфигурация сохранены.

Актуальное состояние приёмок изменилось после предыдущей записи: **Back/H2 и T06 приняты пользователем на code 13** по свидетельству в docs/23 и текущем плане. Повторного подтверждения этих приёмок не требуется. T07 — READY_FOR_HUMAN; camera/UI-проверка и полные H3/H4 не приняты.

## Подтверждённые находки

### 1. [P2] Ошибка отзыва права скрыта за оставшимся диалогом подтверждения

**Основное место:** `app/src/main/kotlin/ru/eventpass/app/feature/volunteers/VolunteersScreen.kt:32–34`. Связанный код: `VolunteersViewModel.kt:45–56`; вывод ошибки — `VolunteersScreen.kt:45–52`.

**Сценарий:** владелец открывает Volunteers, выбирает «Отозвать право», подтверждает; repository возвращает NETWORK, TIMEOUT или STORAGE_UNAVAILABLE. `fail` для этих исходов сохраняет `revoke`, добавляет `error`; finally снимает busy. Условие `state.revoke?.let` поэтому оставляет AlertDialog открытым. В этот диалог передаются только email, confirm/dismiss и loading: ошибка туда не передаётся. Единственный InlineError и «Повторить» находятся на основном экране под модальным окном.

**Последствие:** после завершения неуспешного запроса пользователь остаётся в диалоге с прежним подтверждением без объяснения результата. Ошибка/действие восстановления находятся вне активного модального контекста; нужно самостоятельно закрыть диалог, чтобы прочитать ошибку и воспользоваться retry. Это не ложный успешный отзыв и не потеря прав, но некорректная обратная связь в предусмотренных ветках transport/storage failure. TalkBack также не может считать сообщение основного экрана частью активного диалога.

**Доказательство:** отдельный JVM helper `.gradle/review-48c09b9/ReviewRevokeFailure.java` использует настоящий VolunteersViewModel и реализацию существующего VolunteerRepository через proxy, возвращающую каждый из трёх типизированных Failed. Успешный запуск:

```text
error=NETWORK; confirmationStillOpen=true; busy=false; listRetained=true
error=TIMEOUT; confirmationStillOpen=true; busy=false; listRetained=true
error=STORAGE_UNAVAILABLE; confirmationStillOpen=true; busy=false; listRetained=true
```

Наличие ошибки только за диалогом установлено по wiring Route/Screen/общего ConfirmDialog, не по новому device screenshot. Helper проверяет реальное VM-состояние, не моделирует Android rendering и сеть. Существующие VolunteersViewModelTest покрывают успешный revoke и ошибку grant, но не такой неуспешный revoke с открытым confirmation.

**Направление исправления:** либо закрывать confirmation при неуспешном revoke и показывать уже существующую ошибку на доступном основном экране, либо отображать ошибку и осмысленный повтор непосредственно в диалоге. Сохранить single-flight и блокировку dismissal во время отправки. Добавить адресную проверку fail revoke → доступное объяснение/повтор; исходники в рамках ревью не менялись.

### 2. [P2] Optional camera.any не отменяет обязательность задней камеры в APK

**Место:** `app/src/main/AndroidManifest.xml:3–4`.

**Сценарий:** приложение распространяется через Google Play на устройство без задней камеры, хотя остальные сценарии и ручная проверка билета могут работать без неё. Добавленный CAMERA permission неявно требует `android.hardware.camera`. Объявление только `android.hardware.camera.any required=false` этот другой feature не отменяет.

**Последствие:** такие устройства будут отфильтрованы Google Play целиком, а не получат приложение с доступным ручным вводом. Это дефект описания аппаратных требований, а не падение сканера на проверенном AVD. Текущую локальную установку demo APK/sideload и ручной smoke code 14 он не блокирует; утверждения о фактической публикации проекта в Play нет.

**Доказательство:** проверен merged manifest и выполнен aapt над точным выходным APK code 14:

```text
uses-feature-not-required: name='android.hardware.camera.any'
uses-feature: name='android.hardware.camera'
uses-implied-feature: name='android.hardware.camera' reason='requested android.permission.CAMERA permission'
```

Полный вывод фильтра сохранён в `.gradle/review-48c09b9/manifest-features.log`. Нужное различие `camera.any`/`camera` и правила фильтрации подтверждает [официальная документация uses-feature](https://developer.android.com/guide/topics/manifest/uses-feature-element#permissions). Это проверка реально сформированного требования, не предположение о поведении CameraX.

**Направление исправления:** явно объявить заднюю камеру необязательной (`android.hardware.camera required=false`), поскольку приложение имеет сценарии без камеры, и проверить итоговый APK через aapt. Остальные аппаратные требования объявлять исходя из реальной необходимости; не считать optional camera.any универсальным override. Реализация поддержки фронтальной камеры не требуется для исправления фильтра. Manifest ревьюером не изменён.

P0/P1 в проверенном diff не выявлены. Обе находки требуют отдельного разрешения на исправление исходников; ревью само его не предоставляет.

## Проверенные сценарии и архитектурные границы

| Область | Оценка по прочитанному коду и существующим проверкам |
|---|---|
| Один аккаунт, права по E | Owner/volunteer открывают один Scanner из общего MyEvents; Volunteers доступен из owner-сегмента. Repository повторно проверяет actor/eventId, owner сканирует без self-grant; глобального User.role и отдельного scanner по ролям нет. Кнопки не заменяют авторизацию. |
| Выдача/отзыв | Прежний VolunteerRepository ищет существующий аккаунт, не создаёт приглашение/аккаунт. Grant/revoke меняют общий store; личный билет не удаляется. Busy ставится до launch, generation/ensureActive защищают позднюю команду и finally новой entry. Ошибка активного revoke описана отдельно выше. |
| Check-in | ScannerViewModel использует прежний ScannerController, а тот — CheckInRepository. Синхронный переход SUBMITTING исключает вторую отправку manual/frame; после ответа проверяются текущие access/scope/generation. Transport/storage error отделены от Invalid/Admitted, автоматического retry нет. |
| Результаты | Admitted, AlreadyUsed и Invalid — UiState одной entry, не новые result routes. Legacy Used без first record отображается честно; время имеет явную UTC. Back результата очищает результат, следующий Back удаляет ScannerKey; top/reselect используют тот же callback. |
| Состояние/PII | Ключи содержат eventId и UUID, без credential/допуска/участника. Manual input очищается при submit и foreground(false); receipt format не расширяется постоянным guest lookup/cache. Guest QR/код принадлежат прежнему flow; аккаунт от заявки не создаётся. |
| Lifecycle | Route использует current/visible и RESUMED. Hidden/background/dispose отменяют запрос и очищают result/input; preview исчезает. ScannerController/VM и Volunteers VM живут у entry/private scope; logout уничтожает owners. Recreation переводит scanner в ready, не восстанавливает прежний Admitted. |
| CameraX ресурсы | Отдельный worker, KEEP_ONLY_LATEST, каждый ImageProxy.close в finally, остановка analysis и unbind только своих Preview/ImageAnalysis. stopped блокирует поздний provider bind; delivered и захваченный generation защищают frame callback. unbindAll не используется. |
| Decoder | Читается Y plane с row/pixel stride/crop, ограничены размеры и доступ к buffer. ZXing даёт кандидаты, не решает право прохода. Проверяются rotated/padded/multiple/blank/truncated кадры. Физическая оптика этим не доказана. |
| MVVM/SOLID/DI | Route отвечает за Android lifecycle/permission/navigation, VM — координацию state и портов, platform — камеру/decoder. Compose/VM не получили fake/DTO/storage. Репозитории не вызываются напрямую из camera adapter. Новых нарушений этих границ не подтверждено; дополнительные слои/use case ради стиля не требуются. |
| Неизменённые правила | Сервер/fake решает право допуска/Used, Android показывает outcome. Schema 4/migration/auth/credential semantics и проект API не менялись. Backend не объявляется работающим или согласованным. Dashboard/экспорт/полный профиль остаются будущими этапами. |

CameraX 1.6.2 подтверждён stable по [официальным release notes](https://developer.android.com/jetpack/androidx/releases/camera). Правила backpressure и ImageProxy.close сверены по [официальному image analysis guide](https://developer.android.com/media/camera/camerax/analyze). Это не доказательство runtime-совместимости всех библиотек на всех поддерживаемых API. Figma live в ревью не открывалась: учтены реестр mobile-frame и ограничения design сверки, честно записанные разработкой в docs/24. Визуальная идентичность O05/V02–V05 не заявляется.

## Свежие проверки ревьюера

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
.\gradlew.bat :core:domain:test --rerun :data:fake:test --rerun :app:testDemoDebugUnitTest --rerun :core:platform:testDebugUnitTest --rerun :app:assembleDemoDebug :app:assembleDemoDebugAndroidTest :app:lintDemoDebug :app:verifyDemoIsolation --offline --console=plain --max-workers=2
python scripts/verify-architecture-docs.py
git diff --check 48c09b9^ 48c09b9
& 'C:/Users/XataB/AppData/Local/Android/Sdk/build-tools/36.1.0/aapt.exe' dump badging 'app/build/outputs/apk/demo/debug/app-demo-debug.apk'
```

- Gradle **BUILD SUCCESSFUL in 10s**, 187 actionable tasks: **6 executed / 181 up-to-date**. Все четыре Test task принудительно исполнены через task option --rerun. Остальные задачи инкрементальны, clean не выполнялся.
- XML: **138 JVM-тестов, 0 failures/errors/skipped** — domain 20, fake 66, app 46, platform 6. Из них 8 новых: 2 ScannerUiWiringTest, 3 VolunteersViewModelTest, 3 QrFrameDecoderTest. Существующие unit suites не проверяют отображение revoke failure под модальным окном или Play-фильтры.
- App/test APK assemble — UP-TO-DATE; свежая полная перекомпиляция всех исходников/DI не заявляется. Lint task успешна, анализ/отчёт инкрементальны: **0 errors / 4 warnings**. Подавлений/нового baseline нет.
- verifyDemoIsolation исполнена: только demoDebug включён, fake отсутствует в main implementation.
- Архитектурный скрипт: **OK**, 12 current pages, 215 local links, 9 Mermaid blocks, 18 mobile IDs, S01–S14 и named test references. Это структурная проверка текущего рабочего дерева, не rendering Mermaid/проверка Kotlin-семантики.
- `git diff --check` указанного коммита: успешно.
- APK output и архивный `artifacts/apk/eventpass-0.7.2-t07-demo-code14.apk` совпадают по SHA-256: **`a7f99c3af36b7c166305123f520a3d5509113bdb4a39c3ab5aa0e38ec20bcb3d`**, **44 354 768 байт**, **0.7.2-t07-demo, code 14**, minSdk 28/compile-target 37. Это подтверждает идентичность артефактов, а не ручную приёмку.

Свежий лог — `.gradle/review-48c09b9-build.log`. PowerShell выполнен с разрешённым повышением из-за известной ошибки обычного sandbox helper; глобальные настройки среды не менялись.

Для точечного JVM-воспроизведения находки 1 дополнительно выполнены:

```powershell
.\gradlew.bat :app:reviewVolunteerClasspath -I .gradle/review-48c09b9/classpath.init.gradle --offline --console=plain --max-workers=2
$root = (Resolve-Path '.gradle/review-48c09b9').Path
$cp = Get-Content '.gradle/review-48c09b9/classpath.txt' -Raw
$java = 'C:/Program Files/Android/Android Studio1/jbr/bin'
& "$java/javac.exe" -encoding UTF-8 -cp $cp -d $root "$root/ReviewRevokeFailure.java"
& "$java/java.exe" -cp ($root+';'+$cp) ReviewRevokeFailure
```

Classpath task успешна за 3s, 74 tasks: 1 executed / 73 up-to-date; зависимая app Test task здесь UP-TO-DATE и не считается ещё одним свежим прогоном. javac/java успешны; итог — `.gradle/review-48c09b9/revoke-failure.log`. Helper и init находятся только в игнорируемой .gradle, не меняют приложение/его тесты и не входят в число 138.

## Сохранённые device-доказательства разработки

Нового device-прогона в этом ревью **не было**. Прочитаны `.gradle/t07-ui-evidence/device-release.log` и `device-release-denial.log`: **OK (8 tests), 83.629s** и **OK (1 test), 11.902s**. Это результаты разработки, не запуски ревьюера. INSTRUMENTATION_CODE=-1 при итоговом OK не означает failure.

По журналам, исходникам и docs/24: четыре CheckInUiIntegrationTest охватывают manual Invalid/Timeout/Admitted/AlreadyUsed/Back/recreation, owner grant/revoke/scan, STREAMING и освобождение CameraX при hidden/background/Back/revoke/logout, denial настоящего permissioncontroller и реальный ZXing на controlled frame через controller. Ещё четыре ProductBackNavigationTest и один guest receipt тест с manual-code assert сохраняют адресные регрессии предыдущих этапов. Ошибка revoke из находки 1 этим набором не проверяется.

Development proof относится к точному code 14 в отдельном API 33 AVD, portrait/three-button, согласно docs/24. Controlled decoder → controller и STREAMING/unbind — отдельные доказательства; они не образуют тест реального чтения бумажного QR всей цепочкой камеры. Пользовательское устройство ревьюером не использовалось, APK/permissions/settings на устройствах не менялись.

## Вопросы, ограничения и дальнейший шаг

- Нет подтверждения физической камеры: QR с другого экрана/бумаги, фокус/экспозиция/слабый свет, реальная скорость прохода. Это ожидающая человеческая camera/UI-приёмка T07 на code 14.
- Не выполнялись свежие runtime проверки API 28/37, landscape, gesture/predictive Back, IME/fontScale/TalkBack, OS process kill. Recreation не равно уничтожению процесса ОС. Замечание про доступность ошибки под диалогом установлено по modal wiring, без нового TalkBack-прогона.
- Два fake-устройства не разделяют сервер: simultaneous revoke/check-in между устройствами и transport/idempotency настоящего API не подтверждены и не требуются как новая реализация в этом коммите.
- Manifest-находка относится к фильтрации при распространении через Play; публикации приложения не было, требование немедленно публиковать/строить production release не вводится. Ручной sideload smoke может продолжаться.

**Дальше:** отдельно разрешённые исправления двух P2 и их адресные регрессии; человеческая camera/UI-приёмка T07 по docs/24. Следующая функциональная задача по плану — T08 dashboard/явка, полная H3 после неё; H3/H4 этим отчётом не закрываются. T05/H2 и T06 остаются принятыми, их статусы ревью не меняет.

Предыдущие записи CODE_REVIEW.md сохранены побайтно. Исходники приложения/тестов, конфигурация, зависимости, решения, статусы и пользовательские изменения вне отчёта не менялись; защищённые файлы проверены по SHA-256 до запуска проверок и после записи. Игнорируемые дополнительные артефакты — build log, baseline, classpath/helper и его вывод в .gradle.
---

# Ревью — 1 ноября 2026 — 6de02da (revoke error в Dialog и optional camera)

## Область и вывод

Проверен HEAD `6de02da329afdae695d5c214939f81b54fd49e1a`, `fix: show revoke errors in dialog and relax camera features`, относительно непосредственного родителя `48c09b9`. В diff 14 файлов, 148 добавленных / 29 удалённых строк. Это адресное ревью исправлений двух P2 предыдущей записи, их влияния на общий ConfirmDialog и сопутствующей документации; не повторный полный аудит всего приложения или всей T07 foundation.

Применён `$android-code-review`. Прочитаны AGENTS.md, README, текущие решения 08 и T07 в плане 06, маршрут 00, относящиеся инженерные правила 14 и ответы B01–B08, docs/25 и актуальная lifecycle-карта T07. Проверены production/test diff, вызывающие места общего ConfirmDialog в Tickets и demo gallery, Volunteers VM/Route/Screen, регрессии grant/revoke/denied/hidden/late-finally. Значительный предсуществующий пакет незакоммиченных документов, API, prompts и daemon criteria учтён как рабочий контекст и сохранён; он не приписывается этому коммиту.

**Новых подтверждённых P0, P1, P2 или P3 в проверенном diff не обнаружено. Обе P2 ревью `48c09b9` исправлены.** Этот вывод ограничен указанным изменением и доступными доказательствами; весь проект не объявляется безошибочным. Предыдущая запись с находками сохранена как история исходного состояния.

## Проверка прежних находок

### P2: ошибка отзыва под модальным окном — исправлено

- `app/src/main/kotlin/ru/eventpass/app/feature/volunteers/VolunteersViewModel.kt:12–14,34–52`: Network/Timeout/Storage результата revoke теперь принадлежат отдельному `revokeError`. Выбранный Volunteer остаётся, busy снимается в прежнем guarded finally; общая ошибка списка/grant не используется как единственное место результата команды.
- `app/src/main/kotlin/ru/eventpass/app/feature/volunteers/VolunteersScreen.kt:32–38`: ошибка передаётся в `ConfirmDialog.errorText`, confirm меняется на «Повторить» и вызывает `vm.revoke`, то есть ту же команду по сохранённому userId. Это явный повтор, а не автоматический retry и не refresh списка. Fallback `state.error` также отображается внутри открытого окна; фоновые error/retry скрыты на время confirmation.
- `core/designsystem/src/main/kotlin/ru/eventpass/core/designsystem/EventPassStates.kt:46–64`: InlineError размещён в text-content самого AlertDialog. Он использует существующий polite live region. Фактический TalkBack-прогон не проводился; наличие семантики не приравнивается к такой приёмке.
- Success очищает confirmation/error; dismiss, новое подтверждение и stop очищают modal error. Во время busy невозможно закрыть окно или отправить вторую команду через прежние guards. Denied по-прежнему очищает private state/confirmation, а Route возвращает MyEvents; generation/ensureActive/защита позднего finally сохранены.

Свежие три JVM-регрессии проверяют Network/Timeout/Storage, сохранение того же Volunteer и grant, раздельность list/modal errors, очистку при dismiss/reopen и успешный повтор. Они включены в фактически выполненные 49 app unit tests. Новый device-тест в исходнике отдельно проверяет непосредственный retry без dismiss: error и кнопка retry обязаны иметь `isDialog` ancestor, сообщение видно, grant после failure существует и удаляется после retry. Его сохранённый успешный запуск описан ниже; нового device-запуска ревьюера не было.

### P2: неявное требование задней камеры — исправлено

`app/src/main/AndroidManifest.xml:3–6`: CAMERA permission сохранено, `camera.any`, `camera` и `camera.autofocus` явно имеют required=false. Проверен именно упакованный output APK code 15, а не только исходный XML:

```text
uses-feature-not-required: name='android.hardware.camera'
uses-feature-not-required: name='android.hardware.camera.any'
uses-feature-not-required: name='android.hardware.camera.autofocus'
```

Required/uses-implied-feature для camera больше нет. Прежняя стандартная implied faketouch остаётся и к исправлению не относится. Результат соответствует [официальным правилам Android uses-feature](https://developer.android.com/guide/topics/manifest/uses-feature-element#permissions): явный optional feature отменяет соответствующее требование от permission. Загрузка в Play Console/реальная выдача магазина не проверялись, но причина прежнего фильтра устранена в feature table артефакта.

Поддержка front-camera preview этим не добавляется: CameraX по-прежнему выбирает заднюю камеру, а при её отсутствии предусмотрен manual fallback через существующий error path. Сценарий на физическом устройстве без задней камеры не запускался; такая поддержка камеры не требуется как новая функция этого исправления.

## Сценарии, архитектура и SOLID

Изменения сохраняют MVVM/DI границы: repository сообщает типизированный исход, VM владеет состоянием конкретной modal-команды, Route переводит его в ресурсы и callbacks, дизайн-система показывает переданный текст без знания domain/fake. Отдельное поле revokeError имеет конкретную ответственность и не создаёт второй механизм операций/эффектов.

Общий ConfirmDialog получил необязательный параметр в конце сигнатуры с default null. Все три вызывающих места проверены. Tickets сохраняет свой dismissLabel/loading/destructive, gallery не передаёт errorText. При null диалог показывает только прежнее описание внутри Column; busy блокирует dismissal и кнопки по прежним правилам. Независимая визуальная проверка размеров/длинного текста не проводилась. Новых подтверждённых нарушений контрактов или SOLID в таком расширении не выявлено.

Права по eventId, owner-only Volunteers, один общий Scanner, cancellation/session scopes, check-in outcomes, schema 4 и credential generation не менялись. Нового глобального User.role, guest cache, автоматической регистрации или логирования персональных данных/секретов нет. Неизменённые repository guards и вся камера не аудировались заново полностью. Будущий backend/API/дашборд не требуются для адресного исправления.

Текущие README/план/решения и docs/25 согласованы с исправленным поведением и code 15. Docs/04 отражает errorText в общем компоненте, lifecycle-карта описывает modal error и optional feature. Back/H2 и T06 остаются принятыми на code 13; ревью не переоткрывает их и не закрывает T07/H3/H4.

## Свежие независимые проверки

PowerShell, установленный JBR 21, существующий wrapper, offline:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
.\gradlew.bat :app:testDemoDebugUnitTest --rerun :app:assembleDemoDebug :app:assembleDemoDebugAndroidTest :app:lintDemoDebug :app:verifyDemoIsolation --offline --console=plain --max-workers=2
python scripts/verify-architecture-docs.py
git diff --check 6de02da^ 6de02da
& 'C:/Users/XataB/AppData/Local/Android/Sdk/build-tools/36.1.0/aapt.exe' dump badging app/build/outputs/apk/demo/debug/app-demo-debug.apk
```

- **BUILD SUCCESSFUL in 6s**, 178 actionable tasks: **3 executed / 175 up-to-date**. --rerun относится к app Test task, она действительно исполнена; это не clean/recompile всего проекта.
- XML app unit: **49 tests, 0 failures, 0 errors, 0 skipped**, включая 3 новые revoke-регрессии и прежние grant/denied/hidden/late-finally tests.
- App/test APK assemble — UP-TO-DATE. Сборочные задачи успешны, но свежая полная компиляция всех sources/DI не заявляется.
- Lint task успешна, анализ и отчёт инкрементальны: **0 errors / 4 warnings**. Новых baseline/исключений не добавлялось.
- verifyDemoIsolation фактически исполнена: only demoDebug enabled, fake absent from main implementation.
- Архитектурный валидатор: **OK**, 12 current pages, **216 local links**, 9 Mermaid blocks, 18 mobile IDs, S01–S14/named test references. Проверено текущее рабочее дерево с пользовательскими документами; rendering Mermaid и семантику Kotlin скрипт не доказывает.
- `git diff --check 6de02da^ 6de02da`: успешно, замечаний нет.
- Output APK и архивный `artifacts/apk/eventpass-0.7.3-t07-demo-code15.apk` совпадают по SHA-256: **`0171bcc65518d65c82ba7af98be1bdd50c80f087710f2fcd357d1120a89b859d`**. Размер **44 076 713 байт**, versionName **0.7.3-t07-demo**, versionCode **15**, package `ru.eventpass.android.demo`. Это соответствует README/docs/25.
- aapt подтверждает optional camera table, приведённую выше. Реальной публикации в магазине не выполнялось.

Свежий лог: `.gradle/review-6de02da-build.log`. Команды запускались с разрешённым повышением из-за известного сбоя обычного sandbox helper; глобальные настройки не менялись. Domain/fake/platform unit suites не запускались заново: их source/contracts не изменены. Прежние 138 JVM code 14 не считаются новым полным прогоном code 15. Дополнительные helper-тесты сверх существующих регрессий для этого небольшого исправления не создавались.

## Сохранённые device-доказательства и пределы

Прочитан `.gradle/t07-fix-evidence/device.log`: **OK (4 tests), 48.404s**, INSTRUMENTATION_CODE=-1 при успешном итоговом OK. Это сохранённый прогон разработки на code 15, **не новый инструментальный запуск ревьюера**. Состав сверён с исходниками/командой docs/25:

1. `CheckInUiIntegrationTest#revokeErrorsAreVisibleInDialogAndRetryRevokesTheSameVolunteer`: три fault outcomes, сообщение/retry внутри Dialog, сохранённый grant после ошибки и успешный повтор.
2. `CheckInUiIntegrationTest#ownerGrantsExistingAccountAndConfirmsRevokeWithoutLosingOwnTicket`: прежний grant/revoke/owner scan.
3. `RegistrationTicketsTest#authenticatedSubmitSelectsTicketAndCancellationMovesItToHistory`: отмена личного билета через прежний ConfirmDialog.
4. `ProductBackNavigationTest#dialogAndEventBackReturnToCardBeforeReturningToList`: порядок закрытия dialog/fullscreen/Event и возврата к карточке/списку.

По отчёту разработки использован отдельный API 33 AVD, portrait/three-button. Устройства, APK, permissions и данные ревьюер не менял. Физическая камера/фокус/QR с бумаги, API 28/37 runtime, landscape, gesture/predictive Back, OS process death, полная IME/fontScale/TalkBack/Figma-сверка в этом ревью не проверялись. Живая Figma для адресного исправления не открывалась. Сохранённый device-набор не выдаётся за полный повтор всех T07 сценариев или ручную приёмку.

## Вопросы и следующий шаг

Блокирующих вопросов и дополнительных подтверждённых дефектов по указанному diff нет. Две предыдущие P2 закрыты техническими доказательствами в этой записи; исторические записи отчёта не переписывались.

Остаётся человеческая camera/UI-приёмка T07 на актуальном code 15 по docs/24–25. Она включает физический QR, permission/manual, lifecycle и штатный grant/revoke/retry. Следующая функциональная задача по плану — T08 dashboard/явка; полная H3 после неё, H4 позже. Статусы задач/решения/приёмки ревьюером не менялись.

Предыдущие байты CODE_REVIEW.md сохранены. Исходники приложения/тестов, конфигурация, зависимости и все пользовательские файлы вне отчёта не менялись; неизменность проверена по SHA-256 до проверок и после добавления отчёта. Дополнительные игнорируемые артефакты — build log и baseline хешей в .gradle.
---

# Ревью — 2 ноября 2026 — 3426bdb (owner dashboard и общая явка)

## Область и состояние

Проверен HEAD `3426bdb98bfb0600c708a3b96e47b980ac378b02`, `feat: add owner dashboard and shared attendance`, относительно непосредственного родителя. В diff 24 файла, 879 добавленных / 15 удалённых строк. Область: AttendanceRepository/domain projections, fake dashboard/search/pages/owner cancellation, общий cancel transition, volunteer counters, Dashboard VM/Screen/Route/navigation/DI, тесты и относящиеся документы. Предыдущая T07 camera pipeline полностью заново не аудировалась.

Применён `$android-code-review`. Прочитаны AGENTS.md, README, T08 в плане 06, актуальные решения 08, маршрут 00, инженерные правила 14, ответы B01–B08, docs/26 и t08-attendance. Проверены изменённые исходники, связанные EventAccessPolicy, self-cancel/check-in/store guards и вызывающие места. Предсуществующий незакоммиченный пакет архитектуры/API/prompts/config сохранён; текущие документы учтены как рабочая спецификация, но их переработка целиком не приписана этому коммиту.

T08 выполнена как независимая кодовая часть по разрешённому в плане объёму. T07 camera/UI и общая H3/H4 ожидают человека; Back/H2 и T06 уже приняты. Ревью не меняет эти статусы.

## Подтверждённая находка

### [P2] Запоздавший refresh откатывает dashboard после более нового observer snapshot

**Место:** `app/src/main/kotlin/ru/eventpass/app/feature/dashboard/DashboardViewModel.kt:80–82`, прежде всего безусловный по отношению к ревизии `accept(result)` в строке 81. Связанные места: `accept`/revision replacement (строки 37–44), проверка page revision и подавление повторного refresh (строки 60–82).

**Условие и сценарий:** refresh получает snapshot ревизии A, но его suspend-ответ ещё не доставлен VM. В это время store изменяется — например, проходит owner cancellation во время обновления или поступает check-in — и observer уже доставляет ревизию B с новыми totals. Затем завершается старый refresh A. Все действия относятся к тому же owner/entry; `generation == owner` остаётся true. Отдельной защиты от обновившегося observer snapshot нет, и A заменяет B.

Такое изменение store во время чтения не запрещено: кнопка refresh не блокирует owner cancellation. Fake refresh захватывает результат под Mutex на IO, observer строит проекцию на Default, а доставка ответа и наблюдения в VM не имеет общего порядка. Reproduction управляет доставкой через существующий порт и не требует реализации будущего backend.

**Последствие:** UI может уменьшить уже показанную явку или вернуть старые totals после успешной отмены; свежая серверная/store мутация при этом не откатывается. Вызванная `accept(A)` перезагрузка списка получает страницу B, видит mismatch и вызывает refresh, пока исходный refresh ещё отмечен refreshing=true. Этот вызов подавляется. После finally UI остаётся на старой ревизии без loading/error; для восстановления требуется новая мутация/явное обновление/повторный вход. Инварианты request/owner для страниц защищают append, но не свежесть самого dashboard.

**Доказательство:** `.gradle/review-3426bdb/ReviewRefreshRace.java` запускает настоящий DashboardViewModel с proxy существующего AttendanceRepository, MutableStateFlow и контролируемой suspend-continuation refresh. Query установлен в `no-match`: пустая страница поиска корректна и не должна менять независимые totals. До завершения старого refresh observer сообщает checkedIn=1; после него:

```text
AFTER_OBSERVER revision=after-checkin checked=1
AFTER_LATE_REFRESH revision=before-checkin checked=0 refreshing=false paging=false pageError=null
```

javac/java завершились успешно, воспроизведение содержит assertion отката. Это независимое JVM-доказательство state race, не Android screenshot, не новый device-run и не утверждение об отмене уже выполненного check-in. Исходники приложения/тестов не изменялись. Существующие четыре DashboardViewModelTest проверяют late query/finally, owner replacement, denied/error и sequential revision replacement; конкуренцию старого refresh с новым observer они не покрывают.

**Направление исправления:** сделать observer единственным источником принимаемого snapshot, либо инвалидировать ожидающий результат refresh при принятии нового observer snapshot и проверять это поколение перед accept. Не пытаться сравнивать SHA-256 revision как возрастающее число или использовать фиксированный demo Clock для определения новизны. Добавить адресную регрессию: suspend refresh A → observer B → ответ A; totals/rows должны остаться B, а повтор чтения не должен теряться за refreshing guard. Исправление в рамках ревью не выполнялось.

Других подтверждённых P0–P3 в проверенном diff не сформировано. Это не заключение об отсутствии ошибок во всём проекте.

## Соответствие сценариям, архитектуре и Android

| Область | Что проверено |
|---|---|
| Owner access | Attendance guard использует current verified/nonexpired actor и EventAccessPolicy.canViewAttendees на observe/page/refresh/cancel. Owner другого E, volunteer, participant, guest не получают список. Вложенный registrationId ограничен тем же eventId. Actor не задаётся UI. |
| Один store | Attendance/check-in/tickets/grants используют общий FakeProductState/Mutex/IO/storage/Clock. Нет второго счётчика явки или самостоятельного клиентского допуска. CheckedInCount volunteer вычисляется из первых CheckInRecord конкретного checker/E, repeat/revoke/regrant не меняют историю. |
| Totals | registered=ACTIVE+USED, checkedIn=USED, notCheckedIn=ACTIVE; Cancelled исключены из totals, остаются строками истории. Unlimited=null, bounded remaining неотрицателен, нулевой percent=0, умножение через Long. StartsAt не делает событие finished; lifecycle/end проверяются отдельно. |
| Проекция/PII | Attendee не содержит QR/manual, phone или answers; toString редактирует контакт. Query/route не содержат выданный допуск; SavedStateHandle сохраняет только безопасный query. PII списка не persist как отдельный client cache. |
| Поиск и pages | trim/case-insensitive по имени/номеру; стабильная сортировка и размер ≤20. Cursor ограничен event/revision/query/offset, revision — hash length-prefixed проекции. Query не меняет totals. Пагинация на неизменённом snapshot и guards старого query/owner проверены; refresh race описана отдельно. |
| Отмена | ACTIVE→CANCELLED, Used запрещён, повтор Cancelled идемпотентен. Self и owner используют общий status transition при разных guards. Check-in/cancel сериализованы Mutex, storage failure не публикует новую отмену; registration/answers/постоянная фиксация схемы сохраняются. |
| Modal errors | Network/Timeout/Storage owner cancellation показаны в ConfirmDialog с явным повтором того же registrationId. Busy ставится до launch, повтор/dismiss заблокированы; generation/ensureActive и guarded finally не меняют следующего owner. |
| MVVM/DI/SOLID | Domain port чистый, demo DI выдаёт общий environment attendance. VM координирует repository/state, Screen получает state/callbacks, platform/fake/DTO не протекают в Compose. Общая cancellation-функция разделяет переход статуса и resource authorization. Новых подтверждённых нарушений этих границ не выявлено. |
| Lifecycle/navigation | DashboardKey(eventId,UUID) в исходном private табе; Scanner/Volunteers открываются поверх dashboard. Visible/current+RESUMED управляют jobs, hidden/background/denied/stop очищают данные/confirmation; query сохраняется. Logout уничтожает private owners. LazyColumn использует стабильный ticket key. |
| Границы продукта | Один аккаунт без User.role; guest receipt/auth семантика не изменены. Schema 4 прежняя, вычисляемые totals/rows не требуют миграции. MAX/экспорт/новый API/remote/polling/offline admission не внедрены и не требуются этим ревью. |

Документы описывают фактическую схему/owners и честно называют локальный consistency protocol. Пункт о предотвращении stale page не устраняет подтверждённую гонку самого refresh. Новый backend cursor не объявляется согласованным. Figma live/визуальное соответствие O04 в ревью не проверялись: учтены mobile-frame route и честное ограничение дизайн-сверки в docs/26.

## Свежие проверки ревьюера

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
.\gradlew.bat :core:domain:test --rerun :data:fake:test --rerun :app:testDemoDebugUnitTest --rerun :core:platform:testDebugUnitTest --rerun :app:assembleDemoDebug :app:assembleDemoDebugAndroidTest :app:lintDemoDebug :app:verifyDemoIsolation --offline --console=plain --max-workers=2
python scripts/verify-architecture-docs.py
git diff --check 3426bdb^ 3426bdb
```

- Gradle **BUILD SUCCESSFUL in 10s**, 187 actionable tasks: **6 executed / 181 up-to-date**. Четыре Test task реально исполнены посредством --rerun; остальные задачи инкрементальны, clean не делался.
- XML: **155 JVM tests, 0 failures/errors/skipped**: domain 20, fake 76, app 53, platform 6. Новые 10 AttendanceTest и 4 DashboardViewModelTest включены в этот свежий прогон. Проход suites не закрывает непокрытую refresh/observer гонку.
- App/test APK assemble успешны, инкрементальны; полная свежая перекомпиляция всех DI/sources не заявляется. Lint успешна, анализ/отчёт инкрементальны: **0 errors / 4 warnings**. Новых baseline/подавлений не добавлялось.
- verifyDemoIsolation исполнена: только demoDebug включён, fake отсутствует в main implementation.
- Архитектурный verifier **OK**: 13 current pages, 236 local links, 9 Mermaid blocks, 18 mobile IDs, S01–S14/named test references. Проверка относится к рабочему дереву с предсуществующими документами; rendering Mermaid/Kotlin-семантика ею не проверяются.
- `git diff --check` коммита: успешно, замечаний нет.
- Выходной APK и архив `artifacts/apk/eventpass-0.8.0-t08-demo-code16.apk` совпадают по SHA-256 **`800ae88e4282696985f6c12ccbcfd55022453221f211b54ffa87241ba408ebf2`**, размер **44 366 256 байт**. Version **0.8.0-t08-demo, code 16** согласно build diff/README/docs/26. Идентичность артефакта не означает приёмку человеком.

Build log: `.gradle/review-3426bdb-build.log`. JBR/wrapper/зависимости проекта сохранены, offline. PowerShell запускался с разрешённым повышением из-за известной ошибки обычного sandbox helper; глобальные настройки не менялись.

Для независимого воспроизведения выполнены:

```powershell
.\gradlew.bat :app:reviewDashboardClasspath -I .gradle/review-3426bdb/classpath.init.gradle --offline --console=plain --max-workers=2
$root = (Resolve-Path '.gradle/review-3426bdb').Path
$cp = Get-Content "$root/classpath.txt" -Raw
$j = 'C:/Program Files/Android/Android Studio1/jbr/bin'
& "$j/javac.exe" -encoding UTF-8 -cp $cp -d $root "$root/ReviewRefreshRace.java"
& "$j/java.exe" -cp ($root+';'+$cp) ReviewRefreshRace
```

Classpath task: BUILD SUCCESSFUL in 3s, 74 tasks: 1 executed / 73 up-to-date; зависимая app unit task здесь UP-TO-DATE, не считается ещё одним прогоном. javac/java успешны; compiler note об unchecked proxy continuation относится только к диагностическому Java helper. Исходник/вывод — `.gradle/review-3426bdb/ReviewRefreshRace.java` и `refresh-race.log`; helper не входит в source tree приложения или число 155 тестов.

## Сохранённые device-доказательства и ограничения

Нового device-run в ревью **не было**. Прочитан `.gradle/t08-evidence/device-final.log`: **OK (3 tests), 34.205s**, INSTRUMENTATION_CODE=-1 при успешном OK. Это сохранённый прогон разработки. Исходники DashboardIntegrationTest и docs/26 подтверждают состав: dashboard → существующий scanner/manual → updated attendance → Volunteers → Back; query/recreation/modal Network+Timeout+Storage/retry/cancel/refresh error/hidden/logout; forged volunteer dashboard без private rows.

Первый `.gradle/t08-evidence/device.log` завершается **3 tests / 1 failure**: по отчёту неоднозначный selector заголовка/кнопки уточнён hasClickAction без изменения приложения. Этот неуспех сохранён и не выдаётся за успешный прогон. Итоговый журнал — отдельное подтверждение 3/3. По development-отчёту использован отдельный API 33 AVD, portrait/three-button; ревьюер не устанавливал APK, не сбрасывал данные и не менял permissions пользовательского устройства/AVD.

Свежая физическая камера, API 28/37 runtime, landscape, gesture/predictive Back, OS process death, полная IME/fontScale/TalkBack/Figma-сверка отсутствуют. Activity recreation не доказывает OS process death. Два fake-устройства не разделяют сервер; одновременные действия двух устройств/transport API не проверены и не объявляются обязательной реализацией этого demo-коммита. Подтверждённая гонка относится к VM/портам одного entry, а не требует двух устройств.

## Вопросы и дальнейший шаг

Блокирующих продуктовых вопросов по текущему объёму нет. Достаточность O04 визуального решения и физическая camera/UI T07 остаются за человеком; серверный API по-прежнему на согласовании.

Дальнейший шаг — отдельно разрешённое исправление P2 с адресной refresh/observer regression, затем ожидающая T07/T08 camera/UI/H3-приёмка на конкретной сборке. Следующая функциональная задача по плану — T09; backend требуется для T10. Ревью не закрывает H3/H4 и не меняет статусы.

Старые записи CODE_REVIEW.md сохранены побайтно. Исходники приложения/тестов, зависимости, конфигурация, решения и пользовательские изменения вне отчёта не менялись; контролируются SHA-256 до проверок и после записи. Дополнительные игнорируемые артефакты — build log, baseline, classpath и диагностический helper/вывод в .gradle.
---

# Ревью — 2 ноября 2026 — aaca508 (приоритет observer перед поздним refresh)

## Область и вывод

Проверен HEAD `aaca50816ced4ce8e8f28fec8ac85ea34a8ab605`, `fix: keep newer observer dashboard over delayed refresh`, относительно непосредственного родителя `3426bdb`. В diff 8 файлов, 165 добавленных / 18 удалённых строк. Область — координация observer/refresh/follow-up в DashboardViewModel, пять новых JVM-регрессий, version code/name и относящаяся документация. Repository/store/UI/navigation/schema не изменялись и полностью заново не аудировались.

Применён `$android-code-review`. Прочитаны корневые инструкции и локальный skill, README, текущие решения 08/T08 в плане 06, маршрут 00, относящиеся инженерные правила 14 и B01–B08, docs/27 и изменения t08-attendance. Проверены весь DashboardViewModel, связанные page/owner/command guards и новые tests. Предсуществующая незакоммиченная переработка документов/API/prompts/daemon criteria сохранена и не приписывается коммиту.

**Новых подтверждённых P0, P1, P2 или P3 в проверенном diff не обнаружено. P2 из ревью `3426bdb` исправлена.** Это вывод по указанному исправлению и выполненным проверкам, не утверждение о безошибочности всего приложения. Старое описание дефекта остаётся в предыдущей записи как история.

## Проверка исправления P2

Прежний сценарий: начат refresh A, observer уже показал B с checkedIn=1, затем ответ A откатывал checkedIn к 0. Owner generation не менялся, поэтому прежняя проверка не запрещала accept(A).

В `app/src/main/kotlin/ru/eventpass/app/feature/dashboard/DashboardViewModel.kt` теперь:

- При каждом принятом observer результате увеличивается `observation`, включая Ready и Failed. Refresh запоминает owner и observation до suspend. После ответа `ensureActive` и совпадение обоих поколений обязательны для accept. Revision остаётся opaque hash, timestamp/Clock не используется для определения порядка.
- Запрошенное во время refresh повторное чтение объединяется флагом `refreshPending`. После завершения текущего чтения снимается refreshing и, если owner/корутина актуальны, запускается follow-up. Page mismatch больше не теряется за busy guard. Это повтор чтения, а не автоматическая повторная отмена/check-in.
- Stop увеличивает owner, отменяет jobs, очищает observation/pending и private UiState. Старый finally не снимает busy нового owner и не переигрывает старую очередь.
- Новый refresh job создаётся LAZY, ссылка записывается до запуска. При немедленном ответе follow-up не теряется из-за позднего присваивания завершившегося первого job; stop сохраняет возможность отменить актуальное чтение.

Проверена совместимость с прежними query/request/revision guards: старый page result/finally не меняет новую выдачу, totals не зависят от поиска, подтверждение отмены и его owner generation не затронуты. Приём observer error также инвалидирует более ранний refresh result, поэтому более новая ошибка не заменяется старой.

## Независимое воспроизведение исходного сценария

Сохранённый helper прошлого ревью скопирован в `.gradle/review-aaca508/ReviewRefreshRace.java`; изменено только ожидание assertion на сохранение B. Предыдущий helper не переписывался. Выполнено на настоящем текущем DashboardViewModel с контролируемой suspend-continuation существующего AttendanceRepository и MutableStateFlow. Query `no-match` делает пустую страницу корректной, независимые totals остаются проверяемыми.

```text
AFTER_OBSERVER revision=after-checkin checked=1
AFTER_LATE_REFRESH revision=after-checkin checked=1 refreshing=false paging=false pageError=null
```

javac/java завершились успешно, assertion сохранения нового snapshot прошёл. Для сравнения, в предыдущей записи тот же порядок доставки давал `before-checkin checked=0`. Новое доказательство подтверждает исправление state race; это не Android screenshot/device run и не проверка настоящей сети.

Использован сохранённый runtime classpath из `.gradle/review-3426bdb/classpath.txt`, указывающий на те же актуальные Gradle outputs; зависимости коммит не меняет, app Test task выполнена перед helper. Новый helper скомпилирован заново и использует текущие compiled classes, не прежний сохранённый APK.

```powershell
$root = (Resolve-Path '.gradle/review-aaca508').Path
$cp = Get-Content '.gradle/review-3426bdb/classpath.txt' -Raw
$j = 'C:/Program Files/Android/Android Studio1/jbr/bin'
& "$j/javac.exe" -encoding UTF-8 -cp $cp -d $root "$root/ReviewRefreshRace.java"
& "$j/java.exe" -cp ($root+';'+$cp) ReviewRefreshRace
```

Вывод — `.gradle/review-aaca508/refresh-fixed.log`. Note об unchecked conversion относится только к proxy continuation диагностического Java helper; приложение/его тесты не редактировались, helper не включён в число app tests.

## Сценарии, архитектура, SOLID и Android

Пять новых DashboardViewModelTest покрывают:

1. Поздний refresh A после observer B при одинаковом timestamp и пустом поиске: totals/revision B сохраняются, read/page errors и loading flags не зависают.
2. Observer TIMEOUT и последующий старый refresh NETWORK: остаётся более новая ошибка, доступный предыдущий snapshot сохраняется.
3. Page C требует refresh во время A, observer уже B: после A выполняется одно follow-up чтение и получается согласованная пара dashboard/page C.
4. Старый owner с pending/queued refresh после stop/new owner: старый ответ/finally не снимает новый busy и не переносит очередь.
5. Немедленный ответ запускает suspend follow-up: stop отменяет именно актуальный follow-up job и очищает state.

Все девять DashboardViewModelTest включены в свежий app прогон. Прежние cancel/query/denied tests сохранены. Новых тестов в исходники ревьюер не добавлял: использованы существующие регрессии коммита и отдельная игнорируемая диагностика.

MVVM/DI границы не расширены: согласование двух источников чтения остаётся у entry-owned VM; UI получает UiState/callbacks, repository/fake/platform в Compose не протекли. Два счётчика и pending-флаг имеют конкретную ответственность и не вводят отдельный event framework. Проверки CancellationException/owner/request сохранены; новая очередь не запускает мутации повторно. Lifecycle foreground/hidden/denied cleanup по-прежнему идёт через stop, состояние очереди не persist.

Права по eventId, общий owner dashboard/Scanner/Volunteers, один аккаунт без User.role, guest receipt без постоянного client cache, server-side check-in/capacity semantics не менялись. Schema 4, ports, credential generation и API-предложение прежние. Новые abstractions/backend behavior/функции T09 не требуются для исправления. Отсутствие изменений этих подсистем не означает их полный повторный аудит.

README/06/08/docs/27 и t08-attendance согласованы с code 17 и исправленной координацией. T08 остаётся READY_FOR_HUMAN, T07 camera/UI и общая H3/H4 за человеком; Back/H2/T06 сохраняют прежнюю приёмку. Ревью не меняет статусы.

## Свежие проверки ревьюера

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
.\gradlew.bat :app:testDemoDebugUnitTest --rerun :app:assembleDemoDebug :app:assembleDemoDebugAndroidTest :app:lintDemoDebug :app:verifyDemoIsolation --offline --console=plain --max-workers=2
python scripts/verify-architecture-docs.py
git diff --check aaca508^ aaca508
```

- **BUILD SUCCESSFUL in 6s**, 178 actionable tasks: **3 executed / 175 up-to-date**. App Test task фактически исполнена через --rerun; это не clean/recompile всех задач.
- XML app unit: **58 tests, 0 failures, 0 errors, 0 skipped**. Включены все девять DashboardViewModelTest и прежние auth/submit/editor/volunteer regressions.
- App/test APK assemble успешны, инкрементальны. Полная свежая компиляция всех DI/sources не заявляется. Lint task успешна, анализ/отчёт инкрементальны: **0 errors / 4 warnings**; новых baseline/подавлений нет.
- verifyDemoIsolation исполнена: only demoDebug enabled, fake absent from main implementation.
- Архитектурный verifier **OK**: 13 current pages, 238 local links, 9 Mermaid blocks, 18 mobile IDs, S01–S14/named test references. Это структурная проверка текущего рабочего дерева, не rendering Mermaid/семантика Kotlin.
- `git diff --check` указанного коммита: успешно.
- Output APK и архивный `artifacts/apk/eventpass-0.8.1-t08-demo-code17.apk` совпадают по SHA-256 **`d42d9c95e2e27dbd1bfe75bf399c2274de00f77c50d528edf961129ef6028f3e`**, размер **44 367 304 байт**, **0.8.1-t08-demo, code 17**. Значения соответствуют build diff/README/docs/27. Это не ручная приёмка.

Лог — `.gradle/review-aaca508-build.log`. Применены существующие JBR 21/wrapper/dependencies, offline; глобальные настройки не менялись. PowerShell выполнен с разрешённым повышением из-за известного сбоя обычного sandbox helper. Domain/fake/platform unit suites не запускались заново: их source/contracts в коммите не менялись; прежние 155 JVM code 16 не приписываются текущему прогону code 17.

## Сохранённые RED/device-доказательства и ограничения

Прочитан сохранённый XML `.gradle/t08-refresh-evidence/red-unit-results`: **8 tests / 2 failures**. По tests/docs/27 это исходные rollback и error-order regressions на старом коде; пятый immediate-follow-up тест добавлен позже, поэтому не объявляется упавшим в том RED. Это сохранённое доказательство разработки, не новый запуск старой версии ревьюером.

Прочитан `.gradle/t08-refresh-evidence/device-final.log`: **OK (3 tests), 33.175s**, INSTRUMENTATION_CODE=-1 при итоговом OK. Это сохранённый финальный прогон разработки на code 17, **не новый device-run ревьюера**. По docs/27 повторён существующий DashboardIntegrationTest: dashboard→scanner/manual→Back/updated attendance→Volunteers→Back; query/recreation/modal fault/cancel/retry/refresh error/hidden/logout; forged volunteer route без PII. Точный порядок refresh race доказывают JVM-проверки, integration smoke его не подменяет.

По development-отчёту использован отдельный API 33 AVD, portrait/three-button. Промежуточный 3/3 на 37.334s не выдаётся за окончательный результат; выше указан именно final-device log. Ревьюер не устанавливал APK, не менял устройства/permissions и не сбрасывал данные.

Физическая камера/бумажный QR/фокус/слабый свет, API 28/37 runtime, landscape/gesture/predictive Back, полная IME/fontScale/TalkBack/Figma-сверка и новый OS process kill не проводились. Recreation не доказывает process death. Живая Figma для узкого изменения VM не открывалась. Нового backend/two-device proof нет; оно не требуется как реализация этого исправления.

## Вопросы и следующий шаг

Блокирующих вопросов и дополнительных подтверждённых source findings по данному diff нет. Прежняя P2 закрыта техническими доказательствами этой записи; история отчёта не переписывалась.

Остаётся ручная T07 camera/UI и общая T08/H3-приёмка по docs/24/26/27 на конкретной сборке/устройстве. Следующая функциональная задача по плану — T09, backend требуется для T10. Ревью не закрывает H3/H4 и не отменяет принятую Back/H2/T06.

Предыдущие записи CODE_REVIEW.md сохранены побайтно. Исходники приложения/тестов, зависимости, конфигурация, решения, статусы и пользовательские изменения вне отчёта не менялись; неизменность проверена по SHA-256 до проверок и после записи. Игнорируемые дополнительные артефакты — build log, baseline и helper/вывод в .gradle.
---

# Ревью — 2 ноября 2026 — 8d01048 (T09 профиль, документы и Android CLI)

## Область

Проверен HEAD `8d010487f47f53b54bc1e535815857912fe1372d`, `feat: add independent T09 profile and document flows`, относительно непосредственного родителя. Diff: 47 файлов, 1471 добавленная / 69 удалённых строк. Область: Profile/Document domain ports, fake/store/schema 5 и migrations, Profile/Documents VM и UI/навигация, PDF/XLSX/ContentResolver adapters, resend, tests и текущая документация. Это ревью нового независимого среза T09, а не заявление о полной приёмке MVP.

Применены `$android-code-review` и по дополнительному запросу пользователя `android-cli:android-cli`. Прочитаны AGENTS.md, README, текущие решения 08/T09 в плане 06, карта 00, относящиеся правила 14 и B01–B08, docs/28 и architecture/t09-profile-files. Проверены изменённые реализации и вызывающие места Tickets/Dashboard/Event form/private navigation, migration/writers и новые suites. Предсуществующая переработка документов/API/prompts/daemon criteria сохранена и не присваивается этому коммиту.

## Подтверждённая находка

### [P2] Пересоздание Activity во время SAF теряет ожидающий экспорт без сообщения

**Место:** `app/src/main/kotlin/ru/eventpass/app/feature/documents/DocumentActions.kt:27`. Связанный код: rememberSaveable launch ID/ActivityResult callback в строках 23–25, `DocumentsViewModel.clear` и `destination`.

**Сценарий:** пользователь нажимает экспорт PDF/XLSX, открыт ACTION_CREATE_DOCUMENT; Android пересоздаёт Activity/композицию при конфигурационном изменении. У того же сохранённого nav entry ViewModel должна продолжать владеть pending request. Но старый DisposableEffect вызывает `vm.clear()` на dispose, а новая композиция снова вызывает clear в теле эффекта. Это удаляет `picker`, request/session и отменяет job независимо от сохранения nav entry/личности/ресурса. Launch ID при этом восстанавливается через rememberSaveable. После выбора файла ActivityResult приносит URI и прежний ID; `destination` не находит matching picker и молча возвращается.

**Последствие:** выбранный PDF/XLSX не записывается; provider может уже создать пустой файл. UI не показывает ни успех, ни ошибку, требуется заново начинать экспорт. Также пересоздание после начала записи отменяет текущий job и теряет его состояние. Это не защита от новой личности или process death: проблема возникает при обычном восстановлении той же entry в том же процессе. Не переоткрывать picker автоматически — правильно, но не означает, что его уже ожидающий результат нужно потерять.

**Доказательство:** источник cleanup очевидно привязан к композиции, а не только к смене resource/session/реальному уходу. `.gradle/review-8d01048/ReviewPickerRecreation.java` запускает настоящий текущий DocumentsViewModel с аутентифицированным fake, создаёт pending export/launch ID, затем выполняет два clear, соответствующие old dispose/new DisposableEffect, и доставляет успешный content URI с сохранённым ID:

```text
after same-entry composition recreation: prepareCalls=0 busy=false saved=false error=null
```

javac/java и assertion прошли. Это контролируемое JVM-доказательство потери pending в VM плюс проверка Compose wiring, **не новый Android device-тест с поворотом**. Existing DocumentsViewModelTest проверяет cancel/logout/late picker, но не сохранение pending при configuration recreation. Profile recreation в instrumented smoke не закрывает этот сценарий.

**Направление исправления:** сохранять pending/job у ViewModel той же entry через configuration recreation; очищать при реальном hidden/resource/session change или уничтожении owner. Отдельно сохранить одноразовый launch marker/ActivityResult ID, чтобы picker не запускался повторно. Добавить адресную regression: launch SAF → recreate → успешный URI → реальная запись и итоговый статус, а также logout/новый ресурс → старый URI игнорируется. Исходники в ревью не исправлялись.

Других подтверждённых P0–P3 в прочитанном diff не сформировано; весь проект не объявляется безошибочным.

## Проверенные сценарии и архитектура

| Область | Проверенный результат и предел |
|---|---|
| Profile | Имя — AuthData.names, Account/session не переписываются; phone/org/preference отдельно. Unicode name/optional RU phone/организация валидируются, old registration/tickets/history не переписываются, новые формы получают contact. Dirty observer/save/Back/tab/logout guards и modal failures прочитаны. Background/hidden очищают несохранённую форму согласно текущему решению. |
| Schema 5 | V1–v4 читаются с defaults без reset; signup names, tickets/registrations/drafts/grants/check-ins остаются, все writers кодируют общий state. Corrupt/unsupported bytes не перезаписываются. После первой v5 записи downgrade на code 17 не поддержан и честно обозначен. |
| Доступ к PDF | Только свой current ACTIVE ticket по подтверждённому email/current session; owner/volunteer не получают чужой ticket PDF. Snapshot несёт stamp, validate проверяет identity/status/metadata. Guest/history download не добавлены. |
| XLSX | Owner-only полный snapshot E независимо от query/page, statuses/answers/question-option IDs/first record; нет QR/manual credential. Inline strings не превращаются в формулы, XML escaping/Boolean/empty/legacy history обработаны. Ограничение длины Excel cell явно проверяется, не маскируется ложным успехом. |
| Запись | Генерация во private temp на IO, authorization перед prepare/provider, после open, между chunks и после close; cancellation распространяется. Output закрывается через use, успех только после завершённой записи, temporary finally/startup cleanup. Уже записанные внешние байты не обещают отозвать: возможен partial file, что задокументировано. Configuration lifecycle дефект вынесен выше. |
| PDF | Native PdfDocument, выданные metadata/name/QR/manual, A4 wrapping/pages и QR block. Source/существующие Android tests проверены; новой независимой визуальной проверки PDF ревьюер не выполнял. |
| Resend | Прежний fake outbox, recipient Registration, не новая регистрация/место/QR; minute cooldown auth Clock/typed retryAt, без auto retry/реальной рассылки. Preference не выключает обязательные/явно запрошенные письма. |
| MVVM/SOLID/DI | UI/VM зависят от ports, fake в demo DI; platform не решает actor/check-in policy. Один общий store, без User.role/client guest cache. Инъекция DocumentFiles и application-scope cleanup предметно ограничены. Найденная проблема — неверная lifetime граница UI cleanup, не необходимость новых абстракций ради стиля. |
| Product scope | Q10 deletion не согласован, UI не выдаёт reset за удаление. T09 остаётся IN_PROGRESS с независимым READY_FOR_HUMAN срезом; T07/H3/H4 не приняты. Future API/export transport/camera proof не требуют реализации в этом ревью. |

Прежний auth test harness теперь использует latch, чтобы первая IO запись действительно приостанавливала return; production auth не менялся. Новые миграционные fixtures и suites не подменяют текущие snapshot readers упрощённым форматом.

## Android CLI: доступен и использован

Прочитан установленный skill `C:/Users/XataB/.codex/plugins/cache/android-cli-plugin/android-cli/1.0.16625370/skills/android-cli/SKILL.md`. Команда найдена по `C:\Users\XataB\AppData\AndroidCLI\android.exe`. Выполнены:

```powershell
android --version
android info
android docs --help
android docs search --help
android docs search "ACTION_CREATE_DOCUMENT write content URI"
android docs fetch kb://android/training/data-storage/shared/documents-files
android studio check
```

Версия реально установленного CLI **1.1.16717390** (не версия папки skill), SDK найден как `C:\Users\XataB\AppData\Local\Android\Sdk`. Docs search/fetch успешны: получена официальная статья **Access documents and other files from shared storage**. Полный fetch сохранён в `.gradle/review-8d01048-saf-docs.txt`; публичный источник — [Android SAF](https://developer.android.com/training/data-storage/shared/documents-files). Он подтверждает CreateDocument/content-provider flow, не успешность конкретного приложения. CLI полезен для следующих Android задач: docs, SDK/device inspection, UI layout/screenshots и управляемого запуска на явно выбранном AVD.

`android studio check` завершился с code 1 / **No running Studio instances found**. Поэтому analyze-file/find-usages/preview через Studio в этом ревью не заявляются доступными. Сам CLI и docs работают. Печатается JVM warning про неподдержанный UseAllWindowsProcessorGroups, он не помешал успешным командам. Установки/обновления CLI/SDK/init/global settings, управление устройствами и UI действия не выполнялись.

## Свежие проверки ревьюера

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
.\gradlew.bat :core:domain:test --rerun :data:fake:test --rerun :app:testDemoDebugUnitTest --rerun :core:platform:testDebugUnitTest --rerun :app:assembleDemoDebug :app:assembleDemoDebugAndroidTest :app:lintDemoDebug :app:verifyDemoIsolation --offline --console=plain --max-workers=2
python scripts/verify-architecture-docs.py
git diff --check 8d01048^ 8d01048
```

- **BUILD SUCCESSFUL in 1m 38s**, 187 actionable tasks: **25 executed / 162 up-to-date**. Все четыре Test task фактически исполнены через --rerun; compile/assemble/lint частично инкрементальны, clean не выполнялся.
- **176 JVM tests, 0 failures/errors/skipped**: domain 20, fake 84, app 64, platform 8. Source/new suites покрывают миграции/all writers/corruption/session/storage, профиль, guards, resend, picker cancellation/late identity и streams. Это не покрывает найденную same-entry recreation потерю.
- App/test APK build и demo isolation успешны. Lint **0 errors / 4 warnings**, без новых suppression/baseline. Build log `.gradle/review-8d01048-build.log`.
- Архитектурный verifier **OK**: 14 current pages, 270 local links, 9 Mermaid blocks, 18 mobile IDs, S01–S14/named test references. Это проверка рабочего дерева с пользовательскими документами, не rendering/семантика Kotlin.
- `git diff --check` коммита прошёл.

Для helper использован сохранённый classpath `.gradle/review-3426bdb/classpath.txt`, указывающий на актуальные compiled Gradle outputs; новые runtime зависимости не добавлены. Выполнены javac/java установленного JBR 21 с output в `.gradle/review-8d01048`, helper не входит в 176 app/project tests. Вывод `.gradle/review-8d01048/picker-recreation.log`. Он проверяет lifecycle-сброс state, не provider rendering.

PowerShell выполнен с разрешённым повышением из-за известного сбоя обычного sandbox helper. Источники и глобальная конфигурация среды ревьюером не изменялись.

## Различие APK после свежей сборки

Архивный `artifacts/apk/eventpass-0.9.0-t09-demo-code18.apk` сохранён: **44 789 394 байт**, SHA-256 **`a5c76100b03e50d9f62cdbfb38925766a144d62938452d81cbd79063e6afae8f`**, соответствует README/docs/28. Он не перезаписывался.

Свежая сборка обновила обычный output `app/build/outputs/apk/demo/debug/app-demo-debug.apk`: **45 052 934 байт**, SHA-256 **`eea864abba0aa0def3fe51d0c64e8e607dc3e1de4ca8022325399b4167fd8558`**. aapt подтверждает прежние package/version **ru.eventpass.android.demo / 0.9.0-t09-demo / code 18**. Это другой бинарный артефакт; device-доказательства архивного APK не переносятся автоматически на новый output.

Сравнение ZIP: набор entry одинаков, CRC отличается только у **classes20.dex**, его распакованный размер в обоих — 263496 байт. Причина различия DEX/упаковки точно не установлена. SHA-256 всех защищённых исходников/config/docs до/после проверок совпадает. Бинарная воспроизводимость debug APK в требованиях проекта не заявлена, поэтому отличие само по себе не оформлено как source defect; побайтовая эквивалентность не утверждается.

## Сохранённые device-доказательства и ограничения

Новых device-тестов ревьюер не запускал. Прочитаны финальные журналы `.gradle/t09-evidence/`:

- `device-t09-final.log`: **OK (3 tests), 26.034s**.
- `device-dashboard-final.log`: **OK (3 tests), 29.927s**.
- `device-back-final.log`: **OK (4 tests), 31.42s**.
- `restart-stage.log`: **OK (1 test), 5.476s**; `restart-verify.log`: **OK (1 test), 4.734s**.
- `os-restart-verify.log`: **OK (1 test), 4.628s**.

Итого сохранены **13 завершённых invocations**, включая restart stages, а не 13 независимых end-to-end сценариев. По docs/28 это точный архивный code 18, отдельный API 33 AVD, portrait/three-button; T09 smoke при fontScale 1.3, Back при 1.0. Profile/SAF/provider/PDF QR decode/restart подтверждаются этими тестами в их границах. Промежуточные failures provider/runtime/SAF/harness и auth helper timeout при fontScale 1.3 отражены разработкой и не выдаются за success. OS restart журнал трактуется вместе с описанным адресным am crash и PID-свидетельствами разработки; сам runner restart не доказывает LMK/task restoration.

Прочитаны исходники тестов и отчёт об independent sample validation pypdf/openpyxl/Poppler/ZXing. Эти проверки образцов и их визуальный рендер ревьюером заново не запускались. Отказ независимого provider, реальная физическая камера, API 28/37, landscape/gestures, полный TalkBack/IME/Figma, LMK/guest task restore и настоящая доставка/backend не проверены в этом ревью. Activity recreation профиля не покрывает успешный SAF после recreate.

## Дальнейший шаг

Отдельно разрешённое исправление P2 lifetime SAF с адресной configuration recreation regression. Для человеческой приёмки использовать явно идентифицированный артефакт, учитывая различие fresh output/архива. T09 остаётся IN_PROGRESS: Q10 требует продуктового решения, T07 camera/UI, общая H3 и H4 ожидаются; backend для T10 не согласован. Ревью не закрывает эти gate и не меняет статусы.

Прежние байты CODE_REVIEW.md сохранены. Исходники приложения/тестов, конфигурация, зависимости, решения и пользовательские файлы вне отчёта не менялись; проверено SHA-256 до/после. Build output обновлён штатной сборкой, архивный APK сохранён. Дополнительные игнорируемые артефакты — build/CLI logs, baseline и JVM helper в .gradle.
---

# Ревью — 3 ноября 2026 — c75ce56 (SAF при пересоздании Activity)

## Область и результат

Проверен HEAD `c75ce5620226932bab9b991dd2744d7ff6a95b1e` относительно `8d01048`. Коммит `fix: preserve pending SAF export across activity recreation` меняет 10 файлов. Ревью охватывает `DocumentActions`, `DocumentsViewModel`, регрессии SAF, метаданные APK и документацию T09. Учтены AGENTS.md, маршрут 00, README, план 06, решения 08, инженерные правила 14, B01–B08 и описание жизненного цикла T09. Существующие незакоммиченные документы учтены отдельно от diff.

**Прежняя P2 о потере ожидающего SAF-запроса исправлена. Новых подтверждённых P0–P3 в проверенном diff не выявлено.** Вывод ограничен жизненным циклом экспорта и связанными сценариями.

## Проверка исправления

- `DocumentActions.kt` передаёт из `LocalActivity` признак `isChangingConfigurations` в `unbind`. При таком отсоединении ViewModel сохраняет pending, признак запуска picker, захваченную сессию и текущую запись. Повторный `bind` того же видимого `DocumentRequest` сохраняет состояние. `rememberSaveable` переносит только идентификатор запуска, а `launched` предотвращает повторное открытие picker.
- При обычном выходе, скрытии, смене запроса или сессии VM очищает операцию и отменяет работу. Доставка старого идентификатора игнорируется. Принятый URI потребляет pending до начала работы, поэтому повторная доставка не запускает запись. Проверки поколения и сессии защищают новый экран от запоздавшего результата или `finally`.
- После смерти процесса новая VM стартует без pending. URI, секреты билета, экспортируемые данные и путь временного файла в saved state не добавлены. PDF остаётся доступен владельцу активного билета, XLSX проверяется по владельцу события. Порты, правила авторизации перед записью, schema 5 и общие сценарии EventPass находятся вне изменения.
- Разделение ответственности соответствует MVVM и существующим границам DI. Compose сообщает о жизненном цикле и доставляет ActivityResult, VM владеет состоянием операции, репозиторий проверяет доступ, файловый адаптер пишет данные. Флаг конфигурации остаётся в UI, доменные порты не зависят от Activity.

Две новые JVM-регрессии проверяют сохранение pending для PDF/XLSX, продолжение приостановленной записи, игнорирование повторной доставки и очистку при выходе, скрытии и смене ресурса. Прежние тесты проверяют отмену picker, ошибки записи, logout и поздний результат. Документация `29-t09-saf-recreation-validation.md` и `architecture/t09-profile-files.md` согласуется с этой моделью. Контракт регистрации callback и хранения сопутствующего состояния описан в [Android Activity Results](https://developer.android.com/training/basics/intents/result).

## Выполненные проверки

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio1\jbr'
.\gradlew.bat :app:testDemoDebugUnitTest --rerun :app:assembleDemoDebug :app:assembleDemoDebugAndroidTest :app:lintDemoDebug :app:verifyDemoIsolation --offline --console=plain --max-workers=2
python scripts/verify-architecture-docs.py
git diff --check c75ce56^ c75ce56
```

- Gradle завершился за 28 секунд, 3 задачи выполнены и 175 актуальны. Свежий XML содержит **66 app unit-тестов, 0 failures, 0 errors, 0 skipped**. Сборочные задачи app/test APK и demo isolation прошли. Lint содержит **0 ошибок и 4 предупреждения**. Лог `.gradle/review-c75ce56-build.log`.
- Архитектурный verifier проверил 14 страниц и 275 локальных ссылок, 9 блоков Mermaid, 18 mobile IDs, S01–S14 и ссылки на тесты. `git diff --check` прошёл.
- SHA-256 выходного и архивного APK совпадает с docs/29 — `660f12f354dd963066a24f3a17cee318fcec7a302961748d4acefbd7f25c0ece`, версия `0.9.1-t09-demo`, code 19.
- Проверены сохранённые журналы `.gradle/t09-saf-recreation/red-device-4.log` и `green-device.log`. На code 18 два SAF-теста завершились ошибкой. На code 19 оба и три прежних теста профиля и файлов прошли, **5/5 за 43,105 с**. DocumentsUI открыт на Android, PDF проходит пересоздание Activity, XLSX — поворот. Успешный URI доставляется через реальный ActivityResultRegistry от тестового провайдера, затем проверяются статус и непустые PDF/ZIP bytes. Это данные разработки на отдельном API 33 AVD.

## Пределы и остаток

Свежий device-прогон и полный повтор domain/fake/platform suites не выполнялись. Выбор папки и сохранение через независимые провайдеры, открытие во внешних приложениях, process death, физическая камера, API 28/37 и полный accessibility smoke остаются за пределами ревью. Ручная перепроверка SAF на code 19 и T07/H3/H4 ожидаются. T09 остаётся IN_PROGRESS, Q10/deletion и backend/API открыты.

Первый повторный вызов PowerShell с повышением был отклонён автоматическим согласованием из-за лимита использования. После просьбы продолжить согласование сработало, и приведённые проверки выполнены. Отчёт дописан с сохранением прежнего содержимого. Исходники приложения и тестов сохранены.

## 2026-11-13 — T10 M00: актуальные правила и карта API (aa5050f)

**VERDICT: NO_CONFIRMED_FINDINGS.** Новых подтверждённых дефектов P0–P3 в проверенной области M00 не обнаружено. Это документационная готовность к следующему этапу, не подтверждение корректности ещё отсутствующего remote-кода и не production-приёмка.

### Область и база

- Коммит `aa5050f`, база `0493dbd2e328796cd963689cc25cf3d4d2bd40e1`, ветка `codex/t10-api-integration`.
- Изменённые документы: AGENTS.md, README.md, docs/00, 06, 08, 14, 34, 35, docs/architecture/invariants.md; новые docs/api/migration-mapping.md, docs/api/backend-openapi-1.1.1.json и docs/validation/t10-m00-implementation.md.
- До этапа дерево уже содержало изменённую и untracked документацию. Для восьми затронутых прежних файлов проверен собственный diff M00 относительно `.gradle/m00-baseline/manifest.json` и сохранённых копий; SHA-256 всех восьми копий соответствует manifest. Остальные исходные dirty документы не трактуются как изменения M00. Коммит сохраняет часть ранее незакоммиченного контекста в перечисленных документах.
- Исходники app/core/data и Gradle по сравнению с базой не изменены. Читались интерфейсы domain и точечно backend snapshot `.gradle/backend-v11-def6565`, но полного ревью исходников Android/сервера в M00 не проводилось.
- Применён android-code-review. Поздние прямые решения пользователя и AGENTS/08/34 имеют приоритет над устаревшим no-autoaccount в защищённом тексте скилла. Skill не изменён.

### Что проверено

Полнота распределения 48 методов 12 существующих портов, различие KEEP/CHANGE/REMOVE/ADD, покрытие 33 HTTP-операций на 30 путях. Проверены ключевые несовпадения: signup возвращает Session; login допускает unverified; verify/reset идут через браузер без code/grant; guest autoaccount не Session; EventId и slug различаются; nullable draft и LIVE/default end; mutable анкета; sparse scanner/attendee и display checker name вместо UserId; revision dashboard не write precondition; серверные файлы и удаление ticket resend.

Проверены машинные ошибки/Retry-After/idempotency, limits login 1–256 отдельно от signup/setup/reset 8–256, отсутствие выдуманного expiresAt/ID, profile read M01 и mutation M08, guest saved-state lifetime, отсутствие autosubmit после login. Текущий demo обозначен фактической старой реализацией, target не выдан за уже работающий remote. Правила последовательных этапов/ревью/контекста соответствуют команде пользователя; промежуточная ручная приёмка не требуется и новые H-проверки пройденными не отмечены.

### Подтверждённые находки

Нет в пределах указанного документационного diff. Стилевые предпочтения и ещё не реализованные M01–M09 не оформлялись как дефекты M00.

### Проверки, выполненные независимо

- `python scripts/verify-architecture-docs.py` — exit 0: 15 current pages, 319 local links, 9 Mermaid blocks, 18 mobile IDs, S01–S14 и named test references. Это структурная проверка; Kotlin-семантика и rendering Mermaid ею не доказаны.
- `python .gradle/verify-m00-mapping.py` — exit 0: 48 method declarations / 12 ports, локальные ссылки mapping корректны, 33 backend operations. Скрипт подтверждает структурное присутствие имён, не тестирует будущие HTTP adapters; смысл ключевых строк проверен чтением кода и схем.
- `git diff --check` — exit 0; только CRLF→LF предупреждения в прежних dirty документах, без whitespace errors.
- `git diff aa5050f^ aa5050f --name-only` — exit 0, область соответствует перечисленным документам; `git diff 0493dbd --name-only -- app core data settings.gradle.kts gradle/libs.versions.toml` — exit 0, вывод пустой.
- Python read-only сверка JSON snapshot с прежним `.gradle/backend-v11-live-openapi.json`, required/properties моделей Session/Registration/ScanResult/Attendee/DashboardStats/EventInput и SHA-256 baseline — exit 0. Snapshot семантически равен прежнему, hash `9e5e31730341979c2bad95dd69fb6c37386d69bf6e2fff89d1732c44137f0d2a`.
- Исходный CODE_REVIEW.md до добавления записи: SHA-256 `BE11F7619BE9C466C1C45586098B230351E9BDC5F6B51A32BBB894AF8C5B52F9`. Предыдущие байты сохранены полностью, запись добавлена в конец.

### Вопросы, допущения и NOT_RUN

- Точная deploy SHA не возвращается staging; info.version 1.1.1 и семантическое равенство схем не доказывают идентичность всех runtime-правил. Свежий сетевой GET независимым ревьюером не выполнялся; проверен сохранённый M00 snapshot.
- Conditional PATCH пока отсутствует, строгий multi-device lost-update guard не заявлен. Настройка terms/privacy и доступ к mailbox нужны для финального auth/legal smoke, не являются пропущенной реализацией M00.
- `.gradle/verify-m00-mapping.py` и baseline служебные и не входят в коммит; их проверки выполнены в текущем workspace. Будущие этапы должны закреплять значимые повторяемые контрактные тесты в репозитории.
- Android assemble/lint/JVM/device tests и серверные mutation/browser tests в этом ревью не запускались: нет изменения кода. Инкрементальный baseline root (1 выполненная Gradle-задача/82 up-to-date) не представлен как свежий прогон unit-тестов. Mermaid rendering не выполнялся.
- Ручная приёмка нового T09a/remote остаётся финальной после M09. Решение TECH_ACCEPTED/переход к M01 принимает главный агент, статусы ревьюером не менялись.
