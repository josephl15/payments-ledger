package dev.joseph.ledger.service;

import dev.joseph.ledger.domain.LedgerEntry;
import dev.joseph.ledger.domain.LedgerTransaction;
import java.util.List;

/** The result of posting: the transaction row and the entries that were written for it. */
public record PostedTransaction(LedgerTransaction transaction, List<LedgerEntry> entries) {}
