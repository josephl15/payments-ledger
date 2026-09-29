package dev.joseph.ledger.api;

import dev.joseph.ledger.service.ActingUser;
import java.util.UUID;

/**
 * TODO(Phase 6): delete this class. It is a STUB that trusts the caller.
 *
 * <p>Until login and JWT exist, the acting user is whatever id the client puts in the X-Acting-User-Id header, and
 * that user must already exist in the users table (in the dev profile see DevStubUserSeeder; tests create their own).
 * Anyone can send any id, so nothing built before Phase 6 is secured. In Phase 6 the controllers will build the
 * {@link ActingUser} from the authenticated JWT instead, and no service changes.
 */
final class StubActingUser {

    static final String HEADER = "X-Acting-User-Id";

    private StubActingUser() {}

    static ActingUser from(UUID headerValue) {
        return new ActingUser(headerValue);
    }
}
