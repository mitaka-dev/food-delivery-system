package food.delivery.system.user.service.service;

import food.delivery.system.user.service.entity.RefreshToken;
import food.delivery.system.user.service.entity.User;
import food.delivery.system.user.service.exception.AccountLockedException;
import food.delivery.system.user.service.exception.InvalidTokenException;
import food.delivery.system.user.service.record.AuthResponse;
import food.delivery.system.user.service.record.LoginDto;
import food.delivery.system.user.service.repository.RefreshTokenRepository;
import food.delivery.system.user.service.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class AuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);
    private static final String LOCKOUT_KEY_PREFIX = "login_attempts:";
    private static final int MAX_ATTEMPTS = 5;
    private static final long LOCKOUT_DURATION_SECONDS = 900; // 15 minutes

    @Value("${jwt.refresh-token-expiration}")
    private long refreshTokenExpirationMs;

    private final AuthenticationManager authenticationManager;
    private final UserDetailsServiceImpl userDetailsService;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;
    private final StringRedisTemplate redisTemplate;

    public AuthenticationService(AuthenticationManager authenticationManager,
                                 UserDetailsServiceImpl userDetailsService,
                                 UserRepository userRepository,
                                 RefreshTokenRepository refreshTokenRepository,
                                 JwtService jwtService,
                                 StringRedisTemplate redisTemplate) {
        this.authenticationManager = authenticationManager;
        this.userDetailsService = userDetailsService;
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtService = jwtService;
        this.redisTemplate = redisTemplate;
    }

    // ── Login ─────────────────────────────────────────────────────────────────

    @Transactional
    public AuthResponse login(LoginDto dto) {
        String email = dto.username();

        checkLockout(email);

        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(email, dto.password())
            );
        } catch (BadCredentialsException e) {
            incrementLoginFailures(email);
            throw e;
        }

        // Successful login — clear the failure counter
        redisTemplate.delete(LOCKOUT_KEY_PREFIX + email);

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
        UserDetails userDetails = userDetailsService.loadUserByUsername(email);

        String accessToken = jwtService.generateAccessToken(userDetails);
        String rawRefreshToken = issueRefreshToken(user);

        log.info("Login successful for email='{}'", email);
        return new AuthResponse(accessToken, rawRefreshToken, jwtService.getAccessTokenExpiration());
    }

    // ── Refresh ───────────────────────────────────────────────────────────────

    @Transactional(noRollbackFor = InvalidTokenException.class)
    public AuthResponse refresh(String rawToken) {
        String tokenHash = hashToken(rawToken);

        RefreshToken rt = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new InvalidTokenException("Refresh token not found"));

        if (rt.isRevoked()) {
            // Reuse of a revoked token — possible token theft, revoke entire family
            log.warn("Revoked refresh token presented for userId='{}' — revoking all sessions", rt.getUserId());
            refreshTokenRepository.revokeAllByUserId(rt.getUserId());
            throw new InvalidTokenException("Refresh token already revoked — all sessions terminated");
        }

        if (rt.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new InvalidTokenException("Refresh token expired");
        }

        // Rotate: revoke the old token and issue a new pair
        rt.setRevoked(true);
        refreshTokenRepository.save(rt);

        User user = userRepository.findById(rt.getUserId())
                .orElseThrow(() -> new InvalidTokenException("User not found"));
        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getEmail());

        String newAccessToken = jwtService.generateAccessToken(userDetails);
        String newRawRefreshToken = issueRefreshToken(user);

        log.info("Tokens rotated for userId='{}'", user.getId());
        return new AuthResponse(newAccessToken, newRawRefreshToken, jwtService.getAccessTokenExpiration());
    }

    // ── Logout ────────────────────────────────────────────────────────────────

    @Transactional
    public void logout(String rawRefreshToken, String authorizationHeader) {
        // Add access token jti to denylist so it can't be reused before expiry
        if (authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {
            String accessToken = authorizationHeader.substring(7);
            try {
                String jti = jwtService.extractJti(accessToken);
                long remainingMs = jwtService.extractExpiration(accessToken).getTime() - System.currentTimeMillis();
                if (remainingMs > 0) {
                    jwtService.addJtiToDenylist(jti, remainingMs);
                }
            } catch (Exception e) {
                log.debug("Could not extract jti from access token during logout: {}", e.getMessage());
            }
        }

        // Revoke the refresh token
        String tokenHash = hashToken(rawRefreshToken);
        refreshTokenRepository.findByTokenHash(tokenHash).ifPresent(rt -> {
            rt.setRevoked(true);
            refreshTokenRepository.save(rt);
        });
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void checkLockout(String email) {
        String key = LOCKOUT_KEY_PREFIX + email;
        String attempts = redisTemplate.opsForValue().get(key);
        if (attempts != null && Integer.parseInt(attempts) >= MAX_ATTEMPTS) {
            Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
            long retryAfter = ttl != null && ttl > 0 ? ttl : LOCKOUT_DURATION_SECONDS;
            throw new AccountLockedException(retryAfter);
        }
    }

    private void incrementLoginFailures(String email) {
        String key = LOCKOUT_KEY_PREFIX + email;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            // First failure — set TTL so the counter auto-expires after the lockout window
            redisTemplate.expire(key, LOCKOUT_DURATION_SECONDS, TimeUnit.SECONDS);
        }
    }

    private String issueRefreshToken(User user) {
        String rawToken = UUID.randomUUID().toString();
        RefreshToken rt = new RefreshToken();
        rt.setUserId(user.getId());
        rt.setTokenHash(hashToken(rawToken));
        rt.setExpiresAt(OffsetDateTime.now().plusSeconds(refreshTokenExpirationMs / 1000));
        rt.setRevoked(false);
        refreshTokenRepository.save(rt);
        return rawToken;
    }

    static String hashToken(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
