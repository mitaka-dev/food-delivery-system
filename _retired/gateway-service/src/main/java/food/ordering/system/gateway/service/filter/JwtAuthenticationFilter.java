package food.ordering.system.gateway.service.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

/**
 * Gateway-level JWT filter.
 * Runs before every request. Public paths are whitelisted — all others require a valid Bearer token.
 * On success, the validated username and role are forwarded as headers to downstream services.
 */
@Component
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    // Routes that do not require authentication
    private static final List<String> PUBLIC_PATHS = List.of(
            "/api/v1/users",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/actuator"
    );

    @Value("${jwt.secret}")
    private String secret;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        if (isPublicPath(path)) {
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            log.warn("Missing or malformed Authorization header for path: {}", path);
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        String token = authHeader.substring(BEARER_PREFIX.length());

        try {
            Claims claims = validateToken(token);
            String username = claims.getSubject();
            String role = claims.get("role", String.class);

            log.info("JWT validated — user='{}', role='{}', path={}", username, role, path);

            // Forward user context as headers to downstream services
            ServerWebExchange mutatedExchange = exchange.mutate()
                    .request(r -> r
                            .header("X-User-Name", username)
                            .header("X-User-Role", role != null ? role : "")
                    )
                    .build();

            return chain.filter(mutatedExchange);

        } catch (JwtException | IllegalArgumentException e) {
            log.warn("JWT validation failed for path {}: {}", path, e.getMessage());
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
    }

    private Claims validateToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();

        if (claims.getExpiration().before(new Date())) {
            throw new JwtException("Token has expired");
        }
        if (!"access".equals(claims.get("type"))) {
            throw new JwtException("Not an access token");
        }
        return claims;
    }

    private boolean isPublicPath(String path) {
        return PUBLIC_PATHS.stream().anyMatch(path::startsWith);
    }

    private SecretKey getSigningKey() {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    @Override
    public int getOrder() {
        return -1; // Run before other filters
    }
}
