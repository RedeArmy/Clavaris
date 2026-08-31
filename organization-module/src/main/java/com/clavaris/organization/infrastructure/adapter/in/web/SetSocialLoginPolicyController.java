package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.application.usecase.setsocialloginpolicyfororganization.OrganizationNotFoundException;
import com.clavaris.organization.application.usecase.setsocialloginpolicyfororganization.SetSocialLoginPolicyForOrganizationCommand;
import com.clavaris.organization.application.usecase.setsocialloginpolicyfororganization.SetSocialLoginPolicyForOrganizationResult;
import com.clavaris.organization.application.usecase.setsocialloginpolicyfororganization.SetSocialLoginPolicyForOrganizationUseCase;
import com.clavaris.organization.application.usecase.setsocialloginpolicyfororganization.UnknownSocialProviderException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADR-0020 Decision 3, BR-ID-12: {@code PUT
 * /api/v1/admin/organizations/{organizationId}/social-login-policy} — operator-managed only in v1,
 * same platform-tier-only gating {@code AdminApiSecurityConfig} enforces for every other {@code
 * /api/v1/admin/**} endpoint. This endpoint can only ever turn social login on/off and choose which
 * providers — it has no code path that touches email/password availability at all, which stays
 * permanently on for every tenant regardless of what this endpoint does.
 */
@RestController
class SetSocialLoginPolicyController {

  private final SetSocialLoginPolicyForOrganizationUseCase useCase;

  /* package */ SetSocialLoginPolicyController(
      final SetSocialLoginPolicyForOrganizationUseCase useCase) {
    this.useCase = useCase;
  }

  // Three exits (404 on a bogus organizationId, 400 on an unknown provider name, 200 on success) —
  // same rationale as SetRateLimitPolicyController's own suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @Operation(summary = "Set an Organization's social-login policy (ADR-0020 Decision 3)")
  @ApiResponse(responseCode = "200", description = "Policy created or updated")
  @ApiResponse(
      responseCode = "400",
      description = "providers names a provider outside the known allowlist")
  @ApiResponse(responseCode = "404", description = "No Organization exists with the given id")
  @PutMapping("/api/v1/admin/organizations/{organizationId}/social-login-policy")
  /* package */ ResponseEntity<SetSocialLoginPolicyResponse> set(
      @PathVariable final UUID organizationId,
      @Valid @RequestBody final SetSocialLoginPolicyRequest request,
      final Authentication authentication) {
    final SetSocialLoginPolicyForOrganizationResult result;
    try {
      result =
          useCase.handle(
              new SetSocialLoginPolicyForOrganizationCommand(
                  organizationId,
                  request.enabled(),
                  request.providers(),
                  // TD-SEC-007: AdminApiSecurityConfig gates this endpoint to a platform-tier
                  // client_credentials token only — same actor-resolution rationale as
                  // SetRateLimitPolicyController's own REST path.
                  AuditActor.platformClient(authentication.getName())));
    } catch (final OrganizationNotFoundException _) {
      return ResponseEntity.notFound().build();
    } catch (final UnknownSocialProviderException _) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
    }
    return ResponseEntity.ok(SetSocialLoginPolicyResponse.from(result.organization()));
  }
}
