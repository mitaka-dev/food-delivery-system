package food.ordering.system.product.service.controller;

import food.ordering.system.product.service.enums.ProductCategory;
import food.ordering.system.product.service.exception.AccessDeniedException;
import food.ordering.system.product.service.record.CreateProductDto;
import food.ordering.system.product.service.record.ProductResponseDto;
import food.ordering.system.product.service.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "Products", description = "Product catalog — read is public, create requires ADMIN role")
@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @Operation(summary = "List products", description = "Returns a paginated product list, optionally filtered by category.")
    @ApiResponse(responseCode = "200", description = "Product page returned")
    @GetMapping
    public ResponseEntity<Page<ProductResponseDto>> listProducts(
            @RequestParam(required = false) ProductCategory category,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<ProductResponseDto> result = (category != null)
                ? productService.listProductsByCategory(category, pageable)
                : productService.listProducts(pageable);
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Get product by ID")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Product found"),
            @ApiResponse(responseCode = "404", description = "Product not found")
    })
    @GetMapping("/{id}")
    public ResponseEntity<ProductResponseDto> getProduct(@PathVariable UUID id) {
        return ResponseEntity.ok(productService.getProduct(id));
    }

    @Operation(summary = "Create product", description = "Creates a new product. Requires ADMIN role (enforced via X-User-Role header from gateway).")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Product created"),
            @ApiResponse(responseCode = "403", description = "Caller does not have ADMIN role"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT")
    })
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping
    public ResponseEntity<ProductResponseDto> createProduct(
            @Parameter(hidden = true) @RequestHeader("X-User-Role") String role,
            @Valid @RequestBody CreateProductDto dto) {
        if (!"ADMIN".equals(role)) {
            throw new AccessDeniedException("Only ADMIN users can create products");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(productService.createProduct(dto));
    }
}
