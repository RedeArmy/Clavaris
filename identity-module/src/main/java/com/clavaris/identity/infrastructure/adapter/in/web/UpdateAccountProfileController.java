package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.registeraccount.UsernameAlreadyRegisteredException;
import com.clavaris.identity.application.usecase.updateaccountprofile.AccountNotFoundException;
import com.clavaris.identity.application.usecase.updateaccountprofile.UpdateAccountProfileCommand;
import com.clavaris.identity.application.usecase.updateaccountprofile.UpdateAccountProfileUseCase;
import com.clavaris.identity.domain.model.AccountId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * TD-FUT-041: {@code PUT /api/v1/admin/accounts/{id}/profile} — Backend-API-style profile write for
 * a consuming application's own backend, Clerk {@code clerkClient.users.updateUser} parity. Reuses
 * {@link UpdateAccountProfileUseCase} exactly as it already stood (built for {@code
 * AccountProfileController}'s self-service caller and {@code
 * PlatformAccountProfileAdminController}'s operator-driven one, TD-FUT-040) — this is its third
 * caller, not a fourth implementation. Same dual-authority reachability as {@link
 * GetAccountProfileController}; see that class's own Javadoc for the scope this shares with it.
 */
// PMD.OnlyOneReturn: three genuinely distinct exits (404 unknown account, 409 username conflict,
// 204 success) — same rationale UpdateAccountMetadataController's own identical suppression
// documents. PMD.ShortVariable: id matches the path variable's own wire name (and
// UpdateAccountMetadataController's own identically-named parameter) — not an organically short
// name that should grow.
@SuppressWarnings({"PMD.OnlyOneReturn", "PMD.ShortVariable"})
@RestController
class UpdateAccountProfileController {

  private final UpdateAccountProfileUseCase useCase;

  /* package */ UpdateAccountProfileController(final UpdateAccountProfileUseCase useCase) {
    this.useCase = useCase;
  }

  @Operation(summary = "Update an Account's firstName/lastName/username/phoneNumber (TD-FUT-041)")
  @ApiResponse(responseCode = "204", description = "Profile updated")
  @ApiResponse(responseCode = "404", description = "No Account exists with the given id")
  @ApiResponse(responseCode = "409", description = "The submitted username is already taken")
  @PutMapping("/api/v1/admin/accounts/{id}/profile")
  /* package */ ResponseEntity<Void> update(
      @PathVariable final UUID id,
      @RequestBody final UpdateAccountProfileRequest request,
      final Authentication authentication) {
    try {
      useCase.handle(
          new UpdateAccountProfileCommand(
              new AccountId(id),
              request.firstName(),
              request.lastName(),
              request.username(),
              request.phoneNumber(),
              AuditActor.platformClient(authentication.getName())));
    } catch (final AccountNotFoundException _) {
      return ResponseEntity.notFound().build();
    } catch (final UsernameAlreadyRegisteredException _) {
      return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }
    return ResponseEntity.noContent().build();
  }
}
