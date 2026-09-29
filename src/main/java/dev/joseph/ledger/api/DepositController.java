package dev.joseph.ledger.api;

import dev.joseph.ledger.service.DepositCommand;
import dev.joseph.ledger.service.DepositService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** POST /api/deposits: a simulated deposit into one of the caller's own accounts. */
@RestController
@RequestMapping("/api/deposits")
public class DepositController {

    private final DepositService depositService;

    public DepositController(DepositService depositService) {
        this.depositService = depositService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResponse deposit(
            @RequestHeader(StubActingUser.HEADER) UUID actingUserId, @Valid @RequestBody DepositRequest request) {
        DepositCommand command =
                new DepositCommand(request.accountId(), request.amountMinor(), request.currency(), request.reference());
        return TransactionResponse.from(depositService.deposit(StubActingUser.from(actingUserId), command));
    }
}
