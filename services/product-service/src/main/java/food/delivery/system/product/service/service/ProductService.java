package food.delivery.system.product.service.service;

import food.delivery.system.product.service.entity.Product;
import food.delivery.system.product.service.enums.ProductCategory;
import food.delivery.system.product.service.exception.InsufficientStockException;
import food.delivery.system.product.service.exception.ProductNotFoundException;
import food.delivery.system.product.service.record.CreateProductDto;
import food.delivery.system.product.service.record.ImageUploadRequest;
import food.delivery.system.product.service.record.ImageUploadResponse;
import food.delivery.system.product.service.record.ProductResponseDto;
import food.delivery.system.product.service.record.UpdateProductDto;
import food.delivery.system.product.service.repository.ProductRepository;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import static food.delivery.system.product.service.config.ProductCacheConfig.PRODUCTS_CACHE;

import java.util.UUID;

@Service
public class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    private final ProductRepository productRepository;
    private final ImageUploadService imageUploadService;
    private final String cloudFrontBaseUrl;

    public ProductService(
            ProductRepository productRepository,
            @Value("${app.cloudfront.base-url:}") String cloudFrontBaseUrl,
            ImageUploadService imageUploadService) {
        this.productRepository = productRepository;
        this.cloudFrontBaseUrl = cloudFrontBaseUrl;
        this.imageUploadService = imageUploadService;
    }

    @Transactional
    public ProductResponseDto createProduct(CreateProductDto dto) {
        Product product = new Product();
        product.setId(UUID.randomUUID());
        product.setName(dto.name());
        product.setDescription(dto.description());
        product.setPrice(dto.price());
        product.setCategory(dto.category());
        product.setStock(dto.stock());

        productRepository.save(product);
        log.info("Created product: id={}, name={}, category={}", product.getId(), product.getName(), product.getCategory());
        return toDto(product);
    }

    @CacheEvict(cacheNames = PRODUCTS_CACHE, key = "#id.toString()", beforeInvocation = true)
    @Transactional
    public ProductResponseDto updateProduct(UUID id, UpdateProductDto dto) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));

        if (dto.name() != null) product.setName(dto.name());
        if (dto.description() != null) product.setDescription(dto.description());
        if (dto.price() != null) product.setPrice(dto.price());
        if (dto.category() != null) product.setCategory(dto.category());
        if (dto.stock() != null) product.setStock(dto.stock());

        ProductResponseDto updated = toDto(productRepository.save(product));
        log.info("Updated product: id={}", id);
        return updated;
    }

    @Cacheable(cacheNames = PRODUCTS_CACHE, key = "#id.toString()")
    public ProductResponseDto getProduct(UUID id) {
        return productRepository.findById(id)
                .map(this::toDto)
                .orElseThrow(() -> new ProductNotFoundException(id));
    }

    public ProductResponseDto getProductFresh(UUID id) {
        return productRepository.findById(id)
                .map(this::toDto)
                .orElseThrow(() -> new ProductNotFoundException(id));
    }

    public Page<ProductResponseDto> listProducts(Pageable pageable) {
        return productRepository.findAll(pageable).map(this::toDto);
    }

    public Page<ProductResponseDto> listProductsByCategory(ProductCategory category, Pageable pageable) {
        return productRepository.findByCategory(category, pageable).map(this::toDto);
    }

    public Page<ProductResponseDto> searchProducts(String query, Pageable pageable) {
        return productRepository.search(query, pageable).map(this::toDto);
    }

    public ImageUploadResponse generateImageUploadUrl(UUID id, ImageUploadRequest req) {
        if (!productRepository.existsById(id)) {
            throw new ProductNotFoundException(id);
        }
        return imageUploadService.generatePresignedUrl(id, req.sha256());
    }

    @CacheEvict(cacheNames = PRODUCTS_CACHE, key = "#productId.toString()", beforeInvocation = true)
    @Transactional
    public void reserveStock(UUID productId, int quantity) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        if (product.getStock() < quantity) {
            throw new InsufficientStockException(productId, quantity, product.getStock());
        }

        product.setStock(product.getStock() - quantity);
        productRepository.save(product);
        log.info("Reserved {} units of product {}, remaining stock={}", quantity, productId, product.getStock());
    }

    @CacheEvict(cacheNames = PRODUCTS_CACHE, key = "#productId.toString()", beforeInvocation = true)
    @Transactional
    public void releaseStock(UUID productId, int quantity) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        product.setStock(product.getStock() + quantity);
        productRepository.save(product);
        log.info("Released {} units of product {}, new stock={}", quantity, productId, product.getStock());
    }

    private ProductResponseDto toDto(Product product) {
        String imageUrl = null;
        if (StringUtils.hasText(cloudFrontBaseUrl) && StringUtils.hasText(product.getImageS3Key())) {
            imageUrl = cloudFrontBaseUrl + "/" + product.getImageS3Key();
        }
        return new ProductResponseDto(
                product.getId(),
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                product.getCategory(),
                product.getStock(),
                imageUrl
        );
    }
}
