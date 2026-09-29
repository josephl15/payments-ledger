package dev.joseph.ledger.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * The security rules for the whole application, written as a {@link SecurityFilterChain} bean (Spring Security 6
 * style; the old WebSecurityConfigurerAdapter no longer exists).
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http, JwtService jwtService, ProblemJsonSecurityHandlers handlers) throws Exception {
        http
                // CSRF protection defends browsers that send a cookie automatically. This API keeps no session and
                // no cookie: the caller must attach the token by hand in the Authorization header, which a hostile
                // web page cannot do. With nothing to ride on, the CSRF check would only get in the way.
                .csrf(AbstractHttpConfigurer::disable)
                // STATELESS: the server remembers nothing between requests; every request proves itself with its token.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // No login page and no browser basic-auth popup: authentication is only via the Bearer token.
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        // Register and login cannot require a token: they are how you get one.
                        .requestMatchers("/api/auth/**")
                        .permitAll()
                        // Liveness for Docker/Compose health checks. Only health is exposed (see application.yml).
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**")
                        .permitAll()
                        // Spring Boot forwards unhandled errors to /error; without this the error body would be hidden.
                        .requestMatchers("/error")
                        .permitAll()
                        // Everything else, including URLs that do not exist, needs a valid token.
                        .anyRequest()
                        .authenticated())
                .exceptionHandling(handling ->
                        handling.authenticationEntryPoint(handlers).accessDeniedHandler(handlers))
                // Our filter must run before Spring's own username/password filter position in the chain.
                .addFilterBefore(new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * BCrypt: a deliberately slow, salted hash. Each call to encode() picks a fresh random salt and stores it inside
     * the result, so equal passwords produce different hashes and rainbow tables are useless. Used as the plain
     * class (not DelegatingPasswordEncoder), so the stored value is just the "$2a$10$..." string with no prefix.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
