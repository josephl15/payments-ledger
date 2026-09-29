package dev.joseph.ledger.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bean wiring. A {@code @Configuration} class is a factory: each {@code @Bean} method returns an object that Spring
 * creates once and hands to any class that asks for it in its constructor.
 */
@Configuration
@EnableConfigurationProperties({LedgerProperties.class, JwtProperties.class})
public class LedgerConfig {

    /**
     * The single source of "now". Services take a Clock instead of calling Instant.now(), so a test can supply a
     * fixed clock and time-dependent behaviour stays deterministic.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
