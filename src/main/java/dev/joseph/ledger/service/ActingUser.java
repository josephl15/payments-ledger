package dev.joseph.ledger.service;

import java.util.UUID;

/**
 * Who is performing the request. Services receive it as a parameter and never read a security context, so the
 * business code does not change when real authentication arrives in Phase 6: only the code that builds this record
 * does (today a header-based stub in the api package).
 */
public record ActingUser(UUID userId) {}
