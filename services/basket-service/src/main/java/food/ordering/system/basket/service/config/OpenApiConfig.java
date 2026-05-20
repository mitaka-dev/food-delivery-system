package food.ordering.system.basket.service.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "Basket Service API",
                version = "1.0.0",
                description = "Redis-backed cart management. Carts are keyed by userId with a 24-hour TTL. " +
                              "Checkout publishes a BasketCheckedOutEvent to Kafka for order-service to consume."
        )
)
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        description = "JWT is validated by the gateway. The X-User-Name header is injected automatically."
)
public class OpenApiConfig {}
