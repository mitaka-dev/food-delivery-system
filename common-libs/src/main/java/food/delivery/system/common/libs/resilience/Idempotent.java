package food.delivery.system.common.libs.resilience;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {
    long ttl() default 3600;
}
