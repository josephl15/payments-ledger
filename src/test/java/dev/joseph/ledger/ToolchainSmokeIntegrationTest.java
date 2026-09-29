package dev.joseph.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * First real integration test: proves the toolchain end to end (app boots on PostgreSQL 16, Flyway applied V1,
 * Hibernate validate passed, health endpoint UP).
 */
class ToolchainSmokeIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

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
}
