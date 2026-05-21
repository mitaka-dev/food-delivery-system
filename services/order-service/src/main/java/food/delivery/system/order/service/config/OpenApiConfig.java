package food.delivery.system.order.service.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "Order Service API",
                version = "1.0.0",
                description = "Order lifecycle management. Orders are created as PENDING and confirmed PAID or FAILED " +
                              "via a Kafka Saga involving product-service (stock reservation) and payment-service."
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
