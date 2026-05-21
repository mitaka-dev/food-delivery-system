package food.ordering.system.kitchen.service.exception;

public class TicketNotFoundException extends RuntimeException {
    public TicketNotFoundException(String ticketId) {
        super("Ticket not found: " + ticketId);
    }
}
