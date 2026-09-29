package dev.joseph.ledger.api;

/**
 * The login result. The client sends {@code accessToken} back as {@code Authorization: Bearer <accessToken>} until
 * {@code expiresInSeconds} have passed.
 */
public record TokenResponse(String accessToken, String tokenType, long expiresInSeconds) {

    /** Keeps the token out of any log line that prints this object. */
    @Override
    public String toString() {
        return "TokenResponse[accessToken=****, tokenType=" + tokenType + ", expiresInSeconds=" + expiresInSeconds + "]";
    }
}
