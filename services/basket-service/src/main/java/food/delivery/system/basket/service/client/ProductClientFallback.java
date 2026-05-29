package food.delivery.system.basket.service.client;

import food.delivery.system.basket.service.exception.ProductServiceUnavailableException;
import org.springframework.stereotype.Component;

@Component
public class ProductClientFallback {

    public ProductServiceUnavailableException apply(Throwable t) {
        return new ProductServiceUnavailableException("Product service unavailable. Please retry.");
    }
}
