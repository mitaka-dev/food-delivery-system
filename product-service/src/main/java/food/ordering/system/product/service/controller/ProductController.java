package food.ordering.system.product.service.controller;

import food.ordering.system.product.service.enums.ProductCategory;
import food.ordering.system.product.service.exception.AccessDeniedException;
import food.ordering.system.product.service.record.CreateProductDto;
import food.ordering.system.product.service.record.ProductResponseDto;
import food.ordering.system.product.service.service.ProductService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    public ResponseEntity<Page<ProductResponseDto>> listProducts(
            @RequestParam(required = false) ProductCategory category,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<ProductResponseDto> result = (category != null)
                ? productService.listProductsByCategory(category, pageable)
                : productService.listProducts(pageable);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductResponseDto> getProduct(@PathVariable UUID id) {
        return ResponseEntity.ok(productService.getProduct(id));
    }

    @PostMapping
    public ResponseEntity<ProductResponseDto> createProduct(
            @RequestHeader("X-User-Role") String role,
            @Valid @RequestBody CreateProductDto dto) {
        if (!"ADMIN".equals(role)) {
            throw new AccessDeniedException("Only ADMIN users can create products");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(productService.createProduct(dto));
    }
}
