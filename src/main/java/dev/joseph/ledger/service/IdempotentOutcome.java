package dev.joseph.ledger.service;

import java.util.UUID;

/**
 * What the wrapped business call produced: the HTTP status to answer with, the response object (turned into JSON by
 * the executor and stored on the key row), and the id of the ledger transaction it created.
 */
public record IdempotentOutcome(int status, Object body, UUID transactionId) {}
