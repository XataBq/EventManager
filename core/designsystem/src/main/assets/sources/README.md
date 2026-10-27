# Assets T03

Получены 2026-10-27 для Android UI-кита. Сетевых ссылок в runtime нет.

- `inter_variable.ttf` в `res/font`: [Google Fonts / Inter](https://github.com/google/fonts/tree/main/ofl/inter), файл `Inter[opsz,wght].ttf`, copyright Inter Project Authors. SIL Open Font License 1.1 — `Inter-OFL.txt`. Один локальный variable TTF, четыре объявления Font с wght 400/500/600/700. Набор включает кириллицу; другие письменности могут использовать системный fallback.
- `ticket`, `calendar`, `user`, `back`, `brand`, `avatar_user`: SVG из Figma EventPass, mobile-блок `35:480`, tab bar `35:437`; file `i2NNhWGwunnvhZZX3GPuSR`. Размеры исходных SVG/viewBox сохранены в VectorDrawable.
- `search`, `toggle_on`, `toggle_off`: Figma `35:217`, узлы `35:172`, `35:191`, `35:193`.
- `ticket_action`, `download`: Figma `35:157`, образцы основной/вторичной кнопки. Это отдельные SVG, не замена glyph из tab bar.
- `success`, `warning`, `danger`: SVG Result mark из полного контекста `35:486`; отдельная панель `35:285` не получена повторно из-за лимита Starter. Размер 72×72, исходные цвета/opacity/stroke сохранены.
- `visibility`, `visibility_off`, `close`, `check`, `error_outline`: [Google Material Design Icons](https://github.com/google/material-design-icons), baseline 24 px. Apache License 2.0 — `Material-Icons-LICENSE.txt`. Дополняют Figma там, где нужен переключатель пароля/ошибка/доступная отметка; не подменяют tab icons.

Figma — предоставленный пользователем источник дизайна проекта, а не заявленная сторонняя open-source библиотека. Файл Figma не изменялся. Оригинальные SVG хранятся здесь для происхождения и проверки преобразования; app использует `res/drawable/ic_*.xml`. SVG path перенесены без редактирования; rect/circle преобразованы в эквивалентные arc paths. Иконки действий имеют внешнюю область касания ≥48dp; glyph bounds сохраняют исходные пропорции.

Слоты: Ticket/Calendar/User → BottomBar; Brand/Avatar/Back → TopBar; Search → SearchField; ToggleOn/Off → SettingsSwitch; Result marks → ResultMark; Material Visibility → PasswordField; Check → ConsentCheckbox; Error → ErrorState/InlineError.
