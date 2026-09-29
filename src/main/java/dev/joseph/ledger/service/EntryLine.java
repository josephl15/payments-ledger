package dev.joseph.ledger.service;

import java.util.UUID;

/** One line to post: a signed amount in pence on one account (negative = debit, positive = credit). */
public record EntryLine(UUID accountId, long amountMinor) {}
