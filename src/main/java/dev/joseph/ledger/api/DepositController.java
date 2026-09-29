package dev.joseph.ledger.api;

import dev.joseph.ledger.security.CurrentUserProvider;
import dev.joseph.ledger.service.DepositCommand;
import dev.joseph.ledger.service.DepositService;
import dev.joseph.ledger.service.IdempotentExecutor;
import dev.joseph.ledger.service.IdempotentOutcome;
import dev.joseph.ledger.service.PostedTransaction;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/deposits: a simulated deposit into one of the caller's own accounts. Requires an {@code Idempotency-Key}
 * header: the same key with the same body is executed once and answered with the stored response every time.
 */
@RestController
@RequestMapping("/api/deposits")
public class DepositController {

    private final DepositService depositService;
    private final IdempotentExecutor idempotentExecutor;
    private final CurrentUserProvider currentUser;

    public DepositController(
            DepositService depositService, IdempotentExecutor idempotentExecutor, CurrentUserProvider currentUser) {
        this.depositService = depositService;
        this.idempotentExecutor = idempotentExecutor;
        this.currentUser = currentUser;
    }

    /**
     * The header is read as optional here so that a missing value gets the same 400 problem+json as an invalid one;
     * the executor applies the validation rules. The lambda is the business call, run by the executor inside its
     * transaction. The controller stays thin: it maps the DTO to a command and the result to a response DTO.
     */
    @PostMapping
    public ResponseEntity<String> deposit(
            @RequestHeader(value = IdempotentResponses.KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody DepositRequest request,
            HttpServletRequest http) {
        DepositCommand command =
                new DepositCommand(request.accountId(), request.amountMinor(), request.currency(), request.reference());
        var actor = currentUser.current();
        return IdempotentResponses.toResponse(idempotentExecutor.execute(
                actor, idempotencyKey, http.getMethod(), http.getRequestURI(), request, () -> {
                    PostedTransaction posted = depositService.deposit(actor, command);
                    return new IdempotentOutcome(
                            HttpStatus.CREATED.value(), TransactionResponse.from(posted), posted.transaction().getId());
                }));
    }
}
