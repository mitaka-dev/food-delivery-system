package food.delivery.system.basket.service.client;

import food.delivery.system.grpc.product.ProductAvailability;
import food.delivery.system.grpc.product.ProductServiceGrpc;
import food.delivery.system.grpc.product.VerifyProductRequest;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
@ConditionalOnProperty(name = "grpc.client.product.enabled", matchIfMissing = true)
public class ProductGrpcClient {

    private final ProductServiceGrpc.ProductServiceBlockingStub stub;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final ProductClientFallback fallback;

    public ProductGrpcClient(ProductServiceGrpc.ProductServiceBlockingStub stub,
                             CircuitBreakerRegistry cbRegistry,
                             RetryRegistry retryRegistry,
                             ProductClientFallback fallback) {
        this.stub = stub;
        this.circuitBreaker = cbRegistry.circuitBreaker("product-grpc");
        this.retry = retryRegistry.retry("product-grpc");
        this.fallback = fallback;
    }

    public ProductAvailability verify(UUID productId) {
        Supplier<ProductAvailability> decorated = Retry.decorateSupplier(retry,
                CircuitBreaker.decorateSupplier(circuitBreaker,
                        () -> stub.withDeadlineAfter(200, TimeUnit.MILLISECONDS)
                                  .verifyProduct(VerifyProductRequest.newBuilder()
                                          .setProductId(productId.toString())
                                          .build())));
        try {
            return decorated.get();
        } catch (Exception e) {
            throw fallback.apply(e);
        }
    }
}
