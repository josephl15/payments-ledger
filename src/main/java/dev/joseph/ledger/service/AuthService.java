package dev.joseph.ledger.service;

import dev.joseph.ledger.domain.Role;
import dev.joseph.ledger.domain.User;
import dev.joseph.ledger.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration and credential checking. Issuing the token is not done here (that is the security package's job);
 * this class only decides "is this username free" and "is this password right".
 *
 * <p>Logs contain user ids and outcomes, never usernames-with-passwords, request bodies or tokens.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** BCrypt only reads the first 72 bytes of a password; longer input is refused instead of silently cut. */
    private static final int BCRYPT_MAX_BYTES = 72;

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    /** A real BCrypt hash of a throwaway string, compared against when the username is unknown (see login). */
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode("not-a-real-password");
    }

    /** Creates a USER with a BCrypt-hashed password. Usernames are case-insensitive: stored lower-case. */
    @Transactional
    public User register(String username, String rawPassword) {
        if (rawPassword.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
            throw new InvalidRequestException("Password must be at most " + BCRYPT_MAX_BYTES + " bytes");
        }
        String normalised = normalise(username);
        if (users.existsByUsername(normalised)) {
            throw new DuplicateUsernameException();
        }
        User user = new User(normalised, passwordEncoder.encode(rawPassword), Role.USER, clock.instant());
        try {
            // Flush now. Two simultaneous registrations can both pass the exists check above; the database unique
            // constraint uq_users_username is the real guarantee and rejects the loser here.
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateUsernameException();
        }
        log.info("User registered id={}", user.getId());
        return user;
    }

    /** The user if the password matches; otherwise {@link InvalidCredentialsException}, identical for both failures. */
    @Transactional(readOnly = true)
    public User authenticate(String username, String rawPassword) {
        if (rawPassword.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
            // No registered password can be this long, so it cannot match; same answer as any wrong password.
            throw new InvalidCredentialsException();
        }
        User user = users.findByUsername(normalise(username)).orElse(null);
        // BCrypt is slow on purpose. If an unknown username skipped the comparison the response would come back
        // measurably faster and reveal which usernames exist, so a comparison against a dummy hash is always made.
        String hash = user == null ? dummyHash : user.getPasswordHash();
        boolean matches = passwordEncoder.matches(rawPassword, hash);
        if (user == null || !matches) {
            log.info("Login rejected");
            throw new InvalidCredentialsException();
        }
        log.info("Login accepted id={}", user.getId());
        return user;
    }

    private static String normalise(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }
}
