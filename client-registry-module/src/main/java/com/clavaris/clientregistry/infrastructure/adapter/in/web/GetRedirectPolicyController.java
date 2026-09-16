package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import com.clavaris.clientregistry.application.usecase.getredirectpolicyforclient.GetRedirectPolicyForClientUseCase;
import com.clavaris.clientregistry.application.usecase.getredirectpolicyforclient.OAuthClientNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET .../redirect-policy} — always 200 for a real, owned {@code OAuthClient} (never 404 for
 * an unconfigured-but-real one), same "an unconfigured OAuthClient's implicit defaults are a
 * legitimate answer" convention {@code GetAccountAuthenticationPolicyController} already
 * establishes for its own read side.
 */
@RestController
class GetRedirectPolicyController {

  private final GetRedirectPolicyForClientUseCase useCase;

  /* package */ GetRedirectPolicyController(final GetRedirectPolicyForClientUseCase useCase) {
    this.useCase = useCase;
  }

  // Two exits (404 on a bogus organizationId/oauthClientId pair, 200 on success) — same rationale
  // as GetClientBrandingController's own identical suppression.
  //
  // SDE-III review, 2026-09-15 — real gap found and closed: organizationId used to be accepted as
  // a path segment purely for URL symmetry, never actually used to scope the lookup, on the
  // reasoning that RedirectPolicy "carries nothing sensitive." That reasoning didn't hold: a
  // configured fallback/force redirect URL is still one Organization's own client configuration, a
  // real cross-tenant read this module itself should never allow through regardless of how
  // sensitive the payload is — the same defense-in-depth posture GetClientBrandingController/
  // GetClientDomainConfigController's own identical checks already establish. See
  // GetRedirectPolicyForClientService's own Javadoc for the module-level check this now relies on
  // instead of app-layer plumbing that was never actually wired for this route anyway.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @Operation(summary = "Read an OAuthClient's post-authentication redirect policy")
  @ApiResponse(
      responseCode = "200",
      description = "The configured policy, or all-unconfigured defaults if never set")
  @ApiResponse(
      responseCode = "404",
      description = "No OAuthClient exists with the given id under this Organization")
  @GetMapping(
      "/api/v1/admin/organizations/{organizationId}/clients/{oauthClientId}/redirect-policy")
  /* package */ ResponseEntity<SetRedirectPolicyResponse> get(
      @PathVariable final UUID organizationId, @PathVariable final UUID oauthClientId) {
    try {
      return ResponseEntity.ok(
          SetRedirectPolicyResponse.from(useCase.handle(organizationId, oauthClientId)));
    } catch (final OAuthClientNotFoundException _) {
      return ResponseEntity.notFound().build();
    }
  }
}
