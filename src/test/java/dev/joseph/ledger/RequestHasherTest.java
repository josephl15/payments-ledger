package dev.joseph.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.joseph.ledger.api.DepositRequest;
import dev.joseph.ledger.api.TransferRequest;
import dev.joseph.ledger.service.IdempotencyKeyPolicy;
import dev.joseph.ledger.service.InvalidRequestException;
import dev.joseph.ledger.service.RequestHasher;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests (no Spring, no database) for the request fingerprint and the header rules. The point of the
 * fingerprint is: the same request always gives the same value, however the client happened to write the JSON, and
 * any real difference gives a different value.
 */
class RequestHasherTest {

    private static final UUID ACCOUNT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final RequestHasher hasher = new RequestHasher();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void canonicalFormIsMethodPathAndSortedJsonSeparatedByNewlines() {
        DepositRequest request = new DepositRequest(ACCOUNT, 500L, "GBP", "rent");

        assertThat(hasher.canonicalForm("POST", "/api/deposits", request))
                .isEqualTo("POST\n/api/deposits\n"
                        + "{\"accountId\":\"11111111-1111-1111-1111-111111111111\",\"amountMinor\":500,"
                        + "\"currency\":\"GBP\",\"reference\":\"rent\"}");
    }

    @Test
    void hashIs64LowercaseHexCharactersAndStable() {
        DepositRequest request = new DepositRequest(ACCOUNT, 500L, "GBP", null);

        String hash = hasher.hash("POST", "/api/deposits", request);

        assertThat(hash).matches("[0-9a-f]{64}");
        assertThat(hasher.hash("POST", "/api/deposits", request)).isEqualTo(hash);
    }

    @Test
    void incomingJsonFieldOrderAndWhitespaceDoNotChangeTheHash() throws Exception {
        String a = "{\"accountId\":\"" + ACCOUNT + "\",\"amountMinor\":500,\"currency\":\"GBP\",\"reference\":\"rent\"}";
        String b = "{ \"reference\" : \"rent\",\n \"currency\":\"GBP\" ,\"amountMinor\": 500, \"accountId\":\"" + ACCOUNT
                + "\" }";

        DepositRequest first = json.readValue(a, DepositRequest.class);
        DepositRequest second = json.readValue(b, DepositRequest.class);

        assertThat(hasher.hash("POST", "/api/deposits", first)).isEqualTo(hasher.hash("POST", "/api/deposits", second));
    }

    @Test
    void anAbsentOptionalFieldAndAnExplicitNullAreTheSameRequest() throws Exception {
        DepositRequest absent = json.readValue(
                "{\"accountId\":\"" + ACCOUNT + "\",\"amountMinor\":5,\"currency\":\"GBP\"}", DepositRequest.class);
        DepositRequest explicitNull = json.readValue(
                "{\"accountId\":\"" + ACCOUNT + "\",\"amountMinor\":5,\"currency\":\"GBP\",\"reference\":null}",
                DepositRequest.class);

        assertThat(hasher.hash("POST", "/api/deposits", absent)).isEqualTo(hasher.hash("POST", "/api/deposits", explicitNull));
    }

    @Test
    void anyRealDifferenceChangesTheHash() {
        DepositRequest base = new DepositRequest(ACCOUNT, 500L, "GBP", "rent");
        String baseHash = hasher.hash("POST", "/api/deposits", base);

        assertThat(hasher.hash("POST", "/api/deposits", new DepositRequest(ACCOUNT, 501L, "GBP", "rent")))
                .as("amount").isNotEqualTo(baseHash);
        assertThat(hasher.hash("POST", "/api/deposits", new DepositRequest(OTHER, 500L, "GBP", "rent")))
                .as("account").isNotEqualTo(baseHash);
        assertThat(hasher.hash("POST", "/api/deposits", new DepositRequest(ACCOUNT, 500L, "GBP", "rent2")))
                .as("reference").isNotEqualTo(baseHash);
        assertThat(hasher.hash("POST", "/api/deposits", new DepositRequest(ACCOUNT, 500L, "GBP", null)))
                .as("reference removed").isNotEqualTo(baseHash);
        assertThat(hasher.hash("PUT", "/api/deposits", base)).as("method").isNotEqualTo(baseHash);
        assertThat(hasher.hash("POST", "/api/deposits/other", base)).as("concrete path").isNotEqualTo(baseHash);
    }

    @Test
    void differentRequestTypesNeverShareAHash() {
        // Same key reused on /transfers after /deposits must not replay the deposit.
        DepositRequest deposit = new DepositRequest(ACCOUNT, 500L, "GBP", null);
        TransferRequest transfer = new TransferRequest(ACCOUNT, OTHER, 500L, "GBP", null);

        assertThat(hasher.hash("POST", "/api/deposits", deposit)).isNotEqualTo(hasher.hash("POST", "/api/transfers", transfer));
    }

    // ------------------------------------------------------------------ header rules

    @Test
    void validKeysAreAccepted() {
        for (String key : new String[] {"a", UUID.randomUUID().toString(), "order_42.retry-3:x", "A".repeat(128)}) {
            assertThat(IdempotencyKeyPolicy.requireValid(key)).isEqualTo(key);
        }
    }

    @Test
    void missingBlankOversizedAndBadCharsetKeysAreRejected() {
        String[] bad = {null, "", "   ", "A".repeat(129), "has space", "semi;colon", "quote\"", "new\nline", "unicodé", "a/b"};
        for (String key : bad) {
            assertThatThrownBy(() -> IdempotencyKeyPolicy.requireValid(key))
                    .as("key %s", key == null ? "null" : "of length " + key.length())
                    .isInstanceOf(InvalidRequestException.class);
        }
    }
}
