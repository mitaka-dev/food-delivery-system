package food.delivery.system.product.service.service;

import food.delivery.system.product.service.record.ImageUploadResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

import java.time.Duration;
import java.util.UUID;

@Service
public class ImageUploadService {

    private final S3Presigner presigner;

    @Value("${app.s3.bucket}")
    private String bucket;

    public ImageUploadService(S3Presigner presigner) {
        this.presigner = presigner;
    }

    public ImageUploadResponse generatePresignedUrl(UUID productId, String sha256) {
        String key = "products/" + productId + "/" + sha256 + ".jpg";
        PresignedPutObjectRequest presigned = presigner.presignPutObject(b -> b
                .signatureDuration(Duration.ofMinutes(5))
                .putObjectRequest(p -> p
                        .bucket(bucket)
                        .key(key)
                        .contentType("image/jpeg")));
        return new ImageUploadResponse(presigned.url().toString(), key);
    }
}
