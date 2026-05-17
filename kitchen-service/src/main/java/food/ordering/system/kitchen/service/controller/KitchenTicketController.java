package food.ordering.system.kitchen.service.controller;

import food.ordering.system.kitchen.service.record.KitchenTicketDto;
import food.ordering.system.kitchen.service.record.UpdateTicketStatusRequestDto;
import food.ordering.system.kitchen.service.service.KitchenTicketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/kitchen/tickets")
@Tag(name = "Kitchen Tickets", description = "Kitchen ticket management — view and update preparation status")
public class KitchenTicketController {

    private final KitchenTicketService kitchenTicketService;

    public KitchenTicketController(KitchenTicketService kitchenTicketService) {
        this.kitchenTicketService = kitchenTicketService;
    }

    @GetMapping("/{ticketId}")
    @Operation(summary = "Get ticket", description = "Returns a kitchen ticket by ID.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Ticket found"),
            @ApiResponse(responseCode = "404", description = "Ticket not found")
    })
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<KitchenTicketDto> getTicket(@PathVariable String ticketId) {
        return ResponseEntity.ok(kitchenTicketService.getTicket(ticketId));
    }

    @PutMapping("/{ticketId}/status")
    @Operation(summary = "Update ticket status", description = "Updates the preparation status of a kitchen ticket.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Status updated"),
            @ApiResponse(responseCode = "400", description = "Validation error"),
            @ApiResponse(responseCode = "404", description = "Ticket not found")
    })
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<KitchenTicketDto> updateStatus(
            @PathVariable String ticketId,
            @RequestBody @Valid UpdateTicketStatusRequestDto request) {
        return ResponseEntity.ok(kitchenTicketService.updateStatus(ticketId, request.status()));
    }
}
