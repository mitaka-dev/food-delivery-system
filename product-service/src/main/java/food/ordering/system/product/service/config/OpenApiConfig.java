package food.ordering.system.product.service.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "Product Service API",
                version = "1.0.0",
                description = "Product catalog management. Read endpoints are public; create requires ADMIN role " +
                              "(enforced via X-User-Role header injected by the gateway). " +
                              "Stock uses optimistic locking (@Version) for safe concurrent reservation."
        )
)
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        description = "JWT is validated by the gateway. The X-User-Role header is injected automatically."
)
public class OpenApiConfig {}
