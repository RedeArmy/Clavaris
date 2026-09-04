package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.application.usecase.setorganizationsocialcredential.OrganizationNotFoundException;
import com.clavaris.organization.application.usecase.setorganizationsocialcredential.OrganizationNotProductionException;
import com.clavaris.organization.application.usecase.setorganizationsocialcredential.SetOrganizationSocialCredentialCommand;
import com.clavaris.organization.application.usecase.setorganizationsocialcredential.SetOrganizationSocialCredentialResult;
import com.clavaris.organization.application.usecase.setorganizationsocialcredential.SetOrganizationSocialCredentialUseCase;
import com.clavaris.organization.application.usecase.setorganizationsocialcredential.SocialLoginNotEnabledForProviderException;
import com.clavaris.organization.domain.model.SocialProvider;
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
 * ADR-0022 (amending ADR-0020 Decision 4): {@code PUT
 * /api/v1/admin/organizations/{organizationId}/social-credentials/{provider}} — operator-managed
 * only in v1, same platform-tier-only gating {@code AdminApiSecurityConfig} enforces for every
 * other {@code /api/v1/admin/**} endpoint. PRODUCTION-only, additive on top of ADR-0020 Decision
 * 3's own enabled/allowlist gate — see {@code SetOrganizationSocialCredentialService}'s own
 * Javadoc.
 */
@RestController
class SetOrganizationSocialCredentialController {

  private final SetOrganizationSocialCredentialUseCase useCase;

  /* package */ SetOrganizationSocialCredentialController(
      final SetOrganizationSocialCredentialUseCase useCase) {
    this.useCase = useCase;
  }

  // Three exits (404 on a bogus organizationId, 409 on a guard violation, 200 on success) — same
  // rationale as CreateProductionEnvironmentController's own identical suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @Operation(
      summary = "Set an Organization's own OAuth social credentials for one provider (ADR-0022)")
  @ApiResponse(responseCode = "200", description = "Credential created or updated")
  @ApiResponse(
      responseCode = "409",
      description =
          "Organization is not PRODUCTION, or social login is not enabled for this provider")
  @ApiResponse(responseCode = "404", description = "No Organization exists with the given id")
  @PutMapping("/api/v1/admin/organizations/{organizationId}/social-credentials/{provider}")
  /* package */ ResponseEntity<SetOrganizationSocialCredentialResponse> set(
      @PathVariable final UUID organizationId,
      @PathVariable final SocialProvider provider,
      @Valid @RequestBody final SetOrganizationSocialCredentialRequest request,
      final Authentication authentication) {
    final SetOrganizationSocialCredentialResult result;
    try {
      result =
          useCase.handle(
              new SetOrganizationSocialCredentialCommand(
                  organizationId,
                  provider,
                  request.clientId(),
                  request.clientSecret(),
                  AuditActor.platformClient(authentication.getName())));
    } catch (final OrganizationNotFoundException _) {
      return ResponseEntity.notFound().build();
    } catch (final OrganizationNotProductionException
        | SocialLoginNotEnabledForProviderException _) {
      return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }
    return ResponseEntity.ok(SetOrganizationSocialCredentialResponse.from(result));
  }
}
