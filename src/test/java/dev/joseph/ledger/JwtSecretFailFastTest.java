package dev.joseph.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.joseph.ledger.config.JwtProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

/**
 * The application must refuse to start without a usable JWT secret, instead of running with a missing or guessable
 * signing key. The secret has no default anywhere in the repository (application.yml deliberately omits it).
 */
class JwtSecretFailFastTest {

    @Configuration
    @EnableConfigurationProperties(JwtProperties.class)
    static class PropertiesOnly {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(PropertiesOnly.class);

    @Test
    void aSecretOf32CharactersIsAcceptedAndTheDefaultLifetimeIsOneHour() {
        runner.withPropertyValues("ledger.jwt.secret=" + "s".repeat(32)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(JwtProperties.class).ttl()).isEqualTo(Duration.ofHours(1));
        });
    }

    @Test
    void aMissingSecretStopsTheContextFromStarting() {
        runner.run(context -> assertThat(context).hasFailed());
    }

    @Test
    void aSecretShorterThan32CharactersStopsTheContextFromStarting() {
        runner.withPropertyValues("ledger.jwt.secret=" + "s".repeat(31)).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void theRealApplicationFailsToStartWithAShortSecretAndDoesNotPrintItInTheError() {
        String shortSecret = "too-short-secret-value";
        assertThatThrownBy(() -> start("ledger.jwt.secret=" + shortSecret))
                .satisfies(e -> assertThat(rootText(e))
                        .contains("ledger.jwt") // it failed because of the JWT settings, not for another reason
                        .doesNotContain(shortSecret));
    }

    /** Control: the same boot code with a valid secret works, so the failures above are caused by the secret. */
    @Test
    void theRealApplicationStartsWithAValidSecret() {
        start("ledger.jwt.secret=" + "v".repeat(40));
    }

    @Test
    void theRealApplicationFailsToStartWithNoSecretAtAll() {
        // No "test" profile here, so application-test.yml (which holds the fake test key) is not loaded, and the
        // environment variable is switched off by giving the property an empty value.
        assertThatThrownBy(() -> start("ledger.jwt.secret="))
                .satisfies(e -> assertThat(rootText(e)).contains("ledger.jwt"));
    }

    /**
     * Boots the whole application (web server on a random port) against the shared test database, without the test profile.
     * Settings are passed as command-line arguments because those outrank the ${...} defaults in application.yml.
     */
    private static void start(String secretProperty) {
        try (ConfigurableApplicationContext ignored = new SpringApplicationBuilder(LedgerServiceApplication.class)
                .web(WebApplicationType.SERVLET)
                .run(
                        "--server.port=0", // any free port
                        "--spring.datasource.url=" + AbstractPostgresIntegrationTest.POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + AbstractPostgresIntegrationTest.POSTGRES.getUsername(),
                        "--spring.datasource.password=" + AbstractPostgresIntegrationTest.POSTGRES.getPassword(),
                        "--" + secretProperty)) {
            // Reaching this line means the application started, which is exactly what must not happen.
        }
    }

    private static String rootText(Throwable e) {
        StringBuilder text = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            text.append(t.getClass().getName()).append(": ").append(t.getMessage()).append('\n');
        }
        return text.toString();
    }
}
