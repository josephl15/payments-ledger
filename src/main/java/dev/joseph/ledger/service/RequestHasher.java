package dev.joseph.ledger.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * Turns "what the client asked for" into a fingerprint (SHA-256, 64 lowercase hex characters) so a retry can be
 * recognised as the same request and a different request that reuses the key can be refused.
 *
 * <p>The fingerprint covers three things: the HTTP method, the CONCRETE path (the URL actually called, not a
 * template with placeholders, so two different reversals cannot share a fingerprint), and the request DTO after
 * Bean Validation. It is computed from the DTO, not from the raw body text, so it does not matter in which order
 * the client wrote the JSON fields or how much whitespace it used. The DTO is written to JSON with its properties
 * sorted alphabetically, and a newline separates the three parts so different splits cannot produce the same text.
 */
@Component
public class RequestHasher {

    // A private mapper, separate from Spring's: sorted property names are what make the text stable, and this way
    // nobody can change the fingerprint of every stored key by changing the application-wide JSON settings.
    private final ObjectMapper canonicalMapper =
            JsonMapper.builder().configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true).build();

    /** The exact text that is hashed. Public so tests can show what "canonical" means. */
    public String canonicalForm(String method, String path, Object validatedRequest) {
        try {
            return method.toUpperCase(java.util.Locale.ROOT) + "\n" + path + "\n"
                    + canonicalMapper.writeValueAsString(validatedRequest);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not build the canonical form of a request", e);
        }
    }

    public String hash(String method, String path, Object validatedRequest) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalForm(method, path, validatedRequest).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available on the JVM", e);
        }
    }
}
