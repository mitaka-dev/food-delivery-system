package food.delivery.system.promotion.service.service;

import food.delivery.system.promotion.service.domain.Promotion;
import food.delivery.system.promotion.service.domain.PromotionRepository;
import food.delivery.system.promotion.service.exception.PromotionNotFoundException;
import food.delivery.system.promotion.service.record.CreatePromotionRequestDto;
import food.delivery.system.promotion.service.record.PromotionDto;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class PromotionService {

    private final PromotionRepository promotionRepository;

    public PromotionService(PromotionRepository promotionRepository) {
        this.promotionRepository = promotionRepository;
    }

    public PromotionDto create(CreatePromotionRequestDto request) {
        Promotion promotion = new Promotion();
        promotion.setId(UUID.randomUUID());
        promotion.setCode(request.code().toUpperCase());
        promotion.setDiscountPercent(request.discountPercent());
        promotion.setActive(true);
        promotion.setCreatedAt(LocalDateTime.now());
        return toDto(promotionRepository.save(promotion));
    }

    public PromotionDto getByCode(String code) {
        return promotionRepository.findByCode(code.toUpperCase())
                .map(this::toDto)
                .orElseThrow(() -> new PromotionNotFoundException("Promotion not found: " + code));
    }

    private PromotionDto toDto(Promotion p) {
        return new PromotionDto(p.getId(), p.getCode(), p.getDiscountPercent(), p.isActive(), p.getCreatedAt());
    }
}
