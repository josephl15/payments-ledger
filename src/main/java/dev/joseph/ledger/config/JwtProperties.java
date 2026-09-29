package dev.joseph.ledger.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings under {@code ledger.jwt.}. Spring's relaxed binding maps the environment variable
 * {@code LEDGER_JWT_SECRET} onto {@code ledger.jwt.secret}, so no placeholder is needed in application.yml.
 *
 * <p>There is deliberately NO default for the secret, anywhere in the repository. If it is missing or shorter than
 * 32 characters the validation below fails and the application refuses to start (fail fast), instead of running
 * with a guessable signing key. 32 bytes is the minimum HS256 (HMAC-SHA-256) asks for.
 *
 * @param secret the HMAC signing key as text (32+ ASCII characters, e.g. {@code openssl rand -base64 48})
 * @param ttl how long an access token is valid, e.g. {@code 1h}, {@code 30m}
 */
@Validated
@ConfigurationProperties(prefix = "ledger.jwt")
public record JwtProperties(@NotNull @Size(min = 32) String secret, @DefaultValue("1h") @NotNull Duration ttl) {

    /**
     * The check that really stops startup. The annotations above state the rule, but when THEY fail Spring's error
     * message prints the rejected value, so a 31-character real secret would end up in the startup log. This check
     * runs first (while the record is being built) and its message never contains the value.
     */
    public JwtProperties {
        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException(
                    "ledger.jwt.secret is missing or shorter than 32 characters. Set the LEDGER_JWT_SECRET environment variable.");
        }
    }

    /** A record's generated toString prints every field, which would put the secret into any log line that shows it. */
    @Override
    public String toString() {
        return "JwtProperties[secret=****, ttl=" + ttl + "]";
    }
}
