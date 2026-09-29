package dev.joseph.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * First real integration test: proves the toolchain end to end (app boots on PostgreSQL 16, Flyway applied V1,
 * Hibernate validate passed, health endpoint UP) and asserts every runtime setting the project depends on
 * (validate, open-in-view off, problem details, explicit Hikari pool, lock_timeout, health-only actuator, no
 * embedded database).
 */
class ToolchainSmokeIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    Environment env;

    @Autowired
    TestRestTemplate rest;

    @Test
    void runsAgainstPostgres16() {
        assertThat(jdbc.queryForObject("SHOW server_version", String.class)).startsWith("16.");
    }

    @Test
    void flywayAppliedBaseline() {
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success", Integer.class);
        assertThat(applied).isEqualTo(1);

        String comment = jdbc.queryForObject(
                "SELECT obj_description('public'::regnamespace, 'pg_namespace')", String.class);
        assertThat(comment).isEqualTo("Payments ledger schema, managed by Flyway");
    }

    @Test
    void healthIsUp() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void configIsStrict() {
        // Flyway owns the schema, so Hibernate must only validate; open-in-view off so the EntityManager
        // does not span the whole HTTP request.
        assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(env.getProperty("spring.jpa.open-in-view")).isEqualTo("false");
    }

    @Test
    void hikariPoolIsExplicitForTests() throws SQLException {
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        assertThat(hikari.getMaximumPoolSize()).isEqualTo(20);
        assertThat(hikari.getConnectionTimeout()).isEqualTo(5000);
    }

    @Test
    void lockTimeoutAppliedToPoolConnections() {
        // Postgres echoes the value as 5s, not 5000ms.
        assertThat(jdbc.queryForObject("SHOW lock_timeout", String.class)).isEqualTo("5s");
    }

    @Test
    void lockTimeoutFiresOnRealLockWait() throws Exception {
        try (Connection holder = dataSource.getConnection(); Connection waiter = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            waiter.setAutoCommit(false);
            try {
                try (Statement s = holder.createStatement()) {
                    s.execute("LOCK TABLE flyway_schema_history IN ACCESS EXCLUSIVE MODE");
                }
                // SET LOCAL lasts only for this transaction, so the pooled connection keeps the 5s default
                // from connection-init-sql once it is returned to the pool.
                try (Statement s = waiter.createStatement()) {
                    s.execute("SET LOCAL lock_timeout = '300ms'");
                    assertThatThrownBy(() -> s.executeQuery("SELECT count(*) FROM flyway_schema_history"))
                            .isInstanceOf(SQLException.class)
                            .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("55P03"));
                }
            } finally {
                waiter.rollback();
                holder.rollback();
            }
        }
    }

    @Test
    void unknownPathReturnsProblemJson() {
        ResponseEntity<String> response = rest.getForEntity("/nope", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().toString()).startsWith("application/problem+json");
    }

    @Test
    void onlyHealthEndpointIsExposed() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/env", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void noH2OnClasspath() {
        assertThatThrownBy(() -> Class.forName("org.h2.Driver")).isInstanceOf(ClassNotFoundException.class);
    }
}
