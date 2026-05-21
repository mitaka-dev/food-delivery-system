package food.delivery.system.delivery.service.service;

import food.delivery.system.delivery.service.domain.Delivery;
import food.delivery.system.delivery.service.domain.DeliveryRepository;
import food.delivery.system.delivery.service.enums.DeliveryStatus;
import food.delivery.system.delivery.service.exception.DeliveryNotFoundException;
import food.delivery.system.delivery.service.record.DeliveryDto;
import food.delivery.system.delivery.service.record.UpdateDeliveryStatusRequestDto;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class DeliveryService {

    private final DeliveryRepository deliveryRepository;

    public DeliveryService(DeliveryRepository deliveryRepository) {
        this.deliveryRepository = deliveryRepository;
    }

    public DeliveryDto createDelivery(UUID orderId, String username) {
        Delivery delivery = new Delivery();
        delivery.setId(UUID.randomUUID());
        delivery.setOrderId(orderId);
        delivery.setUsername(username);
        delivery.setStatus(DeliveryStatus.PENDING);
        delivery.setCreatedAt(LocalDateTime.now());
        return toDto(deliveryRepository.save(delivery));
    }

    public DeliveryDto getByOrderId(UUID orderId) {
        return deliveryRepository.findByOrderId(orderId)
                .map(this::toDto)
                .orElseThrow(() -> new DeliveryNotFoundException("No delivery found for order: " + orderId));
    }

    public DeliveryDto updateStatus(UUID deliveryId, UpdateDeliveryStatusRequestDto request) {
        Delivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new DeliveryNotFoundException("Delivery not found: " + deliveryId));
        delivery.setStatus(request.status());
        if (request.driverName() != null) {
            delivery.setDriverName(request.driverName());
        }
        return toDto(deliveryRepository.save(delivery));
    }

    private DeliveryDto toDto(Delivery delivery) {
        return new DeliveryDto(
                delivery.getId(),
                delivery.getOrderId(),
                delivery.getUsername(),
                delivery.getStatus(),
                delivery.getDriverName(),
                delivery.getCreatedAt()
        );
    }
}
