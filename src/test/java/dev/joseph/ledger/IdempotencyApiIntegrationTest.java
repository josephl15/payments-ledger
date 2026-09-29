package dev.joseph.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Idempotency through the HTTP layer against real PostgreSQL: retries, mismatches, header rules and what a failed
 * first attempt leaves behind. Sequential; the concurrent case is ConcurrentIdempotencyIntegrationTest.
 */
class IdempotencyApiIntegrationTest extends AbstractIdempotencyIntegrationTest {

    private static final String REPLAYED = "Idempotent-Replayed";

    @AfterEach
    void putTheClockBack() {
        clock.reset();
    }

    // ------------------------------------------------------------------ retries

    @Test
    void depositRetryWithSameKeyAndBodyExecutesOnceAndReturnsTheStoredResponse() throws Exception {
        UUID user = newUser();
        UUID account = newAccount(user, 0);
        String key = "deposit-" + UUID.randomUUID();

        MvcResult first = send("/api/deposits", user, key, depositBody(account, 2_500));
        MvcResult second = send("/api/deposits", user, key, depositBody(account, 2_500));
        MvcResult third = send("/api/deposits", user, key, depositBody(account, 2_500));

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(first.getResponse().getHeader(REPLAYED)).isNull();
        for (MvcResult retry : new MvcResult[] {second, third}) {
            assertThat(retry.getResponse().getStatus()).isEqualTo(201);
            assertThat(retry.getResponse().getHeader(REPLAYED)).isEqualTo("true");
            // Compared as JSON content: the stored copy is JSONB, which reorders keys and changes spacing.
            assertThat(parse(retry)).isEqualTo(parse(first));
        }
        assertThat(retry(second)).isEqualTo(retry(third));
        assertThat(transactionCount(user, "DEPOSIT")).as("one deposit, not three").isEqualTo(1);
        assertThat(balance(account)).isEqualTo(2_500);
        assertThat(keyRowCount(user, key)).isEqualTo(1);
    }

