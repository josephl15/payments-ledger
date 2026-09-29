package dev.joseph.ledger.service;

import java.util.UUID;

/**
 * Who is performing the request. Services receive it as a parameter and never read a security context, so the
 * business code has no dependency on Spring Security. It is built from the verified JWT by
 * {@code security.CurrentUserProvider}; in a plain service test it is simply {@code new ActingUser(id)}.
 */
public record ActingUser(UUID userId) {}
