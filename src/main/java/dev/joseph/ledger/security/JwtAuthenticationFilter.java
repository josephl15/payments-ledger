package dev.joseph.ledger.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Runs once per request, before the controllers. If the request carries {@code Authorization: Bearer <token>} and
 * the token is valid, it records "this request is user X" in the security context. If there is no token or it is
 * invalid it does nothing: the request simply stays unauthenticated, and the authorization rules in
 * {@link SecurityConfig} then answer 401 for every protected URL. Keeping the decision in one place (the rules)
 * means this filter never has to write an error response itself.
 *
 * <p>This class is deliberately NOT annotated {@code @Component}: Spring Boot would then register it as a plain
 * servlet filter as well as inside the security chain, and it would run twice. SecurityConfig creates it and puts
 * it into the chain by hand.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            jwtService.parse(token).ifPresent(user -> {
                var authentication = new UsernamePasswordAuthenticationToken(
                        user, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name())));
                // A fresh context per request (the recommended way), never a shared one.
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(authentication);
                SecurityContextHolder.setContext(context);
            });
        }
        // Always continue: an unauthenticated request is refused later by the authorization rules, not here.
        filterChain.doFilter(request, response);
    }
}