    /** Two replays are read from the same stored value, so even their text is identical. */
    private String retry(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    @Test
    void transferRetryWithSameKeyAndBodyMovesMoneyOnce() throws Exception {
        UUID user = newUser();
        UUID from = newAccount(user, 10_000);
        UUID to = newAccount(newUser(), 0);
        String key = UUID.randomUUID().toString();

        MvcResult first = send("/api/transfers", user, key, transferBody(from, to, 3_000));
        MvcResult second = send("/api/transfers", user, key, transferBody(from, to, 3_000));

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(second.getResponse().getStatus()).isEqualTo(201);
        assertThat(second.getResponse().getHeader(REPLAYED)).isEqualTo("true");
        assertThat(parse(second)).isEqualTo(parse(first));
        assertThat(transactionCount(user, "TRANSFER")).isEqualTo(1);
        assertThat(balance(from)).isEqualTo(7_000);
        assertThat(balance(to)).isEqualTo(3_000);
    }

    @Test
    void theKeyRowStoresHashStatusBodyTransactionAndTheConfiguredExpiry() throws Exception {
        UUID user = newUser();
        UUID account = newAccount(user, 0);
        String key = "row-" + UUID.randomUUID();
        JsonNode body = parse(send("/api/deposits", user, key, depositBody(account, 100)));

        var row = jdbc.queryForMap(
                "SELECT request_hash, response_status, response_body ->> 'id' AS body_id, transaction_id, created_at, expires_at "
                        + "FROM idempotency_keys WHERE user_id = ? AND idem_key = ?",
                user,
                key);

        assertThat((String) row.get("request_hash")).matches("[0-9a-f]{64}");
        assertThat(row.get("response_status")).isEqualTo(201);
        assertThat(row.get("body_id")).isEqualTo(body.get("id").asText());
        assertThat(row.get("transaction_id")).isEqualTo(UUID.fromString(body.get("id").asText()));
        Instant created = ((java.sql.Timestamp) row.get("created_at")).toInstant();
        Instant expires = ((java.sql.Timestamp) row.get("expires_at")).toInstant();
        assertThat(Duration.between(created, expires)).as("default TTL").isEqualTo(Duration.ofHours(24));
    }

    @Test
    void reorderedJsonFieldsWithTheSameKeyAreTheSameRequest() throws Exception {
        UUID user = newUser();
        UUID account = newAccount(user, 0);
        String key = "order-" + UUID.randomUUID();
        String normal = depositBody(account, 700);
        String shuffled = "{ \"currency\": \"GBP\", \"amountMinor\": 700,\n  \"accountId\": \"" + account + "\" }";

        MvcResult first = send("/api/deposits", user, key, normal);
        MvcResult second = send("/api/deposits", user, key, shuffled);

        assertThat(second.getResponse().getStatus()).isEqualTo(201);
        assertThat(second.getResponse().getHeader(REPLAYED)).isEqualTo("true");
        assertThat(parse(second)).isEqualTo(parse(first));
        assertThat(balance(account)).isEqualTo(700);
    }

    // ------------------------------------------------------------------ mismatches

    @Test
    void sameKeyWithADifferentBodyIsUnprocessableAndMovesNothing() throws Exception {
        UUID user = newUser();
        UUID account = newAccount(user, 0);
        String key = "mismatch-" + UUID.randomUUID();
        send("/api/deposits", user, key, depositBody(account, 1_000));

        MvcResult different = send("/api/deposits", user, key, depositBody(account, 1_001));

        assertThat(different.getResponse().getStatus()).isEqualTo(422);
        assertThat(different.getResponse().getContentType()).startsWith(PROBLEM_JSON);
        assertThat(parse(different).get("title").asText()).isEqualTo("Idempotency key reused");
        assertThat(transactionCount(user, "DEPOSIT")).isEqualTo(1);
        assertThat(balance(account)).isEqualTo(1_000);
    }

    @Test
    void sameKeyOnAnotherEndpointIsUnprocessableAndNeverReplaysTheOtherResponse() throws Exception {
        UUID user = newUser();
        UUID from = newAccount(user, 5_000);
        UUID to = newAccount(user, 0);
        String key = "cross-" + UUID.randomUUID();
        send("/api/deposits", user, key, depositBody(from, 1_000));

        MvcResult transfer = send("/api/transfers", user, key, transferBody(from, to, 1_000));

        assertThat(transfer.getResponse().getStatus()).isEqualTo(422);
        assertThat(transfer.getResponse().getHeader(REPLAYED)).isNull();
        assertThat(transactionCount(user, "TRANSFER")).isZero();
        assertThat(balance(to)).isZero();
    }

    @Test
    void differentUsersMayUseTheSameKeyText() throws Exception {
        UUID alice = newUser();
        UUID bob = newUser();
        UUID aliceAccount = newAccount(alice, 0);
        UUID bobAccount = newAccount(bob, 0);
        String key = "shared-" + UUID.randomUUID();

        MvcResult a = send("/api/deposits", alice, key, depositBody(aliceAccount, 100));
        MvcResult b = send("/api/deposits", bob, key, depositBody(bobAccount, 100));

        assertThat(a.getResponse().getStatus()).isEqualTo(201);
        assertThat(b.getResponse().getStatus()).isEqualTo(201);
        assertThat(a.getResponse().getHeader(REPLAYED)).isNull();
        assertThat(b.getResponse().getHeader(REPLAYED)).as("Bob's request is not Alice's replay").isNull();
        assertThat(parse(a).get("id")).isNotEqualTo(parse(b).get("id"));
        assertThat(balance(aliceAccount)).isEqualTo(100);
        assertThat(balance(bobAccount)).isEqualTo(100);
    }

    // ------------------------------------------------------------------ failed first attempts

    @Test
    void aFailedFirstAttemptIsNotRememberedSoARetryRunsAgain() throws Exception {
        UUID user = newUser();
        UUID from = newAccount(user, 500);
        UUID to = newAccount(user, 0);
        String key = "retry-after-failure-" + UUID.randomUUID();

        MvcResult refused = send("/api/transfers", user, key, transferBody(from, to, 800));
        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        assertThat(parse(refused).get("title").asText()).isEqualTo("Insufficient funds");
        assertThat(keyRowCount(user, key)).as("the key row rolled back with the failed attempt").isZero();

        // Now the account is topped up and the client retries with the SAME key and body.
        send("/api/deposits", user, "topup-" + UUID.randomUUID(), depositBody(from, 500));
        MvcResult retried = send("/api/transfers", user, key, transferBody(from, to, 800));

        assertThat(retried.getResponse().getStatus()).as("re-executed, not a replayed 422").isEqualTo(201);
        assertThat(retried.getResponse().getHeader(REPLAYED)).isNull();
        assertThat(balance(from)).isEqualTo(200);
        assertThat(balance(to)).isEqualTo(800);
        assertThat(keyRowCount(user, key)).isEqualTo(1);
    }

    @Test
    void anUnknownAccountLeavesNoKeyRowEither() throws Exception {
        UUID user = newUser();
        String key = "ghost-" + UUID.randomUUID();

        MvcResult result = send("/api/deposits", user, key, depositBody(UUID.randomUUID(), 100));

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(keyRowCount(user, key)).isZero();
    }

    // ------------------------------------------------------------------ header rules

    @Test
    void aMissingHeaderIsBadRequestOnBothEndpointsAndMovesNothing() throws Exception {
        UUID user = newUser();
        UUID from = newAccount(user, 1_000);
        UUID to = newAccount(user, 0);

        MvcResult deposit = send("/api/deposits", user, null, depositBody(from, 100));
        MvcResult transfer = send("/api/transfers", user, null, transferBody(from, to, 100));

        for (MvcResult result : new MvcResult[] {deposit, transfer}) {
            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(result.getResponse().getContentType()).startsWith(PROBLEM_JSON);
            assertThat(parse(result).get("detail").asText()).contains("Idempotency-Key");
        }
        assertThat(balance(from)).isEqualTo(1_000);
        assertThat(balance(to)).isZero();
    }

    @Test
    void blankOversizedAndBadCharsetKeysAreBadRequests() throws Exception {
        UUID user = newUser();
        UUID account = newAccount(user, 0);
        String[] badKeys = {"", "   ", "x".repeat(129), "has space", "semi;colon", "quote\"mark", "unicodé"};

        for (String bad : badKeys) {
            MvcResult result = send("/api/deposits", user, bad, depositBody(account, 100));
            assertThat(result.getResponse().getStatus()).as("key of length %d", bad.length()).isEqualTo(400);
            assertThat(result.getResponse().getContentType()).startsWith(PROBLEM_JSON);
        }
        assertThat(balance(account)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_keys WHERE user_id = ?", Long.class, user))
                .isZero();
    }

    @Test
    void aKeyOfExactlyTheMaximumLengthIsAccepted() throws Exception {
        UUID user = newUser();
        UUID account = newAccount(user, 0);

        MvcResult result = send("/api/deposits", user, "k".repeat(128), depositBody(account, 100));

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
    }
}
