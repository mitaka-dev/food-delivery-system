package food.delivery.system.product.service.controller;

import food.delivery.system.product.service.enums.ProductCategory;
import food.delivery.system.product.service.exception.AccessDeniedException;
import food.delivery.system.product.service.record.CreateProductDto;
import food.delivery.system.product.service.record.ImageUploadRequest;
import food.delivery.system.product.service.record.ImageUploadResponse;
import food.delivery.system.product.service.record.ProductResponseDto;
import food.delivery.system.product.service.record.UpdateProductDto;
import food.delivery.system.product.service.service.ProductService;
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

@Tag(name = "Products", description = "Product catalog — reads are public, write operations require ADMIN role")
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

    @Operation(summary = "Search products", description = "Full-text search on name and description fields.")
    @ApiResponse(responseCode = "200", description = "Search results returned")
    @GetMapping("/search")
    public ResponseEntity<Page<ProductResponseDto>> searchProducts(
            @RequestParam String q,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(productService.searchProducts(q, pageable));
    }

    @Operation(summary = "Get product by ID")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Product found"),
            @ApiResponse(responseCode = "404", description = "Product not found")
    })
    @GetMapping("/{id}")
    public ResponseEntity<ProductResponseDto> getProduct(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "false") boolean nocache,
            @Parameter(hidden = true) @RequestHeader(value = "X-User-Role", required = false) String role) {
        if (nocache) {
            if (!"ADMIN".equals(role)) {
                throw new AccessDeniedException("Only ADMIN users can bypass cache");
            }
            return ResponseEntity.ok(productService.getProductFresh(id));
        }
        return ResponseEntity.ok(productService.getProduct(id));
    }

    @Operation(summary = "Update product", description = "Partially updates a product. Null fields are left unchanged. Requires ADMIN role.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Product updated"),
            @ApiResponse(responseCode = "403", description = "Caller does not have ADMIN role"),
            @ApiResponse(responseCode = "404", description = "Product not found")
    })
    @SecurityRequirement(name = "bearerAuth")
    @PatchMapping("/{id}")
    public ResponseEntity<ProductResponseDto> updateProduct(
            @PathVariable UUID id,
            @Parameter(hidden = true) @RequestHeader("X-User-Role") String role,
            @Valid @RequestBody UpdateProductDto dto) {
        if (!"ADMIN".equals(role)) {
            throw new AccessDeniedException("Only ADMIN users can update products");
        }
        return ResponseEntity.ok(productService.updateProduct(id, dto));
    }

    @Operation(summary = "Get image upload URL",
            description = "Returns a pre-signed S3 PUT URL valid for 5 minutes. " +
                    "Client uploads image directly to S3 using the URL. " +
                    "Image key is content-addressed: products/{productId}/{sha256}.jpg. Requires ADMIN role.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Pre-signed URL returned"),
            @ApiResponse(responseCode = "403", description = "Caller does not have ADMIN role"),
            @ApiResponse(responseCode = "404", description = "Product not found")
    })
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/{id}/image-upload-url")
    public ResponseEntity<ImageUploadResponse> getImageUploadUrl(
            @PathVariable UUID id,
            @Parameter(hidden = true) @RequestHeader("X-User-Role") String role,
            @Valid @RequestBody ImageUploadRequest req) {
        if (!"ADMIN".equals(role)) {
            throw new AccessDeniedException("Only ADMIN users can request image upload URLs");
        }
        return ResponseEntity.ok(productService.generateImageUploadUrl(id, req));
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
