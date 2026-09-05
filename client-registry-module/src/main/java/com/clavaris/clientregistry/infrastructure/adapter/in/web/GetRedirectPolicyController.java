package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import com.clavaris.clientregistry.application.usecase.getredirectpolicyforclient.GetRedirectPolicyForClientUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET .../redirect-policy} — always 200, never 404, same "an unconfigured OAuthClient's
 * implicit defaults are a legitimate answer" convention {@code
 * GetAccountAuthenticationPolicyController} already establishes for its own read side.
 */
@RestController
class GetRedirectPolicyController {

  private final GetRedirectPolicyForClientUseCase useCase;

  /* package */ GetRedirectPolicyController(final GetRedirectPolicyForClientUseCase useCase) {
    this.useCase = useCase;
  }

  @Operation(summary = "Read an OAuthClient's post-authentication redirect policy")
  @ApiResponse(
      responseCode = "200",
      description = "The configured policy, or all-unconfigured defaults if never set")
  @GetMapping(
      "/api/v1/admin/organizations/{organizationId}/clients/{oauthClientId}/redirect-policy")
  /* package */ ResponseEntity<SetRedirectPolicyResponse> get(
      @PathVariable final UUID organizationId, @PathVariable final UUID oauthClientId) {
    // organizationId isn't used to scope the lookup (RedirectPolicy is keyed purely by
    // oauthClientId) — kept as a path segment only for URL symmetry with the PUT endpoint and
    // every other per-client admin route on this surface (SDE-III review: a client-scoped GET
    // that silently ignored a mismatched organizationId in its own URL would be a confusing API,
    // even though it can't leak cross-tenant data — RedirectPolicy carries nothing sensitive).
    return ResponseEntity.ok(SetRedirectPolicyResponse.from(useCase.handle(oauthClientId)));
  }
}
