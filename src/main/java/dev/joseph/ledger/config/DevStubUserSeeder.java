package dev.joseph.ledger.config;

import java.util.UUID;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * TODO(Phase 6): delete this class when real registration and login exist.
 *
 * <p>Until then the API identifies the caller with the X-Acting-User-Id header, and that user must exist in the
 * users table. This runner creates one such user, but only when the app runs with the {@code dev} profile
 * (SPRING_PROFILES_ACTIVE=dev). It is a fixture, not a migration, so nothing seeded ever ships to a real database.
 * Tests create their own users and do not use it.
 */
@Component
@Profile("dev")
public class DevStubUserSeeder implements CommandLineRunner {

    /** The id to send in X-Acting-User-Id when trying the API by hand in the dev profile. */
    public static final UUID DEV_USER_ID = UUID.fromString("00000000-0000-0000-0000-00000000d001");

    private final JdbcTemplate jdbc;

    public DevStubUserSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) {
        // ON CONFLICT DO NOTHING makes this safe to run on every start.
        jdbc.update(
                "INSERT INTO users (id, username, password_hash, role) VALUES (?, 'dev-user', 'not-a-real-hash', 'USER') "
                        + "ON CONFLICT DO NOTHING",
                DEV_USER_ID);
    }
}
