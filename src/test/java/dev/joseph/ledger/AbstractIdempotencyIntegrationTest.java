package dev.joseph.ledger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.joseph.ledger.service.AccountService;
import dev.joseph.ledger.service.ActingUser;
import dev.joseph.ledger.service.DepositCommand;
import dev.joseph.ledger.service.DepositService;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Shared set-up for the idempotency tests. The idempotency tests share one Spring context (and so one connection
 * pool) whose {@code Clock} is a {@link MutableClock}, so the expiry test can move time without a second context.
 *
 * <p>Test data follows the project rule (docs/DECISIONS.md, entry 16): every test creates its own users and accounts
 * and asserts only on them. Accounts are funded through the real deposit service, so the ledger entries and the
 * cached balances agree and reconciliation can be asserted clean.
 */
@AutoConfigureMockMvc
@Import(AbstractIdempotencyIntegrationTest.ClockConfig.class)
abstract class AbstractIdempotencyIntegrationTest extends AbstractPostgresIntegrationTest {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String PROBLEM_JSON = "application/problem+json";

    /** Replaces the application Clock for this context only. {@code @Primary} makes it win over the real one. */
    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    @Autowired
    MutableClock clock;

    @Autowired
    AccountService accountService;

    @Autowired
    DepositService depositService;

    private AuthTestClient authClient;

    /** A real user, registered and logged in through the API (POST /api/auth/register and /login). */
    UUID newUser() {
        if (authClient == null) {
            authClient = new AuthTestClient(mvc, json);
        }
        return authClient.newUser();
    }

    /** The raw token of a user created with {@link #newUser()}. */
    String token(UUID user) {
        return authClient.token(user);
    }

    /** The Authorization header value ("Bearer <token>") of a user created with {@link #newUser()}. */
    String bearer(UUID user) {
        return authClient.bearer(user);
    }

    /** A new account for the user, funded with {@code balanceMinor} through the real deposit service (0 = unfunded). */
    UUID newAccount(UUID user, long balanceMinor) {
        ActingUser actor = new ActingUser(user);
        UUID id = accountService.open(actor, "idem " + UUID.randomUUID()).getId();
        if (balanceMinor > 0) {
            depositService.deposit(actor, new DepositCommand(id, balanceMinor, "GBP", null));
        }
        return id;
    }

    static String depositBody(UUID account, long amount) {
        return "{\"accountId\":\"" + account + "\",\"amountMinor\":" + amount + ",\"currency\":\"GBP\"}";
    }

    static String transferBody(UUID from, UUID to, long amount) {
        return "{\"fromAccountId\":\"" + from + "\",\"toAccountId\":\"" + to + "\",\"amountMinor\":" + amount
                + ",\"currency\":\"GBP\"}";
    }

    /** POSTs with the given Idempotency-Key ({@code null} = header not sent). */
    MvcResult send(String path, UUID user, String key, String body) throws Exception {
        var request = post(path).header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) {
            request = request.header(IDEMPOTENCY_KEY, key);
        }
        return mvc.perform(request).andReturn();
    }

    JsonNode parse(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    long balance(UUID account) {
        return jdbc.queryForObject("SELECT balance_minor FROM accounts WHERE id = ?", Long.class, account);
    }

    /** Ledger transactions of one type created by the user (tests use their own users, so this is exact). */
    long transactionCount(UUID user, String type) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM ledger_transactions WHERE created_by_user_id = ? AND type = ?",
                Long.class,
                user,
                type);
    }

    long keyRowCount(UUID user, String key) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM idempotency_keys WHERE user_id = ? AND idem_key = ?", Long.class, user, key);
    }
}
