# M04–M05: редактор, MyEvents и обложки

M04 online owner slice принят пользователем на `8da678a` при запуске M05. Его [исторический отчёт](../validation/t10-m04-implementation.md) фиксирует исходный transient draft cache и upload-release протокол. В текущем remote DI draft-путь заменён durable Room coordinator M05; актуальные владельцы/состояния/операции/retention/cover lifecycle — [M05](t10-room-drafts.md). Прежние описание marker без тела и finally-release source больше не являются спецификацией remote draft-пути.

MyEventsTab адресует отдельную server projection: summary/items/roles/counters сохраняют состав сервера, details не вставляются в список. M05 localDrafts рисуются отдельно с дедупликацией по известному remote ID, не изменяют counters. Incomplete fields остаются nullable, typed questions/options/sections сохраняют IDs; remote revision null, не optimistic lock.

RemoteOrganizerRepository продолжает server MyEvents, owner read, online published update/finish; RemoteEventRepository сохраняет M02 public GET. AuthHttp/RemoteAuthRepository — общий bearer/epoch/401 gateway. Domain и UI не получают DTO/token/DAO. Published terminal authority — server status/finishedAt, не часы телефона. Publish выполняется по известному server ID после explicit sync без pending/conflict. Unknown PATCH требует read reconciliation, GET→PATCH не атомарен без backend precondition.

Editor VM сохраняет raw/dirty при живой configuration recreation; hidden/background отменяет reads/foreground operations с generation guards. Back save/discard/stay сохранён, pending Room отличается от несохранённого raw ввода. Nav различает local DraftId, internal EventId и public slug; public copy/share/open — HTTPS `/e/{slug}`. Scanner/Volunteers/Dashboard в remote ожидают M06/M07.

Platform importer копирует content в durable managed source, remote upload возвращает отдельный server asset descriptor. Для draft source сохраняется до DB transition/attach; для published save upload производится перед online update. Preview выполняет bounded GET по assetId через API origin без bearer на произвольный coverUrl, MIME/size проверяются, bitmap decode вне main. Exactly-once upload и server DELETE asset не обещаются.

Fake codec6/legacy fixtures не мигрируются в Room. Текущий M05 проверен только в объёме, перечисленном в [отчёте](../validation/t10-m05-implementation.md): remote compilation и ранние DAO/merge unit tests. Сценарии/lint/demo/device/UI после поздней команды пользователя не запускались; прежние M04 suites не выдаются за повторно пройденные.

## M09: нормализация server asset

Server descriptor описывает нормализованный asset: byteCount и mimeType могут отличаться от local source. RemoteCoverTransfer проверяет supported mime/положительный размер≤5MiB/dimensions и canonical assetId, сохраняя local EventCover metadata для managed source/Room. Сравнение stored bytes с исходными удалено после реального PNG1753→1480. Live upload/attach/detach и regression для uploadPending/importCover пройдены; [текущие доказательства](../validation/t10-m09-final-validation.md).

## Визуальная организация анкеты и выхода

EditorScreen добавляет вопрос через один dropdown типов, каждый существующий вопрос показан отдельной нумерованной карточкой с текущим типом, названием, настройками, отдельной областью вариантов и действиями порядка/удаления. Меню типа не меняет текущий вопрос при повторном выборе того же типа; смена на другой тип использует прежний edit callback с очисткой несовместимых options. Доменные типы/IDs, VM, repository/Room и правила публикации не менялись. Локальный expanded принадлежит composition каждой карточки.

Exit confirmation — один Dialog с ограниченной по высоте прокручиваемой колонкой и тремя полноширинными действиями Save/Discard/Stay. AlertDialog confirm/dismiss FlowRow для трёх действий больше не используется; busy блокирует повторные команды и закрытие. Ошибка сохранения видна внутри диалога. Проверки UI по просьбе пользователя не запускались; актуальная сборка — [отчёт M09](../validation/t10-m09-final-validation.md).

## Поля и preview после ручных замечаний — 18 ноября

EditorViewModel перед publish использует общую publicationErrors и показывает ошибки на обязательных полях; incomplete save остаётся разрешён. FormField в designsystem использует Material3 OutlinedTextField/isError/supportingText, ошибка заменяет обычный help. Вопросы/варианты группируются в карточке; help не выводится отдельно от ошибки. Правила обязательности Checkbox/MultipleChoice не менялись.

Remote EventCoverPreview — singleton DI provider с LRU 12 MiB в памяти процесса, ключ assetId. Mutex объединяет конкурентные чтения; snapshot текущей сессии проверяется перед получением/после загрузки, при смене actor cache очищается перед обращением. URL остаётся через защищённый transfer; постоянного disk cache нет.

## Действия организатора — code30

MyEventActions измеряет полные подписи с EventPassTypography.emphasis/density/fontScale и padding. Два соседних действия помещаются рядом только при достаточной ширине, получают пропорциональные ширины и одну высоту с touch minimum; иначе действие занимает отдельную полную строку. Порядок прежний, пропуска вперёд ради заполнения нет. Общий EventPassSection сохраняет ширину кратких карточек. Доменные операции/права/ID не менялись. [Поставка/NOT_RUN](../validation/2026-11-18-ui-spacing-actions.md).

## Code29: picker и спокойные действия

TemporalField принадлежит designsystem/composition. DATE_TIME возвращает прежний local datetime в zoneId события, DATE анкеты возвращает ISO date, TIME программы — HH:mm. Calendar day Material3 декодируется через UTC без преобразования часового пояса пользовательской даты. Выбор часов/минут через native NumberPicker, запись только после подтверждения, optional clear сохраняет семантику пустого endsAt/default+4h. ViewModel, transport и Room форматы прежние.

Обложка выбирается по tap cover slot, delete компактный; import/lifecycle ownership сохранены. LocalProminentSecondaryActions ограничен EditorScreen, вторичные действия имеют спокойные цвет/рамку и прежний touch target; движение вопросов/вариантов — стрелки с semantic labels. [Поставка и ограничения](../validation/2026-11-18-editor-question-analytics.md).

## Компактность редактора — code30

QuestionEditor сохраняет минимум touch targets, уменьшает внешние gaps и padding карточек вариантов. Стрелки и удаление объединены в одну строку, промежутки между полями варианта 2dp. Clipboard feedback MyEvents/Published — краткий platform Toast после записи ссылки, без новых VM событий. В меню root-плюса два полноширинных действия с центрированным текстом, отдельного dismiss action нет.
