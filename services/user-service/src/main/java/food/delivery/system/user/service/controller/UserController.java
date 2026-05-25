package food.delivery.system.user.service.controller;

import food.delivery.system.user.service.record.UpdateProfileRequest;
import food.delivery.system.user.service.record.UserProfileResponse;
import food.delivery.system.user.service.service.UserProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Users", description = "User profile management")
@RestController
@RequestMapping("/api/v1/users")
@SecurityRequirement(name = "bearerAuth")
public class UserController {

    private final UserProfileService profileService;

    public UserController(UserProfileService profileService) {
        this.profileService = profileService;
    }

    @Operation(summary = "Get my profile")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Profile returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid token")
    })
    @GetMapping("/me")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<UserProfileResponse> getMe(Authentication auth) {
        return ResponseEntity.ok(profileService.getProfile(auth.getName()));
    }

    @Operation(summary = "Update my profile", description = "Partial update — only supplied fields are changed.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated profile returned"),
            @ApiResponse(responseCode = "400", description = "Validation error"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid token")
    })
    @PatchMapping("/me")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<UserProfileResponse> updateMe(
            @Valid @RequestBody UpdateProfileRequest req,
            Authentication auth) {
        return ResponseEntity.ok(profileService.updateProfile(auth.getName(), req));
    }
}
