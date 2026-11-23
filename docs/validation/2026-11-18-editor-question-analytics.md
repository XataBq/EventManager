# Редактор и аналитика ответов — code29

18 ноября 2026. Статус: IMPLEMENTED / REMOTE_APK_INSTALLED. Base: 635d809 + сохранённые локальные code27/code28. Это не новый PASS M09 и не человеческая приёмка.

## Редактор и поля

- Обложка выбирается по тапу на ManagedCover slot; отдельная кнопка выбора убрана, удаление осталось компактным. Import/managed ownership/repository callbacks прежние.
- Новый общий TemporalField в core:designsystem: календарь Material3 DatePicker и последующий wheel часов/минут на Android NumberPicker. UI/выбор принадлежат composition/saveable состоянию конкретного поля, ViewModel получает прежнее строковое значение лишь после подтверждения полного выбора. Отмена не меняет исходное значение.
- DATE_TIME хранит uuuu-MM-dd HH:mm в zoneId редактируемого мероприятия, DATE хранит yyyy-MM-dd без времени по существующему контракту, TIME программы хранит HH:mm. UTC используется только для выбранного календарного дня Material3, не для смещения введённого местного времени. Нормализация/DST/валидация остаются в EditorInput и domain. Очистка доступна для необязательных конца/даты/времени; пустой endsAt оставляет серверный default startsAt+4h. Первое предлагаемое окончание — начало+4h, без автоматической записи.
- Начало/окончание и время блоков программы используют picker; даты ответов анкеты — DATE picker. Public questions имеют стабильный composition key q.id.
- LocalProminentSecondaryActions=false ограничен EditorScreen: secondary/text/danger без яркой заливки, тонкая рамка, компактная видимая высота с прежним touch target. Primary публикации остаётся выраженным. Остальные экраны не меняют стиль. Перемещение вопросов/вариантов — IconButton со стрелками и доступным названием.

## Участники и аналитика

- Общая сводка/сканер/волонтёры/экспорт сохранены сверху, её высота ограничена с собственной прокруткой. Внизу два HorizontalPager page: Участники и Аналитика; вкладки и свайп синхронизируются с DashboardViewModel.panel по settledPage.
- Список показывает email, номер и статус. ParticipantDialog по нажатию хранит только текущую Attendee в live VM, прокручивается и закрывается снаружи/Back. Внутри имя/почта/билет/check-in и ответы по ID вопроса. Маленькое действие отмены открывает прежний ConfirmDialog; команда по ticketId, single-flight, повтор и серверные guards сохранены. Пока команда идёт, закрытие/смена target запрещены. Успех закрывает оба диалога и обновляет список, доступ/ошибки не объявляются успехом.
- Отображение ответов — resolveAttendanceAnswer в domain: SingleChoice/Text ID связывается с option label по текущему q.id; MultipleChoice разворачивается в подписи, Checkbox даёт Да/Нет только при соответствующем типе вопроса. Неизвестные ID не выводятся как текст; отсутствующий/сменённый тип отмечается как устаревший, исходная история не переинтерпретируется как новый ответ.
- AttendanceDashboard теперь содержит текущие questions. AttendanceRepository.questionAnalytics — отдельное owner-чтение; remote не публикует его в query-проекцию списков и не меняет pagination/поиск списка. Fake также формирует questions/answers и использует общий accumulator, но отдельно не собирался.
- Remote читает metadata+Stats+page1 с pageSize=100, затем все последовательные страницы без search с той же revision и размером 100. Проверяются total, уникальные ticket IDs, параметры и revision. В конце повторно читаются Stats и owner metadata: attendance revision и questions должны совпасть. Изменение схемы проверяется отдельно, поскольку её версия не обещана attendee revision. При STALE_PAGE — ограниченный новый полный снимок, частичные проценты не публикуются.
- QuestionAnalyticsAccumulator хранит только счётчики по Checkbox/SingleChoice/Select/MultipleChoice. Проценты — count / все участники с ACTIVE/USED билетами; CANCELLED исключены. Множественный выбор может дать сумму >100%; отсутствие/устаревшие варианты выделяются отдельно. Нулевая база даёт 0%, не деление на ноль.
- analyticsJob имеет отдельный request token+owner; уход с вкладки/stop/отмена/смена actor отменяют read, late result не применяется. Поиск не влияет на проценты. Pull-to-refresh обращается к settled panel, при горизонтальном движении/диалогах отключён. Transport error оставляет прошлый снимок с ошибкой/временем получения; начальная загрузка не выдаётся за пустую статистику.

## Ограничение исторических подписей API

GET attendees возвращает answers (IDs), но не сохранённый answer_labels или историческую схему. Источник: https://github.com/EgorDikanskiy/eventpass/blob/main/backend/api/organizer.py#L48 . В нормальном случае текущая схема позволяет раскрыть подписи. При удалении/переименовании вопроса/варианта старый текст нельзя гарантированно восстановить: показывается пояснение, а не выдуманная подпись. Неизменённый optionId с переименованным label отображается текущей подписью, поскольку API не даёт версии. Для точной истории backend должен вернуть snapshot вопроса и типизированные исторические labels ответа (например answerLabels вместе с question snapshot); для крупных мероприятий желательно server aggregate endpoint с revision/явной базой процентов. Backend/deploy не изменялись.

## Поставка и фактическая проверка

remoteDebug code29, 0.10.12-editor-analytics-remote; production https://eventpass.dikanskii.ru/api/.
APK: app/build/outputs/apk/remote/debug/EventPass-0.10.12-code29.apk.
SHA-256: 46ab72f3f5d2c11096c89c22abe25ff4877e355e2d5738cfe23ab8947f87f460.

Нужные assembleRemoteDebug прошли: основная сборка 53s, после дополнительных полей/header 19s, финальная адресная правка gesture gate 6s. Android CLI install -r завершился exit0 на Redmi M2101K7BNY; MainActivity открыта. Данные не очищались.

По сохранённой команде пользователя unit/scenario/device suites, lint, review, staging, demo/release build, архитектурный verifier и автоматическое UI-прокликивание НЕ запускались. Compilation не доказывает поведение UI, подсчёты или отсутствие гонок. Реальные участники не отменялись, fixtures на production не создавались. Изменения локальные, без commit/push; прежние пользовательские изменения сохранены.
