package dev.joseph.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the ledger service.
 *
 * <p>{@code @SpringBootApplication} does three things:
 * <ul>
 *   <li>component scanning: Spring finds its classes in this package and every package below it;</li>
 *   <li>auto-configuration: the DataSource and HikariCP pool, Flyway, JPA/Hibernate, Actuator and Spring MVC
 *       are set up automatically because their jars are on the classpath, driven by {@code application.yml};</li>
 *   <li>it marks this class as a configuration class, so it can declare beans itself.</li>
 * </ul>
 */
@SpringBootApplication
public class LedgerServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerServiceApplication.class, args);
    }
}
