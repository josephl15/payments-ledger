package dev.joseph.ledger.api;

import dev.joseph.ledger.domain.Account;
import java.time.Instant;
import java.util.UUID;

/** An account as returned by the API. {@code balanceMinor} is the cached balance in pence. */
public record AccountResponse(
        UUID id, String name, String currency, String status, long balanceMinor, Instant createdAt) {

    static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getName(),
                account.getCurrency(),
                account.getStatus().name(),
                account.getBalanceMinor(),
                account.getCreatedAt());
    }
}
