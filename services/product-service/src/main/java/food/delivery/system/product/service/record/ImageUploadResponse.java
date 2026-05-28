package food.delivery.system.product.service.record;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Pre-signed S3 PUT URL for direct image upload")
public record ImageUploadResponse(
        @Schema(description = "Pre-signed S3 PUT URL, valid for 5 minutes") String uploadUrl,
        @Schema(description = "S3 object key the image will be stored under") String key
) {}
