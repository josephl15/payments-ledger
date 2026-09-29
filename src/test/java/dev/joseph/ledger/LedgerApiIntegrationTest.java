package dev.joseph.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.joseph.ledger.config.LedgerProperties;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The HTTP layer end to end (real controllers, real Jackson and validation, real PostgreSQL): status codes, the
 * RFC 7807 problem+json shape, and the amount coercion rules.
 *
 * <p>MockMvc calls the controllers through Spring MVC without opening a socket, which keeps the tests fast while
 * still exercising the message converters and the exception handler.
 */
@AutoConfigureMockMvc
class LedgerApiIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String ACTING_USER = "X-Acting-User-Id";
    private static final String PROBLEM_JSON = "application/problem+json";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    @Autowired
    LedgerProperties properties;

    private UUID newUser() {
        return new LedgerTestData(jdbc).newUser();
    }

    private ResultActions send(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request);
    }

    private UUID openAccount(UUID user) throws Exception {
        String body = send(post("/api/accounts")
                        .header(ACTING_USER, user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Main\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(json.readTree(body).get("id").asText());
    }

    /** Deposits and transfers require an Idempotency-Key (Phase 5); a fresh one per call, so calls never replay each other. */
    private ResultActions postJson(String path, UUID user, String body) throws Exception {
        return send(post(path)
                .header(ACTING_USER, user)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private void deposit(UUID user, UUID account, long amount) throws Exception {
        postJson("/api/deposits", user, depositBody(account, amount)).andExpect(status().isCreated());
    }

    private static String depositBody(UUID account, Object amount) {
        return "{\"accountId\":\"" + account + "\",\"amountMinor\":" + amount + ",\"currency\":\"GBP\"}";
    }

    private static String transferBody(UUID from, UUID to, Object amount) {
        return "{\"fromAccountId\":\"" + from + "\",\"toAccountId\":\"" + to + "\",\"amountMinor\":" + amount
                + ",\"currency\":\"GBP\",\"reference\":\"rent\"}";
    }

    private ResultActions expectProblem(ResultActions result, int status) throws Exception {
        return result.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.title").isNotEmpty());
    }

    // ---------------------------------------------------------------- accounts

    @Test
    void openListAndViewAccounts() throws Exception {
        UUID user = newUser();
        UUID other = newUser();
        UUID account = openAccount(user);
        openAccount(other);

        send(post("/api/accounts")
                        .header(ACTING_USER, user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Savings\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/accounts/")))
                .andExpect(jsonPath("$.name").value("Savings"))
                .andExpect(jsonPath("$.currency").value("GBP"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.balanceMinor").value(0));

        send(get("/api/accounts").header(ACTING_USER, user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        send(get("/api/accounts/" + account).header(ACTING_USER, user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(account.toString()))
                .andExpect(jsonPath("$.balanceMinor").value(0));
    }

    @Test
    void anotherUsersAccountAndUnknownAccountAreBothNotFound() throws Exception {
        UUID owner = newUser();
        UUID intruder = newUser();
        UUID account = openAccount(owner);

        expectProblem(send(get("/api/accounts/" + account).header(ACTING_USER, intruder)), 404);
        expectProblem(send(get("/api/accounts/" + UUID.randomUUID()).header(ACTING_USER, owner)), 404);
    }

    @Test
    void openingAnAccountValidatesTheName() throws Exception {
        UUID user = newUser();

        expectProblem(postJson("/api/accounts", user, "{\"name\":\"\"}"), 400)
                .andExpect(jsonPath("$.errors[0].field").value("name"));
        expectProblem(postJson("/api/accounts", user, "{}"), 400);
        expectProblem(postJson("/api/accounts", user, "{\"name\":\"" + "x".repeat(101) + "\"}"), 400);
    }

    @Test
    void missingOrMalformedActingUserHeaderIsBadRequest() throws Exception {
        expectProblem(send(get("/api/accounts")), 400);
        expectProblem(send(get("/api/accounts").header(ACTING_USER, "not-a-uuid")), 400);
    }

    @Test
    void anActingUserWhoDoesNotExistCannotOpenAnAccount() throws Exception {
        expectProblem(postJson("/api/accounts", UUID.randomUUID(), "{\"name\":\"Main\"}"), 404);
    }

    // ---------------------------------------------------------------- deposits and transfers

    @Test
    void depositThenTransferReturnsBalancedTransactionsAndUpdatesBalances() throws Exception {
        UUID user = newUser();
        UUID from = openAccount(user);
        UUID to = openAccount(newUser());

        postJson("/api/deposits", user, depositBody(from, 10_000))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("DEPOSIT"))
                .andExpect(jsonPath("$.entries", hasSize(2)))
                .andExpect(jsonPath("$.entries[?(@.amountMinor == -10000)].accountId")
                        .value(org.hamcrest.Matchers.hasItem("00000000-0000-0000-0000-000000000001")));

        String body = postJson("/api/transfers", user, transferBody(from, to, 2_500))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("TRANSFER"))
                .andExpect(jsonPath("$.reference").value("rent"))
                .andExpect(jsonPath("$.entries", hasSize(2)))
                .andReturn()
                .getResponse()
                .getContentAsString();
        long sum = 0;
        for (JsonNode entry : json.readTree(body).get("entries")) {
            sum += entry.get("amountMinor").asLong();
        }
        assertThat(sum).isZero();

        send(get("/api/accounts/" + from).header(ACTING_USER, user)).andExpect(jsonPath("$.balanceMinor").value(7_500));
        assertThat(jdbc.queryForObject("SELECT balance_minor FROM accounts WHERE id = ?", Long.class, to))
                .isEqualTo(2_500L);
    }

    @Test
    void insufficientFundsIsProblemJson422() throws Exception {
        UUID user = newUser();
        UUID from = openAccount(user);
        UUID to = openAccount(user);
        deposit(user, from, 100);

        expectProblem(postJson("/api/transfers", user, transferBody(from, to, 101)), 422)
                .andExpect(jsonPath("$.title").value("Insufficient funds"));
    }

    @Test
    void transferFromSomeoneElsesAccountOrToAnUnknownAccountIsNotFound() throws Exception {
        UUID alice = newUser();
        UUID bob = newUser();
        UUID aliceAccount = openAccount(alice);
        UUID bobAccount = openAccount(bob);
        deposit(bob, bobAccount, 1_000);

        expectProblem(postJson("/api/transfers", alice, transferBody(bobAccount, aliceAccount, 10)), 404);
        expectProblem(postJson("/api/transfers", bob, transferBody(bobAccount, UUID.randomUUID(), 10)), 404);
    }

    @Test
    void sameFromAndToIsBadRequest() throws Exception {
        UUID user = newUser();
        UUID account = openAccount(user);
        deposit(user, account, 100);

        expectProblem(postJson("/api/transfers", user, transferBody(account, account, 10)), 400);
    }

    @Test
    void closedAccountIsUnprocessable() throws Exception {
        UUID user = newUser();
        UUID from = openAccount(user);
        UUID to = openAccount(user);
        deposit(user, from, 100);
        jdbc.update("UPDATE accounts SET status = 'CLOSED' WHERE id = ?", to);

        expectProblem(postJson("/api/transfers", user, transferBody(from, to, 10)), 422);
    }

    // ---------------------------------------------------------------- amount coercion and validation

    @Test
    void invalidAmountsAreRejectedWithProblemJson400() throws Exception {
        UUID user = newUser();
        UUID from = openAccount(user);
        UUID to = openAccount(user);
        deposit(user, from, 1_000);

        Object[] badAmounts = {
            0, -1, -3000,
            "30.9", // decimal: Jackson must not truncate it to 30
            "1e2", // exponent form is a floating-point number too
            "100.0",
            "\"3000\"", // a string must not be coerced to a number
            "null",
            "true",
            properties.maxAmountMinor() + 1, // above the configured maximum
            Long.MAX_VALUE,
            "9223372036854775808", // one more than Long.MAX_VALUE: overflows a long
        };
        for (Object amount : badAmounts) {
            expectProblem(postJson("/api/transfers", user, transferBody(from, to, amount)), 400);
            expectProblem(postJson("/api/deposits", user, depositBody(from, amount)), 400);
        }
        // A missing amount must be rejected, not treated as 0.
        expectProblem(
                postJson(
                        "/api/transfers",
                        user,
                        "{\"fromAccountId\":\"" + from + "\",\"toAccountId\":\"" + to + "\",\"currency\":\"GBP\"}"),
                400);

        // None of that moved any money.
        assertThat(jdbc.queryForObject("SELECT balance_minor FROM accounts WHERE id = ?", Long.class, from))
                .isEqualTo(1_000L);
        assertThat(jdbc.queryForObject("SELECT balance_minor FROM accounts WHERE id = ?", Long.class, to)).isZero();
    }

    @Test
    void malformedBodiesAndBadFieldsAreBadRequests() throws Exception {
        UUID user = newUser();
        UUID from = openAccount(user);
        UUID to = openAccount(user);

        expectProblem(postJson("/api/transfers", user, "{not json"), 400);
        expectProblem(postJson("/api/transfers", user, ""), 400);
        expectProblem(
                postJson(
                        "/api/transfers",
                        user,
                        "{\"fromAccountId\":\"nope\",\"toAccountId\":\"" + to + "\",\"amountMinor\":1,\"currency\":\"GBP\"}"),
                400);
        expectProblem(
                postJson(
                        "/api/transfers",
                        user,
                        "{\"fromAccountId\":\"" + from + "\",\"toAccountId\":\"" + to
                                + "\",\"amountMinor\":1,\"currency\":\"gbp\"}"),
                400);
        // A supported-looking but unsupported currency is a service-level 400.
        expectProblem(
                postJson(
                        "/api/transfers",
                        user,
                        "{\"fromAccountId\":\"" + from + "\",\"toAccountId\":\"" + to
                                + "\",\"amountMinor\":1,\"currency\":\"USD\"}"),
                400);
    }

    @Test
    void validationErrorBodyListsFieldsButNeverEchoesValues() throws Exception {
        UUID user = newUser();

        String body = postJson("/api/deposits", user, "{\"accountId\":null,\"amountMinor\":-5,\"currency\":\"GBP\"}")
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode errors = json.readTree(body).get("errors");
        assertThat(errors).isNotNull();
        assertThat(body).contains("accountId").contains("amountMinor");
        assertThat(body).doesNotContain("-5");
    }
}
