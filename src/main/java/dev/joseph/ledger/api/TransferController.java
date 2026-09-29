package dev.joseph.ledger.api;

import dev.joseph.ledger.security.CurrentUserProvider;
import dev.joseph.ledger.service.IdempotentExecutor;
import dev.joseph.ledger.service.IdempotentOutcome;
import dev.joseph.ledger.service.PostedTransaction;
import dev.joseph.ledger.service.TransferCommand;
import dev.joseph.ledger.service.TransferService;
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
 * POST /api/transfers: move money from one of the caller's accounts to another customer account. Requires an
 * {@code Idempotency-Key} header, exactly like deposits (see {@link DepositController}).
 */
@RestController
@RequestMapping("/api/transfers")
public class TransferController {

    private final TransferService transferService;
    private final IdempotentExecutor idempotentExecutor;
    private final CurrentUserProvider currentUser;

    public TransferController(
            TransferService transferService, IdempotentExecutor idempotentExecutor, CurrentUserProvider currentUser) {
        this.transferService = transferService;
        this.idempotentExecutor = idempotentExecutor;
        this.currentUser = currentUser;
    }

    @PostMapping
    public ResponseEntity<String> transfer(
            @RequestHeader(value = IdempotentResponses.KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody TransferRequest request,
            HttpServletRequest http) {
        TransferCommand command = new TransferCommand(
                request.fromAccountId(),
                request.toAccountId(),
                request.amountMinor(),
                request.currency(),
                request.reference());
        var actor = currentUser.current();
        return IdempotentResponses.toResponse(idempotentExecutor.execute(
                actor, idempotencyKey, http.getMethod(), http.getRequestURI(), request, () -> {
                    PostedTransaction posted = transferService.transfer(actor, command);
                    return new IdempotentOutcome(
                            HttpStatus.CREATED.value(), TransactionResponse.from(posted), posted.transaction().getId());
                }));
    }
}
