# Следующие замечания UI — 18 ноября 2026

Статус: IMPLEMENTED / APK_INSTALLED, code28 / 0.10.11-prod-ui-remote. Base Git: 635d809 плюс сохранённые локальные изменения code27; коммит и push не выполнялись. M09/human acceptance этим не закрываются.

## Изменения

- Убрана demo-строка именно под кнопкой регистрации: production и demo теперь используют разные ресурсы.
- RegistrationQuestion.SingleChoice с format=SELECT отображается через общий SelectField: Material3 ExposedDropdownMenuBox, read-only OutlinedTextField, выбор optionId и прежний callback Answer.SingleChoice. CHOICE сохраняет обычный выбор. DTO/правила анкеты не менялись.
- Каждый дополнительный вопрос любого типа имеет отдельную нумерованную карточку с рамкой/отступами. Checkbox, MultipleChoice, варианты, подписи и ошибка визуально принадлежат своему вопросу; у Select/Text единственный supporting slot.
- TicketQr принимает optional onOpen: inline QR доступен по тапу/семантическому действию кнопки, fullscreen QR не имеет вложенного открытия. Отдельные кнопки полного QR убраны у гостя и своего билета.
- EventPassSection заполняет доступную ширину и на Surface, и на внутреннем Column: карточка без QR не сжимается до текста. Внешние отступы сохранены.
- Действия MyEvents в remote и demo размещаются последовательным FlowRow, максимум два действия в строке; weight распределяет доступную ширину строки, длинные действия переносятся. Порядок callbacks не менялся.
- В профиле ID доступен для выделения, рядом IconButton копирования с короткой отметкой успеха. Clipboard callback прежний, отдельная большая кнопка убрана.
- Предупреждение о неподтверждённой почте находится внутри ProfileScreen и зависит от актуального ProfileUiState.profile.account.emailVerified; не дублирует невидимое root-состояние. Ненавязчивая tinted surface и текстовое действие вызывают переданный callback навигации. Для подтверждённого email блок отсутствует.
- category больше не показывается в своей карточке: пользователь её не выбирает. В текущем исходнике backend get_category берёт label первого question.type=choice либо «Участник»: https://github.com/EgorDikanskiy/eventpass/blob/main/backend/api/tickets.py#L55 . Это объясняет имя/произвольный ответ вместо роли. Backend, сохранённые ticket.category, PDF и scanner metadata не изменялись; поле не определяет клиентские права.

## Поставка и ограничения

API https://eventpass.dikanskii.ru/api/. APK: app/build/outputs/apk/remote/debug/EventPass-0.10.11-code28.apk.
SHA-256: 5424ffd21728f701fa5b2b8c8c35ca386e1c890b46dc42402e2374d09ac06090.

assembleRemoteDebug успешна после исправления отсутствующего import; финальная адресная сборка после размещения warning внутри ProfileScreen — 11 секунд. Android CLI install -r завершился с exit 0 на Redmi M2101K7BNY; MainActivity запущена. Данные приложения не очищались.

По действующей команде пользователя tests/scenarios/lint/review/device suites/demo/release builds/architecture verifier не запускались. UI не прокликивался автоматически; поведение и визуальное качество требуют проверки человеком. Существующие UI-сценарии с прежней кнопкой open-fullscreen не выдаются за актуальную проверку новой интеракции. Посторонние изменения сохранены.
