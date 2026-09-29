package dev.joseph.ledger.service;

import java.util.UUID;

/** Input to {@link DepositService}: a plain record, so the service does not depend on the HTTP layer's DTOs. */
public record DepositCommand(UUID accountId, long amountMinor, String currency, String reference) {}
