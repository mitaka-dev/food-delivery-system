package food.ordering.system.kitchen.service.service;

import food.ordering.system.kitchen.service.domain.KitchenTicket;
import food.ordering.system.kitchen.service.enums.TicketStatus;
import food.ordering.system.kitchen.service.exception.TicketNotFoundException;
import food.ordering.system.kitchen.service.record.KitchenTicketDto;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

import java.time.Instant;
import java.util.UUID;

@Service
public class KitchenTicketService {

    private final DynamoDbTable<KitchenTicket> ticketTable;

    public KitchenTicketService(DynamoDbEnhancedClient enhancedClient) {
        this.ticketTable = enhancedClient.table("kitchen-tickets", TableSchema.fromBean(KitchenTicket.class));
    }

    public KitchenTicketDto createTicket(String orderId) {
        KitchenTicket ticket = new KitchenTicket();
        ticket.setTicketId(UUID.randomUUID().toString());
        ticket.setOrderId(orderId);
        ticket.setStatus(TicketStatus.PENDING.name());
        ticket.setCreatedAt(Instant.now().toString());
        ticketTable.putItem(ticket);
        return toDto(ticket);
    }

    public KitchenTicketDto getTicket(String ticketId) {
        KitchenTicket ticket = ticketTable.getItem(Key.builder().partitionValue(ticketId).build());
        if (ticket == null) throw new TicketNotFoundException(ticketId);
        return toDto(ticket);
    }

    public KitchenTicketDto updateStatus(String ticketId, TicketStatus newStatus) {
        KitchenTicket ticket = ticketTable.getItem(Key.builder().partitionValue(ticketId).build());
        if (ticket == null) throw new TicketNotFoundException(ticketId);
        ticket.setStatus(newStatus.name());
        ticketTable.putItem(ticket);
        return toDto(ticket);
    }

    private KitchenTicketDto toDto(KitchenTicket ticket) {
        return new KitchenTicketDto(
                ticket.getTicketId(),
                ticket.getOrderId(),
                TicketStatus.valueOf(ticket.getStatus()),
                ticket.getCreatedAt()
        );
    }
}
