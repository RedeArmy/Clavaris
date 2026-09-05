package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.application.usecase.setaccountauthenticationpolicyfororganization.OrganizationNotFoundException;
import com.clavaris.organization.application.usecase.setaccountauthenticationpolicyfororganization.PasswordOptionalRequiresPasswordlessSignInException;
import com.clavaris.organization.application.usecase.setaccountauthenticationpolicyfororganization.SetAccountAuthenticationPolicyForOrganizationCommand;
import com.clavaris.organization.application.usecase.setaccountauthenticationpolicyfororganization.SetAccountAuthenticationPolicyForOrganizationResult;
import com.clavaris.organization.application.usecase.setaccountauthenticationpolicyfororganization.SetAccountAuthenticationPolicyForOrganizationUseCase;
import com.clavaris.organization.application.usecase.setaccountauthenticationpolicyfororganization.UsernameRequiredWithoutSignUpException;
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
 * ADR-0024: {@code PUT /api/v1/admin/organizations/{organizationId}/authentication-policy} —
 * operator-managed only in v1, same platform-tier-only gating {@code AdminApiSecurityConfig}
 * enforces for every other {@code /api/v1/admin/**} endpoint. This endpoint can only ever tune the
 * strategies named on {@link com.clavaris.organization.domain.model.AccountAuthenticationPolicy} —
 * it has no code path that touches email/password availability itself, which stays permanently on
 * for every tenant regardless of what this endpoint does.
 */
@RestController
class SetAccountAuthenticationPolicyController {

  private final SetAccountAuthenticationPolicyForOrganizationUseCase useCase;

  /* package */ SetAccountAuthenticationPolicyController(
      final SetAccountAuthenticationPolicyForOrganizationUseCase useCase) {
    this.useCase = useCase;
  }

  // Three exits (404 on a bogus organizationId, 400 on an invalid policy combination, 200 on
  // success) — same rationale as SetSocialLoginPolicyController's own identical suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @Operation(summary = "Set an Organization's sign-up/sign-in options policy (ADR-0024)")
  @ApiResponse(responseCode = "200", description = "Policy created or updated")
  @ApiResponse(
      responseCode = "400",
      description =
          "usernameRequired/usernameSignInEnabled without usernameSignUpEnabled, or"
              + " passwordAtSignUpEnabled=false with no passwordless sign-in method enabled")
  @ApiResponse(responseCode = "404", description = "No Organization exists with the given id")
  @PutMapping("/api/v1/admin/organizations/{organizationId}/authentication-policy")
  /* package */ ResponseEntity<AccountAuthenticationPolicyResponse> set(
      @PathVariable final UUID organizationId,
      @Valid @RequestBody final SetAccountAuthenticationPolicyRequest request,
      final Authentication authentication) {
    final SetAccountAuthenticationPolicyForOrganizationResult result;
    try {
      result =
          useCase.handle(
              new SetAccountAuthenticationPolicyForOrganizationCommand(
                  organizationId,
                  request.emailVerificationRequiredAtSignIn(),
                  request.emailVerificationMethod(),
                  request.emailCodeSignInEnabled(),
                  request.emailLinkSignInEnabled(),
                  request.usernameSignUpEnabled(),
                  request.usernameRequired(),
                  request.usernameSignInEnabled(),
                  request.passwordAtSignUpEnabled(),
                  request.deviceTrustEnabled(),
                  AuditActor.platformClient(authentication.getName())));
    } catch (final OrganizationNotFoundException _) {
      return ResponseEntity.notFound().build();
    } catch (final UsernameRequiredWithoutSignUpException
        | PasswordOptionalRequiresPasswordlessSignInException _) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
    }
    return ResponseEntity.ok(AccountAuthenticationPolicyResponse.from(result.policy()));
  }
}
