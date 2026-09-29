package dev.joseph.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import dev.joseph.ledger.config.DevStubUserSeeder;
import dev.joseph.ledger.service.AccountService;
import dev.joseph.ledger.service.ActingUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** With the dev profile active the stub user exists, so the header-based API can be tried by hand. */
@ActiveProfiles("dev")
class DevStubUserSeederIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AccountService accountService;

    @Test
    void devProfileCreatesTheStubUserAndItCanOpenAnAccount() {
        Integer users = jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE id = ?", Integer.class, DevStubUserSeeder.DEV_USER_ID);
        assertThat(users).isEqualTo(1);

        assertThat(accountService
                        .open(new ActingUser(DevStubUserSeeder.DEV_USER_ID), "dev account")
                        .getOwnerUserId())
                .isEqualTo(DevStubUserSeeder.DEV_USER_ID);
    }
}
