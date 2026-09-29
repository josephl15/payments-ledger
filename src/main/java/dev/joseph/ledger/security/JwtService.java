package dev.joseph.ledger.security;

import dev.joseph.ledger.config.JwtProperties;
import dev.joseph.ledger.domain.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/**
 * Creates and checks JSON Web Tokens (JWTs).
 *
 * <p>A JWT is three base64 parts joined by dots: header.payload.signature. The payload ("claims") here holds the
 * user id (subject), the role, when it was issued and when it expires. The signature is an HMAC-SHA-256 of the
 * first two parts made with the server's secret key, so anyone can read a token but nobody without the secret can
 * change it (change one character and the signature no longer matches) or make a new one.
 *
 * <p>Time comes from the injected {@link Clock}, both when issuing and when checking, so tests can move time
 * forward and see a token expire without sleeping.
 */
@Component
public class JwtService {

    private static final String ROLE_CLAIM = "role";

    private final JwtProperties properties;
    private final Clock clock;
    private final SecretKey key;
    private final JwtParser parser;

    public JwtService(JwtProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        // hmacShaKeyFor refuses keys shorter than 256 bits, a second line of defence behind the @Size check.
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        // verifyWith(key): only tokens signed with this key are accepted. Unsigned ("alg":"none") tokens are always
        // rejected by jjwt. The parser also compares "exp" with OUR clock, not the system clock.
        this.parser = Jwts.parser()
                .verifyWith(key)
                .clock(() -> Date.from(clock.instant()))
                .build();
    }

    /** A signed token for this user, valid for the configured time-to-live. */
    public String issue(UUID userId, Role role) {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(userId.toString())
                .claim(ROLE_CLAIM, role.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.ttl())))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** How long a freshly issued token lives, in seconds (returned to the client as expiresInSeconds). */
    public long ttlSeconds() {
        return properties.ttl().toSeconds();
    }

    /**
     * The user inside a valid token, or empty if the token is malformed, tampered with, expired or otherwise
     * invalid. The reason is deliberately not returned: the client only ever learns "401".
     */
    public Optional<AuthenticatedUser> parse(String token) {
        try {
            Claims claims = parser.parseSignedClaims(token).getPayload();
            UUID userId = UUID.fromString(claims.getSubject());
            Role role = Role.valueOf(claims.get(ROLE_CLAIM, String.class));
            return Optional.of(new AuthenticatedUser(userId, role));
        } catch (JwtException | IllegalArgumentException e) {
            // JwtException: bad signature, expired, malformed. IllegalArgumentException: blank token, subject that is
            // not a UUID, unknown role. Never log the token itself.
            return Optional.empty();
        }
    }
}
