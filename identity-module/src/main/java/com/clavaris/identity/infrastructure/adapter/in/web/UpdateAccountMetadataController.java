package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.updateaccountmetadata.AccountNotFoundException;
import com.clavaris.identity.application.usecase.updateaccountmetadata.InvalidMetadataException;
import com.clavaris.identity.application.usecase.updateaccountmetadata.UpdateAccountMetadataCommand;
import com.clavaris.identity.application.usecase.updateaccountmetadata.UpdateAccountMetadataUseCase;
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
 * {@code PUT /api/v1/admin/accounts/{id}/metadata} — TD-FUT-034, Clerk "Metadata" parity. Reachable
 * by both the platform operator's own {@code PlatformClient} and, via {@code
 * OrganizationClientOwnershipFilter}'s {@code oneHopAccount} allowlist entry for this route, a
 * tenant's own {@code OrganizationClient} ("Secret Key") — the same dual-authority shape TD-FUT-019
 * already established, since reading/writing an Account's own application-defined metadata is
 * exactly the kind of thing a consuming application's backend needs to do routinely, not only the
 * Clavaris operator.
 */
@RestController
class UpdateAccountMetadataController {

  private final UpdateAccountMetadataUseCase useCase;

  /* package */ UpdateAccountMetadataController(final UpdateAccountMetadataUseCase useCase) {
    this.useCase = useCase;
  }

  // Three exits (404 on an unknown id, 400 on invalid JSON/oversized tier, 204 on success) — same
  // rationale as SetAccountAuthenticationPolicyController's own identical suppression.
  @SuppressWarnings({"PMD.OnlyOneReturn", "PMD.ShortVariable"})
  @Operation(summary = "Replace an Account's public/private/unsafe metadata (TD-FUT-034)")
  @ApiResponse(responseCode = "204", description = "Metadata replaced")
  @ApiResponse(
      responseCode = "400",
      description = "A non-blank tier isn't valid JSON, or is too long")
  @ApiResponse(responseCode = "404", description = "No Account exists with the given id")
  @PutMapping("/api/v1/admin/accounts/{id}/metadata")
  /* package */ ResponseEntity<Void> update(
      @PathVariable final UUID id,
      @RequestBody final UpdateAccountMetadataRequest request,
      final Authentication authentication) {
    try {
      useCase.handle(
          new UpdateAccountMetadataCommand(
              new AccountId(id),
              request.publicMetadata(),
              request.privateMetadata(),
              request.unsafeMetadata(),
              AuditActor.platformClient(authentication.getName())));
    } catch (final AccountNotFoundException _) {
      return ResponseEntity.notFound().build();
    } catch (final InvalidMetadataException _) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
    }
    return ResponseEntity.noContent().build();
  }
}
