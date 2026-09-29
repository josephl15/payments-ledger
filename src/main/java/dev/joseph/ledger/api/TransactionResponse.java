package dev.joseph.ledger.api;

import dev.joseph.ledger.domain.LedgerEntry;
import dev.joseph.ledger.service.PostedTransaction;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A posted transaction with its entries (negative amount = debit, positive = credit). */
public record TransactionResponse(
        UUID id, String type, String reference, Instant createdAt, List<EntryResponse> entries) {

    /** One line of the transaction. */
    public record EntryResponse(UUID accountId, long amountMinor, String currency) {}

    static TransactionResponse from(PostedTransaction posted) {
        List<EntryResponse> lines = posted.entries().stream()
                .map(TransactionResponse::toEntry)
                .toList();
        return new TransactionResponse(
                posted.transaction().getId(),
                posted.transaction().getType().name(),
                posted.transaction().getReference(),
                posted.transaction().getCreatedAt(),
                lines);
    }

    private static EntryResponse toEntry(LedgerEntry entry) {
        return new EntryResponse(entry.getAccountId(), entry.getAmountMinor(), entry.getCurrency());
    }
}
