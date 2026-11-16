# Повторное ревью исправления M02 перед M03

Работай одним агентом, без субагентов, в F:\ASProjects\MospolytechProject. Используй локальный $android-code-review. Прочитай AGENTS/README/08/06, затем docs/validation/t10-m02-fix-ticket-expiry.md и CODE_REVIEW.md:1943. База узкого исправления — 9ac807e, target — коммит, содержащий этот промпт (установи точный SHA через Git). Не повторяй полное M02 ревью и сборки без новой причины.

Проверь Ticket.isCurrent в RegistrationModels.kt и новый RegistrationRulesTest.ticketExpiryMatchesDefaultExplicitAndManualEventEnd: start+4h при null endsAt, explicit endsAt, finishedAt, точная граница, terminal lifecycle и Used/Cancelled; реальный call path TicketsScreen/FakeTicketOperations. Не требуй будущую реализацию remote M03 и не выдавай самопроверку автора за независимое ревью. Сохрани прежний CODE_REVIEW.md побайтно, добавь результат. При необходимости достаточно :core:domain:test --tests ru.eventpass.core.domain.RegistrationRulesTest, без assemble/device/staging. Изменять только CODE_REVIEW.md.

После отдельного успешного ревью исправления следующий реализационный проход может технически принять M02 и начать уже разрешённый M03 по docs/34-api-migration-plan.md и docs/api/migration-mapping.md. Межэтапная ручная приёмка не требуется, субагенты запрещены.
