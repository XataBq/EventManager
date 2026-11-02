# UI-кит и Android-адаптация

Источник: [06 / UI-кит](https://www.figma.com/design/i2NNhWGwunnvhZZX3GPuSR/Untitled?node-id=35-486). Исходный JSON — `sources/figma-2026-10-21.json`. **T03, 27 октября:** реализованы нативные компоненты в `core:designsystem`, локальный Inter и demo-галерея. H1 ожидается; наличие компонентов не означает утверждённое визуальное соответствие. Отчёт — [17-t03-validation.md](17-t03-validation.md).

## Фактический API T03

После подтверждённой H1 (29 октября) и интеграции T04 галерея открывается через «Демо-инструменты → Открыть UI-кит» с Login и из авторизованной оболочки, а также поверх счётчика. У гостя нет нижних табов; private gallery — запись в существующем стеке, без четвёртого таба. Компоненты получают состояние/callbacks, не знают Hilt/репозитории/navigation; auth-экраны используют тот же UI-кит, safeDrawing/IME и Inter.

| Файл в core/designsystem | API и назначение |
|---|---|
| `EventPassTokens.kt`, `EventPassTheme.kt` | semantic colors, Inter 400/500/600/700, Typography, Shapes, размеры/отступы; только dark, без dynamic color |
| `EventPassActions.kt` | EventPassButton: ActionStyle Primary/Secondary/Danger/Text, enabled/loading/compact/icon; TextAction |
| `EventPassFields.kt` | FormField: label/value/placeholder/focus/error/help/enabled, KeyboardOptions/Actions, multiline; PasswordField с локальным переключением видимости; SearchField |
| `EventPassSelection.kt` | ConsentCheckbox, SettingsSwitch, OptionCard (radio semantics), SegmentedTabs с необязательными счётчиками и горизонтальным скроллом |
| `EventPassData.kt` | StatusBadge/StatusDot/ResultMark с StatusTone; AvatarInitials, KeyValueRow, MetricCard, EventPassSection |
| `EventPassStates.kt` | EmptyState, ErrorState с retry callback, InlineError с live region, ConfirmDialog с обычным/destructive/loading состояниями и optional errorText внутри окна |
| `EventPassNavigation.kt` | TopBar Guest/Authenticated/Nested; BottomBar ровно Tickets/Events/Profile. System insets включены по умолчанию, в галерейных образцах явно отключены |
| `EventPassIcons.kt`, `EventPassPreviews.kt` | Локальные VectorDrawable; previews кнопок/полей/выбора/статусов, 360/390dp и fontScale 1.3/2.0 |

`ComponentGallery` находится в `app/src/demo`, содержит семь групп с локальным интерактивным состоянием. Текстовые поля и пароль — временные примеры, без дискового сохранения; переключатели и выбранная группа могут восстанавливаться через rememberSaveable. Примеры не вызывают product repositories и не генерируют QR.

Чтение Figma 27 октября: получен полный high-fidelity context и общий screenshot `35:486`, отдельно контекст/screenshots кнопок `35:157`, форм `35:217`, mobile `35:480`. Повторный запрос статусов `35:285` ограничен Starter; детали сверены с полным контекстом и исходным снимком. Файл Figma не изменялся. Текущие технические детали/focus/disabled/loading/error — Android-адаптация; вручную сравнить их на H1.

Assets: локальный variable TTF [Google Fonts Inter](https://github.com/google/fonts/tree/main/ofl/inter), SIL OFL 1.1; [Material Icons](https://github.com/google/material-design-icons) для дополнительных glyph, Apache 2.0. Tab/header/search/toggle/result/action SVG взяты из предоставленного EventPass Figma и переведены в VectorDrawable с сохранением viewBox, путей, stroke/alpha и естественных размеров. Оригиналы, источники и обе лицензии — [assets/sources](../core/designsystem/src/main/assets/sources/README.md). Font cmap проверен на кириллицу, диапазон wght 100–900; веса подключены как 400/500/600/700.

Visual min-height кнопок 44/36dp и углы 8dp сохранены внутри области касания ≥48dp. Высоты растут с текстом, длинные подписи переносятся. Поля с дополнительным action могут быть выше 44dp для доступного hit target. Avatar с инициалами также растёт при большом шрифте. Bottom icons сохраняют исходные bounds внутри 24dp слота. Скругления и disabled/status alpha централизованы; точная визуальная приёмка расширенных состояний — H1.

Оболочка применяет и потребляет горизонтальные `safeDrawing` insets вокруг всего Scaffold: верхняя/нижняя панели и содержимое защищены от боковой navigation bar и выреза при любом повороте. TopBar по умолчанию учитывает `safeDrawing` сверху и по горизонтали; внутри оболочки уже потреблённый горизонтальный отступ не повторяется. BottomBar применяет оставшиеся navigation insets. IME padding остаётся один раз у оболочки, NavDisplay потребляет переданный Scaffold padding. Это исправляет P2 из ревью `a263c21`; red/green и матрица поворотов — в [отчёте T03](17-t03-validation.md). Поведение потребления соответствует [Compose window insets](https://developer.android.com/develop/ui/compose/system/insets-ui#inset-consumption). Сохранение entry owners скрытых стеков не менялось; добавлены только GalleryKey и вход/Back. Визуальные guest/auth примеры не реализуют вход.

## Цвета

| Semantic token | Значение | Назначение |
|---|---|---|
| background | `#0F131C` | Фон, шапка, нижняя навигация |
| card | `#181B25` | Карточки и segment container |
| surface | `#1C1F29` | Поля, вложенные блоки |
| raised | `#262A34` | Вторичные кнопки, badges, выделение |
| accent | `#8083FF` | Основное действие, toggle, outline выбора |
| accentLight | `#C0C1FF` | Ссылки, активный таб, аватар |
| onAccent | `#0D0096` | Текст на primary |
| textPrimary | `#DFE2EF` | Основной текст |
| textSecondary | `#C7C4D7` | Подписи и описания |
| muted | `#908FA0` | Подсказки/placeholder |
| success | `#34D399` | Проход разрешён |
| warning | `#FBBF24` | Повторный проход, мало мест |
| danger | `#F87171` | Ошибки, отмена, отзыв |
| footer | `#0A0E17` | Desktop footer; не создавать footer в Android |

Выделить собственный `EventPassColors` поверх Material 3; не пытаться выразить все статусы одним `colorScheme.error`. Dark theme — единственная заданная дизайном тема MVP. Dynamic color выключен, light theme пока не проектируется. Подложки полупрозрачных статусов и границы уточнять по конкретному узлу, не придумывать новый набор цветов на каждом экране.

## Типографика

Семейство **Inter**, веса 400/500/600/700. Упаковать разрешённые к распространению font assets локально и проверить лицензию при добавлении; загрузка шрифта из сети на старте не нужна.

| Стиль | Размер из Figma → Android sp | Вес | Line height из прочитанного узла |
|---|---|---|---|
| Mobile H1 | 26 | Bold 700 | 125% ≈ 32.5 |
| Card H2 | 18 | Semi Bold 600 | 145% ≈ 26.1 |
| Button/body emphasis | 14 | Medium 500 | 145% ≈ 20.3 |
| Body | 14 | Regular 400 | 145% ≈ 20.3 |
| Label/badge | 12 | Medium 500 | 145% ≈ 17.4 |
| Hint | 12 | Regular 400 | 145% ≈ 17.4 |
| Tab caption | 11 | Medium 500 | образец 145%, сам таб AUTO; проверить визуально |

Desktop H1 32 не переносить в mobile. Значения sp — стартовая адаптация, а не утверждение, что Figma px физически равны Android px. При font scale текст и контейнеры должны расти; фиксированные высоты текста не применять.

## Прочитанная геометрия

| Элемент / узел | Параметры |
|---|---|
| Primary `35:109`, secondary `35:116`, danger `35:121` | Высота 44, radius 8, горизонтальный padding 16, gap 8 |
| Small action `35:126` | Высота 36, radius 8, horizontal padding 16 |
| Input `35:160`, `35:167`, `35:175` | Высота 44, radius 8, horizontal padding 14, gap 10 |
| Field `35:161` | Между label и input 6 |
| Badge `35:219` и соседние | Radius 4, padding vertical 2 / horizontal 8 |
| Tab bar `35:437` | Высота 64, padding top/bottom 10, horizontal 8 |

В Android интерактивная область не меньше 48dp: визуальную кнопку 44/36 можно разместить в большей touch area, не допуская перекрытия соседних областей. Insets системных панелей добавляются отдельно от высоты 64. Не копировать длинный Figma frame как фиксированную высоту экрана.

Ширина эталонного контента — 390px. При реализации брать относительную ширину и адаптивные отступы; для телефонов 360–412dp проверять переносы. Некоторые screenshot bounds равны 406 из-за выходящих за frame эффектов; это не новая целевая ширина устройства.

## Переиспользуемые компоненты

| Группа | Будущие Compose API | Состояния |
|---|---|---|
| Кнопки | EventPassButton, SecondaryButton, DangerButton, TextAction | enabled, disabled, loading |
| Формы | FormField, PasswordField, SearchField, ConsentCheckbox, SettingsSwitch | empty, filled, focused, error, disabled |
| Вопросы | QuestionField, OptionCard, SingleChoiceField | required, selected, invalid, unsupported type |
| Переключатели | SegmentedTabs | selected, count, empty |
| Статусы | StatusBadge, StatusDot, ScanResultMark | success, warning, danger, neutral + текст |
| Данные | AvatarInitials, KeyValueRow, MetricCard, ParticipantRow | длинные значения, неизвестное значение |
| Карточки | EventCard, TicketCard, FormSection | optional metadata, cancelled/used, loading |
| Навигация | EventPassTopBar, EventPassBottomBar | guest, authenticated, nested/back |
| Состояния | EmptyState, ErrorState, InlineError, ConfirmDialog | retry, destructive confirmation |
| Специальное | QrTicketContent, CameraOverlay | разрешение камеры, loading, QR full screen |

Сначала базовые примитивы и previews, затем композиции в feature-пакетах. Не встраивать сетевой вызов в UI-kit и не делать universal Card с десятками флагов.

## Содержательные расхождения дизайна

- Блок 06 содержит desktop-заготовки с «Сканер» и «Аналитика»; они противоречат актуальной навигации 00. В Android ровно три таба.
- По уточнению B02 гостевой G02 сохраняет QR-билет и сообщение об отправке на почту. Убрать обещание автоматически созданного аккаунта; объяснить временный доступ: после закрытия вернуться к билету можно через письмо/кабинет. Гостевой PDF отдельно не задан. В G03 убрать обещание сохранения ответов и «Войти и завершить регистрацию». Подтверждение email → ручной вход; проверить H2.
- «Платно» и «Шаг 1 из 2» убрать из редактора согласно заметке 04.
- Добавить отмену регистрации под действиями билета и в меню участника дашборда: это текстовое MVP-требование, хотя кнопок ещё нет.
- В независимой T09 профиль показывает недоступность удаления до согласования Q10; ложного диалога завершённого удаления/reset нет. Name/phone/organization/reminders/save используют прежние поля/действия UI-kit; email read-only.
- QR в Figma декоративный, с наложением иконки. Рабочий QR генерировать из токена с достаточной quiet zone; не накладывать значок без проверки считывания.
- UI-кит показывает visual frames, а не готовую связанную компонентную библиотеку. Соответствие поддерживается кодовыми токенами и ручной приёмкой.

## Android и доступность

Предлагаемая реализация: Compose Material 3 с собственными токенами; edge-to-edge с корректными systemBars/IME insets; скроллируемые формы; подходящие keyboardType/IME actions; TalkBack labels; описания иконок действий; статус всегда текстом, не только цветом. Режим fullscreen QR скрывает лишние элементы, яркость при необходимости меняется только для окна и восстанавливается при выходе.

Ручная проверка: 360/390–412dp, font scale 1.0 и 1.3 (критические экраны также 2.0), клавиатура, длинные ФИО/email/заголовки, тёмная системная панель, TalkBack на основных действиях. Не считать совпадение картинки достаточной проверкой поведения.
