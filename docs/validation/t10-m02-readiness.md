# Подготовительные наблюдения M02

**Историческая подготовка.** Сведения ниже относятся к исследованию 13 ноября. На 15 ноября M02 частично реализован и передан как WIP_HANDOFF; актуальное состояние — [handoff](t10-m02-handoff-2026-11-15.md). Старое «не начат» ниже не является текущим статусом.

Read-only исследование 13 ноября на исходном Android `0493dbd`, пока M01 изменяет auth/source sets. M02 **не начат**, тесты не запускались. Перед реализацией перечитать итог M01; это handoff, не ревью готового M02.

- Backend slug может содержать кириллицу и быть длиной до 73 символов: текущий ASCII regex64 для ReturnDestination.Event нельзя переносить на EventSlug. Разделить public reference и внутренний EventId; корректно кодировать path segment, не decode дважды.
- Все 10 wire question types должны сохранить subtype, help/placeholder и option hints. Новый MultipleChoice влияет на exhaustive when в renderer/editor/validator/codec и demo AttendanceXlsxWriter, хотя remote XLSX подключается позже.
- formVersion сервера отсутствует. Локальный schema fingerprint может защищать state/generation и совместимые ответы, но не выдаётся за серверную precondition. Старый EventViewModel при смене formVersion очищает форму целиком; target должен сохранять совместимые значения, явно обозначая несовместимую правку.
- Поля server summary применяются и к nullable draft: нельзя подставлять now/0 в обязательный published Event. LIVE и effectiveEndsAt должны одинаково учитываться public availability/scanner/tickets/dashboard.
- Сервер: title/location/labels/help/placeholder/section title/time до300, description20000, questions/options/sections50, text answer4000; Unicode question.id/option.value до300. Старые локальные regex64 и limits200/10000 не являются серверными правилами.
- Текущий seed содержит SingleChoice с одним вариантом и опубликованные Unlimited/0; target min2 и positive capacity требуют явной миграционной политики, не добавления выдуманных мест/вариантов.
- Codec v1–v5 использует writeUTF с лимитом 65535 modified UTF bytes: description20000 с некоторыми Unicode-символами может не поместиться. Новая схема требует bounded length-prefixed UTF-8 либо явного отдельного storage limit; одного поднятия UI-limit недостаточно.
- Не менять старый QR/реальный first check-in ради расширения Event. Совместимые данные переносить, несовместимый legacy demo сохранять/объяснимо сбрасывать отдельно от remote pending, повреждённый snapshot не перезаписывать автоматически.
- Старые тесты закрытия регистрации после старта/permanent QuestionsLocked/Unlimited должны менять ожидания по принятому продукту. Проверки session/cancellation/storage failure/owner/late-result/corrupt snapshot остаются обязательными.

Точки: EventModels/EventEditing/RegistrationModels/RegistrationValidator/ProductRepositories; EventViewModel/EventScreen; EditorViewModel/EditorScreen; NavigationState/SessionCoordinator/flavor hosts после M01; FakeTicket/EventEditing/CheckIn/Attendance/Fixtures/ProductSnapshotCodec; platform AttendanceXlsxWriter. Полный scope и критерии — [34](../34-api-migration-plan.md), mapping — [migration-mapping](../api/migration-mapping.md).
