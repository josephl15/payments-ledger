package dev.joseph.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Key expiry, driven by a controllable Clock instead of sleeping. The default TTL is 24 hours.
 *
 * <p>An expired row still sits in the unique index, so reusing the key only works because begin() first deletes the
 * expired row for that (user, key) in the same transaction. Without that the reuse below would fail for ever.
 */
class IdempotencyExpiryIntegrationTest extends AbstractIdempotencyIntegrationTest {

    @AfterEach
    void putTheClockBack() {
        clock.reset();
    }

    @Test
    void aKeyStillWithinItsTtlIsReplayed() throws Exception {
        UUID user = newUser();
        UUID account = newAccount(user, 0);
        String key = "within-ttl-" + UUID.randomUUID();
        send("/api/deposits", user, key, depositBody(account, 100));

        clock.advance(Duration.ofHours(23));
        MvcResult retry = send("/api/deposits", user, key, depositBody(account, 100));

        assertThat(retry.getResponse().getStatus()).isEqualTo(201);
        assertThat(retry.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(balance(account)).isEqualTo(100);
    }

    @Test
    void anExpiredKeyCanBeReusedAndExecutesAgain() throws Exception {
        UUID user = newUser();
        UUID account = newAccount(user, 0);
        String key = "expires-" + UUID.randomUUID();
        MvcResult first = send("/api/deposits", user, key, depositBody(account, 100));
        UUID firstTransaction = UUID.fromString(parse(first).get("id").asText());

        clock.advance(Duration.ofHours(24).plusMinutes(1));
        MvcResult again = send("/api/deposits", user, key, depositBody(account, 100));

        assertThat(again.getResponse().getStatus()).isEqualTo(201);
        assertThat(again.getResponse().getHeader("Idempotent-Replayed")).as("a new execution, not a replay").isNull();
        assertThat(UUID.fromString(parse(again).get("id").asText())).isNotEqualTo(firstTransaction);
        assertThat(balance(account)).as("executed twice, once per lifetime of the key").isEqualTo(200);
        assertThat(keyRowCount(user, key)).as("the expired row was replaced, not duplicated").isEqualTo(1);
        // The new row is live: an immediate retry replays it.
        MvcResult replay = send("/api/deposits", user, key, depositBody(account, 100));
        assertThat(replay.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(parse(replay)).isEqualTo(parse(again));
        assertThat(balance(account)).isEqualTo(200);
    }

    @Test
    void anExpiredKeyMayBeReusedForADifferentRequest() throws Exception {
        UUID user = newUser();
        UUID account = newAccount(user, 0);
        String key = "expired-then-different-" + UUID.randomUUID();
        send("/api/deposits", user, key, depositBody(account, 100));

        clock.advance(Duration.ofHours(25));
        MvcResult different = send("/api/deposits", user, key, depositBody(account, 250));

        assertThat(different.getResponse().getStatus()).as("not 422: the old key is gone").isEqualTo(201);
        assertThat(balance(account)).isEqualTo(350);
    }
}
