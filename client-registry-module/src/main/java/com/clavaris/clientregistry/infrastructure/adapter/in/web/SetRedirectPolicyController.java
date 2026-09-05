package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import com.clavaris.clientregistry.application.usecase.setredirectpolicyforclient.OAuthClientNotFoundException;
import com.clavaris.clientregistry.application.usecase.setredirectpolicyforclient.RedirectUrlNotRegisteredException;
import com.clavaris.clientregistry.application.usecase.setredirectpolicyforclient.SetRedirectPolicyForClientCommand;
import com.clavaris.clientregistry.application.usecase.setredirectpolicyforclient.SetRedirectPolicyForClientResult;
import com.clavaris.clientregistry.application.usecase.setredirectpolicyforclient.SetRedirectPolicyForClientUseCase;
import com.clavaris.common.domain.model.AuditActor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Clerk "customize redirect URLs" parity: {@code PUT
 * /api/v1/admin/organizations/{organizationId}/clients/{oauthClientId}/redirect-policy} —
 * operator-managed only in v1, same posture as {@code SetRateLimitPolicyController}.
 * Authentication/authorization enforced by {@code app}'s own {@code AdminApiSecurityConfig}, not
 * here.
 */
@RestController
class SetRedirectPolicyController {

  private final SetRedirectPolicyForClientUseCase useCase;

  /* package */ SetRedirectPolicyController(final SetRedirectPolicyForClientUseCase useCase) {
    this.useCase = useCase;
  }

  // Three exits (404 on a bogus organizationId/oauthClientId pair, 400 on an unregistered URL,
  // 200 on success) — same rationale as SetRateLimitPolicyController's own suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @Operation(summary = "Set an OAuthClient's post-authentication redirect policy")
  @ApiResponse(responseCode = "200", description = "Policy created or updated")
  @ApiResponse(
      responseCode = "400",
      description = "A configured URL is not a registered redirectUri for this OAuthClient")
  @ApiResponse(
      responseCode = "404",
      description = "No OAuthClient exists with the given id under this Organization")
  @PutMapping(
      "/api/v1/admin/organizations/{organizationId}/clients/{oauthClientId}/redirect-policy")
  /* package */ ResponseEntity<SetRedirectPolicyResponse> set(
      @PathVariable final UUID organizationId,
      @PathVariable final UUID oauthClientId,
      @RequestBody final SetRedirectPolicyRequest request,
      final Authentication authentication) {
    final SetRedirectPolicyForClientResult result;
    try {
      result =
          useCase.handle(
              new SetRedirectPolicyForClientCommand(
                  organizationId,
                  oauthClientId,
                  request.fallbackSignInRedirectUrl(),
                  request.fallbackSignUpRedirectUrl(),
                  request.forceSignInRedirectUrl(),
                  request.forceSignUpRedirectUrl(),
                  AuditActor.platformClient(authentication.getName())));
    } catch (final OAuthClientNotFoundException _) {
      return ResponseEntity.notFound().build();
    } catch (final RedirectUrlNotRegisteredException _) {
      return ResponseEntity.badRequest().build();
    }
    return ResponseEntity.ok(SetRedirectPolicyResponse.from(result.policy()));
  }
}
