# M02 — исправление P2 default окончания билета

16 ноября 2026. **FIX_REVIEW_PENDING**. Независимое ревью M02 (`9ac807e`, CODE_REVIEW.md:1943) нашло рассогласование default effectiveEndsAt с Ticket.isCurrent. Исправление — отдельный реализационный проход после завершения ревью, в рамках разрешения T10; субагенты не использовались.

## Область

Только `RegistrationModels.kt`: Ticket.isCurrent использует Event.statusAt(now), допускает PUBLISHED/LIVE и активный билет. Теперь default start+4h, explicit end и finishedAt применяются одинаково к регистрации, scanner и локальному отображению билета. DRAFT/FINISHED/CANCELLED не показывают действующий QR. Новый тест `RegistrationRulesTest.ticketExpiryMatchesDefaultExplicitAndManualEventEnd` проверяет точную границу и состояния Used/Cancelled. Контракты remote не менялись; будущий M03 не должен строить полный Event из TicketEvent или заменять server ticket policy локальной моделью.

## Доказательства

До исправления: `:core:domain:test --tests ru.eventpass.core.domain.RegistrationRulesTest.ticketExpiryMatchesDefaultExplicitAndManualEventEnd --offline --console=plain --max-workers=2`, JBR21, exit1, 1 test failed на проверке точного default окончания (RegistrationRulesTest.kt:41); `.gradle/m02-expiry-red.log`, Gradle 3s. Тест написан после завершения ревью, не во время read-only прохода. Первую попытку команды не запустила автоматическая проверка разрешений из-за таймаута, разрешённый повтор успешно запустил тест; таймаут не является дефектом приложения.

После исправления: `.\gradlew.bat :core:domain:test --offline --console=plain --max-workers=2`, JBR21, exit0, BUILD SUCCESSFUL 5s, 29 passed / 0 failures/errors/skipped; `.gradle/m02-expiry-green.log`, XML `core/domain/build/test-results/test`. Повторных assemble/lint/device/staging нет: изменена чистая domain-функция без UI/DI/transport. Ручная приёмка остаётся в конце M09.

## Следующий шаг

Нужно отдельное последовательное повторное ревью узкого fix diff относительно `9ac807e`. Автор исправления не выдаёт собственную проверку за независимое ревью. До этого M02 не TECH_ACCEPTED и зависимый M03 не запускается. M03 остаётся следующим этапом и уже разрешён пользователем; после принятия исправления повторное согласование запуска M03 не требуется. Область M03 — регистрации/idempotency/guest capability, списки/карточки/отмена билетов и первый PDF/SAF срез из 34; новые абстракции не создавать вне рабочего сквозного сценария.
