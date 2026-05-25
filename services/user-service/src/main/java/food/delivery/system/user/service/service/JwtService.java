package food.delivery.system.user.service.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final String JTI_DENYLIST_PREFIX = "jti_denylist:";

    @Value("${jwt.access-token-expiration}")
    private long accessTokenExpiration;

    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final StringRedisTemplate redisTemplate;

    public JwtService(KeyPair keyPair, StringRedisTemplate redisTemplate) {
        this.privateKey = keyPair.getPrivate();
        this.publicKey = keyPair.getPublic();
        this.redisTemplate = redisTemplate;
    }

    // ── Token Generation ──────────────────────────────────────────────────────

    public String generateAccessToken(UserDetails userDetails) {
        String role = userDetails.getAuthorities().stream()
                .findFirst()
                .map(a -> a.getAuthority().replace("ROLE_", ""))
                .orElse("USER");

        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(userDetails.getUsername())
                .claim("role", role)
                .claim("type", "access")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + accessTokenExpiration))
                .signWith(privateKey, Jwts.SIG.RS256)
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

    // ── Claim Extraction ──────────────────────────────────────────────────────

    public String extractUsername(String token) {
        return extractAllClaims(token).getSubject();
    }

    public String extractJti(String token) {
        return extractAllClaims(token).getId();
    }

    public Date extractExpiration(String token) {
        return extractAllClaims(token).getExpiration();
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(publicKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    // ── JTI Denylist (logout) ─────────────────────────────────────────────────

    public void addJtiToDenylist(String jti, long remainingMillis) {
        redisTemplate.opsForValue().set(
                JTI_DENYLIST_PREFIX + jti,
                "1",
                remainingMillis,
                TimeUnit.MILLISECONDS
        );
    }

    public boolean isJtiDenylisted(String jti) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(JTI_DENYLIST_PREFIX + jti));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    public long getAccessTokenExpiration() {
        return accessTokenExpiration;
    }
}
