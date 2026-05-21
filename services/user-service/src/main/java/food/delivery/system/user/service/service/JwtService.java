package food.delivery.system.user.service.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.concurrent.TimeUnit;

@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final String REFRESH_TOKEN_PREFIX = "refresh_token:";

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.access-token-expiration}")
    private long accessTokenExpiration;

    @Value("${jwt.refresh-token-expiration}")
    private long refreshTokenExpiration;

    private final StringRedisTemplate redisTemplate;

    public JwtService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    // ── Token Generation ──────────────────────────────────────────────────────

    public String generateAccessToken(UserDetails userDetails) {
        return buildToken(userDetails, accessTokenExpiration, "access");
    }

    public String generateRefreshToken(UserDetails userDetails) {
        String token = buildToken(userDetails, refreshTokenExpiration, "refresh");
        storeRefreshToken(userDetails.getUsername(), token);
        return token;
    }

    private String buildToken(UserDetails userDetails, long expiration, String tokenType) {
        String role = userDetails.getAuthorities().stream()
                .findFirst()
                .map(a -> a.getAuthority().replace("ROLE_", ""))
                .orElse("USER");

        return Jwts.builder()
                .subject(userDetails.getUsername())
                .claim("role", role)
                .claim("type", tokenType)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(getSigningKey())
                .compact();
    }

    // ── Token Validation ──────────────────────────────────────────────────────

    public boolean isAccessTokenValid(String token, UserDetails userDetails) {
        try {
            Claims claims = extractAllClaims(token);
            return claims.getSubject().equals(userDetails.getUsername())
                    && !claims.getExpiration().before(new Date())
                    && "access".equals(claims.get("type"));
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("Invalid access token: {}", e.getMessage());
            return false;
        }
    }

    public boolean isRefreshTokenValid(String username, String token) {
        try {
            Claims claims = extractAllClaims(token);
            String storedToken = redisTemplate.opsForValue().get(REFRESH_TOKEN_PREFIX + username);
            return claims.getSubject().equals(username)
                    && !claims.getExpiration().before(new Date())
                    && "refresh".equals(claims.get("type"))
                    && token.equals(storedToken);
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("Invalid refresh token for user {}: {}", username, e.getMessage());
            return false;
        }
    }

    // ── Token Extraction ──────────────────────────────────────────────────────

    public String extractUsername(String token) {
        return extractAllClaims(token).getSubject();
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    // ── Redis Refresh Token Storage ───────────────────────────────────────────

    private void storeRefreshToken(String username, String token) {
        redisTemplate.opsForValue().set(
                REFRESH_TOKEN_PREFIX + username,
                token,
                refreshTokenExpiration,
                TimeUnit.MILLISECONDS
        );
    }

    public void revokeRefreshToken(String username) {
        redisTemplate.delete(REFRESH_TOKEN_PREFIX + username);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private SecretKey getSigningKey() {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    public long getAccessTokenExpiration() {
        return accessTokenExpiration;
    }
}
