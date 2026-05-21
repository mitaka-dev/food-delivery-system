package food.delivery.system.common.libs.resilience;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.data.redis.core.StringRedisTemplate;

@AutoConfiguration
@PropertySource(
        value = "classpath:application-resilience.yml",
        factory = YamlPropertySourceFactory.class)
public class ResilienceAutoConfig {

    // IdempotencyKeyAspect is conditional on both Redis and the Servlet API being
    // present, so it lives in a nested @Configuration. Spring Boot reads the
    // @ConditionalOnClass via ASM before loading the nested class body, so the
    // class load never fails even when these deps are absent.
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({StringRedisTemplate.class,
            org.springframework.web.context.request.ServletRequestAttributes.class})
    static class IdempotencyConfig {

        @Bean
        @ConditionalOnBean(StringRedisTemplate.class)
        IdempotencyKeyAspect idempotencyKeyAspect(StringRedisTemplate redis, ObjectMapper mapper) {
            return new IdempotencyKeyAspect(redis, mapper);
        }
    }
}
