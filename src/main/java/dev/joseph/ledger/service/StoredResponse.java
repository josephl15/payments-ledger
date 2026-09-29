package dev.joseph.ledger.service;

/**
 * The response a client gets: status plus JSON text. {@code replayed} is true when it was read back from the key row
 * (the request had been seen before) and false when this call did the work.
 */
public record StoredResponse(int status, String bodyJson, boolean replayed) {}
