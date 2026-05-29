package food.delivery.system.basket.service.client;

import food.delivery.system.grpc.product.ProductServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "grpc.client.product.enabled", matchIfMissing = true)
public class ProductClientConfig {

    @Value("${grpc.client.product.target:product-service:9090}")
    private String target;

    @Bean
    public ManagedChannel productChannel() {
        return ManagedChannelBuilder.forTarget(target)
                .usePlaintext()
                .build();
    }

    @Bean
    public ProductServiceGrpc.ProductServiceBlockingStub productStub(ManagedChannel productChannel) {
        return ProductServiceGrpc.newBlockingStub(productChannel);
    }
}
