package dev.joseph.ledger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Test helper that creates REAL users through the public API (POST /api/auth/register then /login), so tests
 * authenticate with genuine tokens signed by the application, not with a mocked security context.
 *
 * <p>It remembers each user's token so a test can address callers by user id, like the earlier header-based tests did.
 */
final class AuthTestClient {

    static final String PASSWORD = "correct-horse-battery";

    private final MockMvc mvc;
    private final ObjectMapper json;
    private final Map<UUID, String> tokens = new ConcurrentHashMap<>();

    AuthTestClient(MockMvc mvc, ObjectMapper json) {
        this.mvc = mvc;
        this.json = json;
    }

    /** Registers a user with a unique username, logs in, remembers the token and returns the new user id. */
    UUID newUser() {
        try {
            String username = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
            UUID id = register(username, PASSWORD);
            tokens.put(id, login(username, PASSWORD));
            return id;
        } catch (Exception e) {
            throw new IllegalStateException("could not create a test user", e);
        }
    }

    /** The token remembered for a user created with {@link #newUser()}. */
    String token(UUID userId) {
        String token = tokens.get(userId);
        if (token == null) {
            throw new IllegalArgumentException("no token known for user " + userId);
        }
        return token;
    }

    /** The value for the Authorization header. */
    String bearer(UUID userId) {
        return "Bearer " + token(userId);
    }

    UUID register(String username, String password) throws Exception {
        String body = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(username, password)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(json.readTree(body).get("id").asText());
    }

    String login(String username, String password) throws Exception {
        String body = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(username, password)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return json.readTree(body).get("accessToken").asText();
    }

    static String credentials(String username, String password) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
    }
}
