package dev.joseph.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Fails the build if the PostgreSQL image in docker-compose.yml drifts from the one the integration tests use.
 *
 * <p>Tests must exercise the image that ships, otherwise a passing suite says nothing about the database the
 * Compose stack actually runs. This is a plain unit test: it does not extend the Spring base class and starts
 * no container. {@code POSTGRES_IMAGE} is a compile-time constant, so javac inlines its value here and the base
 * class's static block (which starts a container) never runs.
 *
 * <p>Gradle runs tests with the project root as the working directory, locally and in CI, so the relative path
 * resolves.
 */
class PostgresImageTagConsistencyTest {

    @Test
    void composeUsesTheSamePostgresImageAsTestcontainers() throws IOException {
        List<String> lines = Files.readAllLines(Path.of("docker-compose.yml"));

        assertThat(lines)
                .extracting(String::trim)
                .contains("image: " + AbstractPostgresIntegrationTest.POSTGRES_IMAGE);
        assertThat(AbstractPostgresIntegrationTest.POSTGRES_IMAGE).startsWith("postgres:16.");
    }
}
