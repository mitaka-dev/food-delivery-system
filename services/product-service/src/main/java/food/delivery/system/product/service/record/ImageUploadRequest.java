package food.delivery.system.product.service.record;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Request for a pre-signed S3 upload URL")
public record ImageUploadRequest(
        @Schema(description = "SHA-256 hex digest of the image file (used as the S3 key)")
        @NotBlank String sha256
) {}
