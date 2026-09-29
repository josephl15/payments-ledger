package dev.joseph.ledger;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for every integration test: boots the whole application against a real PostgreSQL 16.
 *
 * <p><b>One container for the whole test JVM.</b> The container is created and started in a static
 * initialiser, so it starts once, the first time any subclass is loaded. Testcontainers' Ryuk sidecar removes
 * it when the JVM exits.
 *
 * <p><b>Why not the Testcontainers JUnit field annotations here.</b> With a container annotated for the JUnit
 * lifecycle in an abstract base class, the container would restart for each test class on a new random port,
 * but Spring caches its application context between test classes and that context keeps the old JDBC URL,
 * so later classes fail with connection errors. Starting once in a static block avoids this.
 *
 * <p><b>No container reuse across runs.</b> Every run gets a fresh database, so no state leaks between runs
 * and CI behaves like a laptop.
 *
 * <p><b>Why {@code @DynamicPropertySource}.</b> The JDBC URL (host port) is only known after the container
 * has started, so it cannot live in a properties file. This method feeds the live values into Spring's
 * environment before the context is created, overriding {@code spring.datasource.*} from application.yml.
 *
 * <p>The image tag is a compile-time constant so other code can read it without starting a container.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractPostgresIntegrationTest {

    /** Must match the {@code db} image in docker-compose.yml. */
    public static final String POSTGRES_IMAGE = "postgres:16.15-alpine";

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE);

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
