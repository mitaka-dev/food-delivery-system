package food.delivery.system.product.service.grpc;

import food.delivery.system.grpc.product.ProductAvailability;
import food.delivery.system.grpc.product.ProductServiceGrpc;
import food.delivery.system.grpc.product.VerifyProductRequest;
import food.delivery.system.product.service.exception.ProductNotFoundException;
import food.delivery.system.product.service.record.ProductResponseDto;
import food.delivery.system.product.service.service.ProductService;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ProductGrpcService extends ProductServiceGrpc.ProductServiceImplBase {

    private final ProductService productService;
    private final RateLimiter rateLimiter;

    public ProductGrpcService(ProductService productService, RateLimiterRegistry rateLimiterRegistry) {
        this.productService = productService;
        this.rateLimiter = rateLimiterRegistry.rateLimiter("grpc-verify");
    }

    @Override
    public void verifyProduct(VerifyProductRequest request,
                              StreamObserver<ProductAvailability> responseObserver) {
        if (!rateLimiter.acquirePermission()) {
            responseObserver.onError(Status.RESOURCE_EXHAUSTED
                    .withDescription("Rate limit exceeded").asRuntimeException());
            return;
        }
        try {
            UUID id = UUID.fromString(request.getProductId());
            ProductResponseDto dto = productService.getProduct(id);
            responseObserver.onNext(ProductAvailability.newBuilder()
                    .setExists(true)
                    .setInStock(dto.stock() > 0)
                    .setCurrentPrice(dto.price().toPlainString())
                    .setStock(dto.stock())
                    .build());
        } catch (ProductNotFoundException e) {
            responseObserver.onNext(ProductAvailability.newBuilder()
                    .setExists(false)
                    .build());
        }
        responseObserver.onCompleted();
    }
}
