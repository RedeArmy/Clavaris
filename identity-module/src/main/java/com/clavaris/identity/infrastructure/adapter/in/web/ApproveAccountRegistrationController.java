package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.approveaccountregistration.AccountNotFoundException;
import com.clavaris.identity.application.usecase.approveaccountregistration.ApproveAccountRegistrationCommand;
import com.clavaris.identity.application.usecase.approveaccountregistration.ApproveAccountRegistrationUseCase;
import com.clavaris.identity.domain.model.AccountId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/admin/accounts/{id}:approve-registration} — TD-FUT-019: approves a {@code
 * PENDING_APPROVAL} self-registration. Same {@code :action}-on-resource naming precedent as {@code
 * SuspendAccountController}. Reachable by both a {@code PlatformClient} (operator, any
 * Organization) and a tenant's own {@code OrganizationClient} scoped to its own Organization, via
 * {@code OrganizationClientOwnershipFilter}'s {@code oneHopAccount} allowlist entry for this route
 * — this dual reachability is what lets a consuming application's own backend decide approval
 * itself, per TD-FUT-019's own confirmed scope.
 */
@RestController
class ApproveAccountRegistrationController {

  private final ApproveAccountRegistrationUseCase useCase;

  /* package */ ApproveAccountRegistrationController(
      final ApproveAccountRegistrationUseCase useCase) {
    this.useCase = useCase;
  }

  // Two exits (404 on an unknown id, 204 on success) — same rationale as SuspendAccountController's
  // own identical suppression.
  @SuppressWarnings({"PMD.OnlyOneReturn", "PMD.ShortVariable"})
  @Operation(summary = "Approve a PENDING_APPROVAL self-registration")
  @ApiResponse(responseCode = "204", description = "Approved — Account is now ACTIVE")
  @ApiResponse(responseCode = "404", description = "No Account exists with the given id")
  @PostMapping("/api/v1/admin/accounts/{id}:approve-registration")
  /* package */ ResponseEntity<Void> approve(
      @PathVariable final UUID id, final Authentication authentication) {
    try {
      useCase.handle(
          new ApproveAccountRegistrationCommand(
              new AccountId(id), AuditActor.platformClient(authentication.getName())));
    } catch (final AccountNotFoundException _) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.noContent().build();
  }
}
