# Профиль, radio, размеры действий и копирование — code30

18 ноября 2026. IMPLEMENTED / REMOTE_APK_INSTALLED. Base 635d809 плюс сохранённые локальные правки code27–29. M09/human acceptance не закрываются; commit/push не выполнялись.

## Изменения

- В профиле убрано повторное отображение email над полями. Адрес остаётся в FormField; legacy/demo без поддержки редактирования email получает то же поле в disabled состоянии. Профильные команды/валидация прежние.
- Обычный одиночный выбор в регистрации использует RadioOption из designsystem: стандартный Material3 RadioButton с кружком, выбор по всей строке, Role.RadioButton и selectableGroup. SELECT остаётся dropdown, MultipleChoice/Checkbox не менялись; сохраняется прежний Answer.SingleChoice(optionId).
- MyEventActions заменяет FlowRow: измеряет полные подписи с фактической типографикой/density/fontScale и padding; объединяет только соседние действия, если оба целиком помещаются на строке. Ширина распределяется пропорционально нужному месту, обе кнопки пары имеют одну высоту с учётом метрик текста/touch target. Иначе действие идёт отдельно на всю ширину. Последовательность/права/callbacks/test tags прежние; helper применяется и remote, и demo веткой экрана.
- В QuestionEditor внешние промежутки 16→8dp, внутри карточки варианта 8→2dp, padding 12→8dp. Стрелки и удаление находятся на одной строке вместо двух. Размеры touch targets сохранены; уплотняется пространство между контролами.
- Clipboard callbacks в MyEventsRoute и EditorRoute после публикации показывают короткий системный Toast «Ссылка скопирована» после записи ссылки. Repository/VM/сохранение ссылки не менялись.
- В remote меню плюса убрана кнопка «Понятно». Два действия fillMaxWidth с TextAlign.Center, в том числе при переносе строки. Закрытие снаружи/Back и прежние переходы остаются.

## Поставка

remoteDebug code30, 0.10.13-ui-spacing-remote. Production API https://eventpass.dikanskii.ru/api/.
APK: app/build/outputs/apk/remote/debug/EventPass-0.10.13-code30.apk.
SHA-256: 3a7c17ce8ce19a97108c0e5b7a7fef1f94382a855554bf00cb6478b091055fb7.

Одна assembleRemoteDebug: BUILD SUCCESSFUL, 28s. Android CLI install -r: exit0 на Redmi M2101K7BNY, MainActivity запущена. Данные не очищались.

По прежней команде пользователя tests/scenarios/lint/review/demo/release/device suites, архитектурный verifier и автоматическое UI-прокликивание не запускались. Визуальное поведение, TalkBack/fontScale и строки разных ширин не объявляются проверенными по факту компиляции. Никаких операций с участниками/мероприятиями на сервере не выполнялось. Посторонние изменения сохранены.
