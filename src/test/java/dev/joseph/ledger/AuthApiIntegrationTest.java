package dev.joseph.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import dev.joseph.ledger.api.LoginRequest;
import dev.joseph.ledger.api.RegisterRequest;
import dev.joseph.ledger.api.TokenResponse;
import dev.joseph.ledger.domain.Role;
import dev.joseph.ledger.security.AuthenticatedUser;
import dev.joseph.ledger.security.JwtService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Authentication and ownership over HTTP against real PostgreSQL. Every user here is created through
 * POST /api/auth/register and holds a real token issued by the application; no mocked security context is used.
 *
 * <p>Extends the idempotency base class only to reuse its shared Spring context, whose Clock is a MutableClock, so the
 * expiry test can move time without sleeping.
 */
@ExtendWith(OutputCaptureExtension.class)
class AuthApiIntegrationTest extends AbstractIdempotencyIntegrationTest {

    private static final String PASSWORD = AuthTestClient.PASSWORD;

    @Autowired
    JwtService jwtService;

    @AfterEach
    void putTheClockBack() {
        clock.reset();
    }

    private static String uniqueName() {
        return "user" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private ResultActions postJson(String path, String body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions expectProblem(ResultActions result, int status) throws Exception {
        return result.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(status));
    }

    private ResultActions getWithToken(String path, String token) throws Exception {
        MockHttpServletRequestBuilder request = get(path);
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        return mvc.perform(request);
    }

    // ---------------------------------------------------------------- registration

    @Test
    void registerCreatesAUserAndNeverReturnsAPasswordOrHash() throws Exception {
        String username = uniqueName();

        String body = postJson("/api/auth/register", AuthTestClient.credentials(username, PASSWORD))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain(PASSWORD).doesNotContain("password").doesNotContain("$2");
    }

    @Test
    void thePasswordIsStoredAsABcryptHashNeverAsPlaintext() throws Exception {
        String username = uniqueName();
        postJson("/api/auth/register", AuthTestClient.credentials(username, PASSWORD))
                .andExpect(status().isCreated());

        String stored =
                jdbc.queryForObject("SELECT password_hash FROM users WHERE username = ?", String.class, username);

        assertThat(stored).isNotEqualTo(PASSWORD).doesNotContain(PASSWORD);
        assertThat(stored).matches("\\$2[aby]\\$10\\$.{53}"); // BCrypt, cost 10, 60 characters, no {bcrypt} prefix
        assertThat(new BCryptPasswordEncoder().matches(PASSWORD, stored)).isTrue();
        assertThat(new BCryptPasswordEncoder().matches("wrong-password", stored)).isFalse();
    }

    @Test
    void twoUsersWithTheSamePasswordGetDifferentHashesBecauseOfTheSalt() throws Exception {
        String first = uniqueName();
        String second = uniqueName();
        postJson("/api/auth/register", AuthTestClient.credentials(first, PASSWORD)).andExpect(status().isCreated());
        postJson("/api/auth/register", AuthTestClient.credentials(second, PASSWORD)).andExpect(status().isCreated());

        String hash1 = jdbc.queryForObject("SELECT password_hash FROM users WHERE username = ?", String.class, first);
        String hash2 = jdbc.queryForObject("SELECT password_hash FROM users WHERE username = ?", String.class, second);

        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void aDuplicateUsernameIsRejectedWith409EvenInADifferentCase() throws Exception {
        String username = uniqueName();
        postJson("/api/auth/register", AuthTestClient.credentials(username, PASSWORD)).andExpect(status().isCreated());

        expectProblem(postJson("/api/auth/register", AuthTestClient.credentials(username, PASSWORD)), 409);
        expectProblem(
                postJson("/api/auth/register", AuthTestClient.credentials(username.toUpperCase(), PASSWORD)), 409);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE username = ?", Integer.class, username))
                .isEqualTo(1);
    }

    @Test
    void manySimultaneousRegistrationsOfOneUsernameCreateExactlyOneUser() throws Exception {
        String username = uniqueName();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Integer>> jobs = java.util.stream.IntStream.range(0, threads)
                    .<Callable<Integer>>mapToObj(i -> () -> postJson(
                                    "/api/auth/register", AuthTestClient.credentials(username, PASSWORD))
                            .andReturn()
                            .getResponse()
                            .getStatus())
                    .toList();
            int created = 0;
            int conflicts = 0;
            for (Future<Integer> result : pool.invokeAll(jobs)) {
                int status = result.get();
                if (status == 201) {
                    created++;
                } else if (status == 409) {
                    conflicts++;
                }
            }
            assertThat(created).isEqualTo(1);
            assertThat(conflicts).isEqualTo(threads - 1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void weakOrMalformedInputIsRejectedWith400AndNeverEchoesThePassword() throws Exception {
        String shortPassword = "abc12";
        String body = expectProblem(
                        postJson("/api/auth/register", AuthTestClient.credentials(uniqueName(), shortPassword)), 400)
                .andExpect(jsonPath("$.errors[0].field").value("password"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body).doesNotContain(shortPassword);

        expectProblem(postJson("/api/auth/register", AuthTestClient.credentials("ab", PASSWORD)), 400); // name too short
        expectProblem(postJson("/api/auth/register", AuthTestClient.credentials("bad name!", PASSWORD)), 400);
        expectProblem(postJson("/api/auth/register", AuthTestClient.credentials(uniqueName(), "x".repeat(73))), 400);
        expectProblem(postJson("/api/auth/register", "{}"), 400);
        expectProblem(postJson("/api/auth/register", "{\"username\":\"\",\"password\":\"\"}"), 400);
        // 40 characters but 80 bytes: BCrypt would silently ignore everything after byte 72, so it is refused.
        expectProblem(postJson("/api/auth/register", AuthTestClient.credentials(uniqueName(), "é".repeat(40))), 400);
    }

    // ---------------------------------------------------------------- login

    @Test
    void loginReturnsATokenForTheRegisteredUser() throws Exception {
        String username = uniqueName();
        UUID id = new AuthTestClient(mvc, json).register(username, PASSWORD);

        String body = postJson("/api/auth/login", AuthTestClient.credentials(username, PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresInSeconds").value(jwtService.ttlSeconds()))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String token = json.readTree(body).get("accessToken").asText();
        assertThat(token.split("\\.")).hasSize(3); // header.payload.signature
        assertThat(jwtService.parse(token)).contains(new AuthenticatedUser(id, Role.USER));
        // The username lookup is case-insensitive, like registration.
        postJson("/api/auth/login", AuthTestClient.credentials(username.toUpperCase(), PASSWORD))
                .andExpect(status().isOk());
    }

    @Test
    void aWrongPasswordAndAnUnknownUsernameGiveTheIdenticalResponse() throws Exception {
        String username = uniqueName();
        new AuthTestClient(mvc, json).register(username, PASSWORD);

        MvcResult wrongPassword = postJson("/api/auth/login", AuthTestClient.credentials(username, "not-the-password"))
                .andReturn();
        MvcResult unknownUser = postJson("/api/auth/login", AuthTestClient.credentials(uniqueName(), PASSWORD))
                .andReturn();

        assertThat(wrongPassword.getResponse().getStatus()).isEqualTo(401);
        assertThat(unknownUser.getResponse().getStatus()).isEqualTo(401);
        JsonNode a = json.readTree(wrongPassword.getResponse().getContentAsString());
        JsonNode b = json.readTree(unknownUser.getResponse().getContentAsString());
        // Same title and detail (the "instance" field is the path, also identical); nothing hints which case it was.
        assertThat(a).isEqualTo(b);
        assertThat(wrongPassword.getResponse().getContentType()).contains("problem+json");
    }

    @Test
    void loginWithMissingFieldsIsBadRequest() throws Exception {
        expectProblem(postJson("/api/auth/login", "{}"), 400);
    }

    // ---------------------------------------------------------------- tokens on protected endpoints

    @Test
    void aValidTokenOpensProtectedEndpoints() throws Exception {
        UUID user = newUser();
        mvc.perform(get("/api/accounts").header("Authorization", bearer(user))).andExpect(status().isOk());
    }

    @Test
    void protectedEndpointsRefuseARequestWithoutATokenWith401ProblemJson() throws Exception {
        UUID account = UUID.randomUUID();
        List<MockHttpServletRequestBuilder> requests = List.of(
                get("/api/accounts"),
                get("/api/accounts/" + account),
                post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"),
                post("/api/deposits")
                        .header("Idempotency-Key", "k1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(depositBody(account, 100)),
                post("/api/transfers")
                        .header("Idempotency-Key", "k2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(account, UUID.randomUUID(), 100)),
                get("/api/does-not-exist"));
        for (MockHttpServletRequestBuilder request : requests) {
            expectProblem(mvc.perform(request), 401)
                    .andExpect(header().string("WWW-Authenticate", "Bearer"))
                    .andExpect(jsonPath("$.title").value("Unauthorized"));
        }
    }

    @Test
    void anAuthorizationHeaderThatIsNotABearerTokenIsRefused() throws Exception {
        expectProblem(mvc.perform(get("/api/accounts").header("Authorization", "Basic dXNlcjpwYXNz")), 401);
        expectProblem(mvc.perform(get("/api/accounts").header("Authorization", "Bearer ")), 401);
        expectProblem(mvc.perform(get("/api/accounts").header("Authorization", "Bearer garbage.not.jwt")), 401);
    }

    @Test
    void healthAndAuthEndpointsNeedNoToken() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        // Reaching the controller (400 for an empty body) proves the request was not stopped by the security rules.
        postJson("/api/auth/login", "{}").andExpect(status().isBadRequest());
        postJson("/api/auth/register", "{}").andExpect(status().isBadRequest());
    }

    @Test
    void otherActuatorEndpointsStayClosed() throws Exception {
        expectProblem(mvc.perform(get("/actuator/env")), 401);
    }

    @Test
    void anExpiredTokenIsRefusedOnceTheClockPassesItsExpiry() throws Exception {
        UUID user = newUser();
        String token = jwtService.issue(user, Role.USER);
        getWithToken("/api/accounts", token).andExpect(status().isOk());

        clock.advance(Duration.ofSeconds(jwtService.ttlSeconds()).minusMinutes(1));
        getWithToken("/api/accounts", token).andExpect(status().isOk()); // one minute before expiry

        clock.advance(Duration.ofMinutes(2));
        expectProblem(getWithToken("/api/accounts", token), 401); // one minute after expiry
    }

    @Test
    void aTokenWithAChangedSignatureOrPayloadIsRefused() throws Exception {
        UUID attacker = newUser();
        UUID victim = newUser();
        String token = token(attacker);
        getWithToken("/api/accounts", token).andExpect(status().isOk());
        String[] parts = token.split("\\.");

        // 1. Change one character of the signature.
        char first = parts[2].charAt(0);
        String badSignature = parts[0] + "." + parts[1] + "." + (first == 'A' ? 'B' : 'A') + parts[2].substring(1);
        expectProblem(getWithToken("/api/accounts", badSignature), 401);

        // 2. Keep the real signature but swap the user id inside the payload for the victim's.
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        assertThat(payload).contains(attacker.toString());
        String forgedPayload = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(payload.replace(attacker.toString(), victim.toString()).getBytes(StandardCharsets.UTF_8));
        expectProblem(getWithToken("/api/accounts", parts[0] + "." + forgedPayload + "." + parts[2]), 401);
    }

    @Test
    void aTokenSignedWithAnotherKeyOrNotSignedAtAllIsRefused() throws Exception {
        UUID user = newUser();
        Date expiry = Date.from(Instant.now().plus(Duration.ofHours(1)));

        String otherKey = Jwts.builder()
                .subject(user.toString())
                .claim("role", "USER")
                .expiration(expiry)
                .signWith(Keys.hmacShaKeyFor("a-different-secret-that-is-32-chars!".getBytes(StandardCharsets.UTF_8)))
                .compact();
        expectProblem(getWithToken("/api/accounts", otherKey), 401);

        String unsigned = Jwts.builder()
                .subject(user.toString())
                .claim("role", "USER")
                .expiration(expiry)
                .compact(); // "alg":"none": no signature at all
        expectProblem(getWithToken("/api/accounts", unsigned), 401);
    }

    // ---------------------------------------------------------------- ownership

    @Test
    void aUserCannotReadListDepositToOrTransferFromAnotherUsersAccount() throws Exception {
        UUID alice = newUser();
        UUID bob = newUser();
        UUID aliceAccount = newAccount(alice, 5_000);
        UUID bobAccount = newAccount(bob, 5_000);

        // Read: 404, the same answer as for an account that does not exist.
        MvcResult others = mvc.perform(get("/api/accounts/" + bobAccount).header("Authorization", bearer(alice)))
                .andExpect(status().isNotFound())
                .andReturn();
        MvcResult unknown = mvc.perform(get("/api/accounts/" + UUID.randomUUID()).header("Authorization", bearer(alice)))
                .andExpect(status().isNotFound())
                .andReturn();
        assertThat(parse(others).get("title")).isEqualTo(parse(unknown).get("title"));
        assertThat(parse(others).get("detail")).isEqualTo(parse(unknown).get("detail"));

        // List: only your own.
        mvc.perform(get("/api/accounts").header("Authorization", bearer(alice)))
                .andExpect(jsonPath("$[*].id").value(hasItem(aliceAccount.toString())))
                .andExpect(jsonPath("$[*].id").value(not(hasItem(bobAccount.toString()))));

        // Deposit into someone else's account: 404, nothing moved.
        assertThat(send("/api/deposits", alice, "k-" + UUID.randomUUID(), depositBody(bobAccount, 100))
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);

        // Transfer out of someone else's account: 404, nothing moved.
        assertThat(send("/api/transfers", alice, "k-" + UUID.randomUUID(), transferBody(bobAccount, aliceAccount, 100))
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);

        assertThat(balance(bobAccount)).isEqualTo(5_000);
        assertThat(balance(aliceAccount)).isEqualTo(5_000);

        // Sending TO someone else's account is allowed (that is what a payment is).
        assertThat(send("/api/transfers", alice, "k-" + UUID.randomUUID(), transferBody(aliceAccount, bobAccount, 1_000))
                        .getResponse()
                        .getStatus())
                .isEqualTo(201);
        assertThat(balance(aliceAccount)).isEqualTo(4_000);
        assertThat(balance(bobAccount)).isEqualTo(6_000);
    }

    // ---------------------------------------------------------------- secrets stay out of output

    @Test
    void noPasswordOrTokenAppearsInTheLogOrInAnyErrorBody(CapturedOutput output) throws Exception {
        String username = uniqueName();
        String password = "very-secret-pass-" + UUID.randomUUID();

        MvcResult registered = postJson("/api/auth/register", AuthTestClient.credentials(username, password)).andReturn();
        MvcResult loggedIn = postJson("/api/auth/login", AuthTestClient.credentials(username, password)).andReturn();
        MvcResult rejected = postJson("/api/auth/login", AuthTestClient.credentials(username, password + "x")).andReturn();
        String token = json.readTree(loggedIn.getResponse().getContentAsString()).get("accessToken").asText();
        MvcResult badRequest = postJson("/api/auth/register", AuthTestClient.credentials("x", password)).andReturn();
        getWithToken("/api/accounts", token + "tampered").andReturn();

        assertThat(output.getAll()).contains("User registered id=").contains("Login accepted id="); // capture works
        assertThat(output.getAll()).doesNotContain(password).doesNotContain(token);
        for (MvcResult result : List.of(registered, rejected, badRequest)) {
            assertThat(result.getResponse().getContentAsString()).doesNotContain(password);
        }
    }

    @Test
    void recordsThatHoldSecretsDoNotPrintThem() {
        assertThat(new RegisterRequest("alice", "hunter2hunter2").toString()).doesNotContain("hunter2");
        assertThat(new LoginRequest("alice", "hunter2hunter2").toString()).doesNotContain("hunter2");
        assertThat(new TokenResponse("header.payload.signature", "Bearer", 60).toString())
                .doesNotContain("signature");
    }
}
