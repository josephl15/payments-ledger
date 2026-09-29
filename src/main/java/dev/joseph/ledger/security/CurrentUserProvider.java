package dev.joseph.ledger.security;

import dev.joseph.ledger.service.ActingUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The one place that turns "who is logged in" (Spring Security's context) into an {@link ActingUser}, the plain
 * record the services take as a parameter. Services never touch Spring Security, so they stay easy to test.
 */
@Component
public class CurrentUserProvider {

    /** The caller of the current request. Only call this from a URL that requires authentication. */
    public ActingUser current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            // Cannot happen behind SecurityConfig (protected URLs are refused before reaching a controller).
            throw new IllegalStateException("No authenticated user in the security context");
        }
        return new ActingUser(user.userId());
    }
}
