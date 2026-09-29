package dev.joseph.ledger.api;

import dev.joseph.ledger.domain.User;
import java.util.UUID;

/** The new user as returned by registration. There is no password or hash field, by design. */
public record RegisterResponse(UUID id, String username, String role) {

    static RegisterResponse from(User user) {
        return new RegisterResponse(user.getId(), user.getUsername(), user.getRole().name());
    }
}
