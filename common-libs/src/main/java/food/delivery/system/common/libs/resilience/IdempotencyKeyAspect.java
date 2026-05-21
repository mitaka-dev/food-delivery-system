package food.delivery.system.common.libs.resilience;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Type;
import java.time.Duration;

@Aspect
public class IdempotencyKeyAspect {

    private static final String KEY_PREFIX = "idempotency:";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public IdempotencyKeyAspect(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    @Around("@annotation(idempotent)")
    public Object intercept(ProceedingJoinPoint pjp, Idempotent idempotent) throws Throwable {
        String idempotencyKey = extractKey();
        if (idempotencyKey == null) {
            return pjp.proceed();
        }

        String redisKey = KEY_PREFIX + idempotencyKey;
        String cached = redis.opsForValue().get(redisKey);
        if (cached != null) {
            Type returnType = ((MethodSignature) pjp.getSignature()).getMethod().getGenericReturnType();
            return mapper.readValue(cached, mapper.constructType(returnType));
        }

        Object result = pjp.proceed();
        redis.opsForValue().set(redisKey, mapper.writeValueAsString(result), Duration.ofSeconds(idempotent.ttl()));
        return result;
    }

    private String extractKey() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
            return attrs.getRequest().getHeader("Idempotency-Key");
        } catch (IllegalStateException e) {
            // Not in a servlet request context (e.g., Kafka listener) — skip idempotency.
            return null;
        }
    }
}
