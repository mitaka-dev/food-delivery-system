package food.delivery.system.basket.service.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import food.delivery.system.basket.service.exception.GlobalExceptionHandler.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;

@Component
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final String IDEM_HEADER = "Idempotency-Key";
    private static final String USER_HEADER = "X-User-Name";
    private static final String IDEM_PREFIX = "idem:basket:";
    private static final Duration IDEM_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public IdempotencyFilter(StringRedisTemplate redis,
                             @Qualifier("basketObjectMapper") ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod())
                || !"/api/v1/basket/items".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String idempotencyKey = request.getHeader(IDEM_HEADER);
        String userId = request.getHeader(USER_HEADER);

        if (idempotencyKey == null || idempotencyKey.isBlank()
                || userId == null || userId.isBlank()) {
            writeError(response, 400, "Bad Request", "Idempotency-Key header is required");
            return;
        }

        byte[] body = request.getInputStream().readAllBytes();
        String bodyHash = sha256(body);
        String redisKey = IDEM_PREFIX + userId + ":" + idempotencyKey;

        String cached = redis.opsForValue().get(redisKey);
        if (cached != null) {
            int sep = cached.indexOf('|');
            String storedHash = cached.substring(0, sep);
            String storedBody = cached.substring(sep + 1);
            if (!bodyHash.equals(storedHash)) {
                writeError(response, 409, "Conflict",
                        "Idempotency-Key already used with a different request body");
                return;
            }
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/json");
            response.getWriter().write(storedBody);
            return;
        }

        RepeatableReadRequest wrappedReq = new RepeatableReadRequest(request, body);
        ContentCachingResponseWrapper wrappedRes = new ContentCachingResponseWrapper(response);

        chain.doFilter(wrappedReq, wrappedRes);

        if (wrappedRes.getStatus() >= 200 && wrappedRes.getStatus() < 300) {
            String responseBody = new String(wrappedRes.getContentAsByteArray(), StandardCharsets.UTF_8);
            redis.opsForValue().set(redisKey, bodyHash + "|" + responseBody, IDEM_TTL);
        }

        wrappedRes.copyBodyToResponse();
    }

    private void writeError(HttpServletResponse response, int status, String error, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(
                objectMapper.writeValueAsString(new ErrorResponse(status, error, message, LocalDateTime.now())));
    }

    private static String sha256(byte[] data) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private static class RepeatableReadRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        RepeatableReadRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream stream = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return stream.read(); }
                @Override public boolean isFinished() { return stream.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) {}
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
