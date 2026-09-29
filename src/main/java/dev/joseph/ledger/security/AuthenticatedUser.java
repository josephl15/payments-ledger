package dev.joseph.ledger.security;

import dev.joseph.ledger.domain.Role;
import java.util.UUID;

/** The principal stored in the security context after a token has been verified: who is calling, and their role. */
public record AuthenticatedUser(UUID userId, Role role) {}
