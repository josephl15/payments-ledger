package dev.joseph.ledger.api;

import dev.joseph.ledger.service.TransferCommand;
import dev.joseph.ledger.service.TransferService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** POST /api/transfers: move money from one of the caller's accounts to another customer account. */
@RestController
@RequestMapping("/api/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResponse transfer(
            @RequestHeader(StubActingUser.HEADER) UUID actingUserId, @Valid @RequestBody TransferRequest request) {
        TransferCommand command = new TransferCommand(
                request.fromAccountId(),
                request.toAccountId(),
                request.amountMinor(),
                request.currency(),
                request.reference());
        return TransactionResponse.from(transferService.transfer(StubActingUser.from(actingUserId), command));
    }
}
