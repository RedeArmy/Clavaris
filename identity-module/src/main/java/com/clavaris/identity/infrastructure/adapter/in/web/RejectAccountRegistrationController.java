package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.rejectaccountregistration.AccountNotFoundException;
import com.clavaris.identity.application.usecase.rejectaccountregistration.RejectAccountRegistrationCommand;
import com.clavaris.identity.application.usecase.rejectaccountregistration.RejectAccountRegistrationUseCase;
import com.clavaris.identity.domain.model.AccountId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/admin/accounts/{id}:reject-registration} — TD-FUT-019: rejects a {@code
 * PENDING_APPROVAL} self-registration. Same dual-reachability rationale ({@code PlatformClient} or
 * the tenant's own {@code OrganizationClient} via {@code OrganizationClientOwnershipFilter}) as
 * {@code ApproveAccountRegistrationController}'s own Javadoc. The request body is optional — an
 * absent/empty body means no reason was given, not a malformed request.
 */
@RestController
class RejectAccountRegistrationController {

  private final RejectAccountRegistrationUseCase useCase;

  /* package */ RejectAccountRegistrationController(
      final RejectAccountRegistrationUseCase useCase) {
    this.useCase = useCase;
  }

  // Two exits (404 on an unknown id, 204 on success) — same rationale as
  // ApproveAccountRegistrationController's own identical suppression.
  @SuppressWarnings({"PMD.OnlyOneReturn", "PMD.ShortVariable"})
  @Operation(summary = "Reject a PENDING_APPROVAL self-registration")
  @ApiResponse(responseCode = "204", description = "Rejected — Account is now REJECTED")
  @ApiResponse(responseCode = "404", description = "No Account exists with the given id")
  @PostMapping("/api/v1/admin/accounts/{id}:reject-registration")
  /* package */ ResponseEntity<Void> reject(
      @PathVariable final UUID id,
      @RequestBody(required = false) final RejectAccountRegistrationRequest request,
      final Authentication authentication) {
    final String reason = request == null ? null : request.reason();
    try {
      useCase.handle(
          new RejectAccountRegistrationCommand(
              new AccountId(id), reason, AuditActor.platformClient(authentication.getName())));
    } catch (final AccountNotFoundException _) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.noContent().build();
  }
}
