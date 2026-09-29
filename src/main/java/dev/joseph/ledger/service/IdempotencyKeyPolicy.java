package dev.joseph.ledger.service;

import java.util.regex.Pattern;

/**
 * Which Idempotency-Key header values are accepted: 1 to 128 characters from letters, digits and {@code _ . : -}.
 * A UUID (36 characters) fits. Anything else, including a missing or blank header, is a 400. The limit keeps a
 * 10 KB header from becoming a database key, and the narrow character set keeps odd characters out of logs and SQL
 * parameters (values are still bound as parameters, never concatenated).
 */
public final class IdempotencyKeyPolicy {

    public static final int MAX_LENGTH = 128;

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_.:-]{1," + MAX_LENGTH + "}");

    private IdempotencyKeyPolicy() {}

    /** Returns the key unchanged, or throws {@link InvalidRequestException} (400). The message never echoes the value. */
    public static String requireValid(String key) {
        if (key == null || key.isBlank()) {
            throw new InvalidRequestException("The Idempotency-Key header is required");
        }
        if (key.length() > MAX_LENGTH) {
            throw new InvalidRequestException("The Idempotency-Key header must be at most " + MAX_LENGTH + " characters");
        }
        if (!VALID.matcher(key).matches()) {
            throw new InvalidRequestException(
                    "The Idempotency-Key header may contain only letters, digits and the characters _ . : -");
        }
        return key;
    }
}
