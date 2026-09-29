package dev.joseph.ledger.service;

import java.util.UUID;

/** Input to {@link TransferService}: a plain record, so the service does not depend on the HTTP layer's DTOs. */
public record TransferCommand(
        UUID fromAccountId, UUID toAccountId, long amountMinor, String currency, String reference) {}
