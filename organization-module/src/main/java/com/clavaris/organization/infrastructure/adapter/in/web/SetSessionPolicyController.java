package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.application.usecase.setsessionpolicyfororganization.OrganizationNotFoundException;
import com.clavaris.organization.application.usecase.setsessionpolicyfororganization.SetSessionPolicyForOrganizationCommand;
import com.clavaris.organization.application.usecase.setsessionpolicyfororganization.SetSessionPolicyForOrganizationResult;
import com.clavaris.organization.application.usecase.setsessionpolicyfororganization.SetSessionPolicyForOrganizationUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code PUT /api/v1/admin/organizations/{organizationId}/session-policy} — {@code
 * PlatformClient}-gated (never a tenant's own token — {@code AdminApiSecurityConfig} enforces
 * platform-tier-only, same as every other {@code /api/v1/admin/**} endpoint), deliberately unscoped
 * to any one Organization. Same dual-caller shape as {@code SetRateLimitPolicyController}'s own
 * Javadoc documents — a second, ownership-scoped caller exists too ({@code
 * PlatformSessionPolicyController}, the dashboard), both calling the exact same {@link
 * SetSessionPolicyForOrganizationUseCase}.
 */
@RestController
class SetSessionPolicyController {

  private final SetSessionPolicyForOrganizationUseCase useCase;

  /* package */ SetSessionPolicyController(final SetSessionPolicyForOrganizationUseCase useCase) {
    this.useCase = useCase;
  }

  // Two exits (404 on a bogus organizationId, 200 on success) — same rationale as every sibling
  // policy controller's own identical suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @Operation(summary = "Set an Organization's session policy (Clerk Sessions parity)")
  @ApiResponse(responseCode = "200", description = "Policy created or updated")
  @ApiResponse(responseCode = "404", description = "No Organization exists with the given id")
  @PutMapping("/api/v1/admin/organizations/{organizationId}/session-policy")
  /* package */ ResponseEntity<SetSessionPolicyResponse> set(
      @PathVariable final UUID organizationId,
      @Valid @RequestBody final SetSessionPolicyRequest request,
      final Authentication authentication) {
    final SetSessionPolicyForOrganizationResult result;
    try {
      result =
          useCase.handle(
              new SetSessionPolicyForOrganizationCommand(
                  organizationId,
                  request.maximumLifetimeMinutes(),
                  request.inactivityTimeoutMinutes(),
                  request.reverificationWindowMinutes(),
                  request.multiSessionHandlingEnabled(),
                  // TD-SEC-007: AdminApiSecurityConfig gates this endpoint to a platform-tier
                  // client_credentials token only — same actor-resolution rationale as every
                  // sibling policy REST endpoint.
                  AuditActor.platformClient(authentication.getName())));
    } catch (final OrganizationNotFoundException _) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(SetSessionPolicyResponse.from(result.policy()));
  }
}
