package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import com.clavaris.clientregistry.application.usecase.getclientdomainconfig.GetClientDomainConfigUseCase;
import com.clavaris.clientregistry.application.usecase.getclientdomainconfig.OAuthClientNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET .../domain-config} — always 200 for a real, owned {@code OAuthClient} (never 404 for
 * an unconfigured-but-real one), same "an unconfigured OAuthClient's implicit {@code SHARED}-mode
 * default is a legitimate answer" convention {@link GetClientBrandingController} already
 * establishes.
 */
@RestController
class GetClientDomainConfigController {

  private final GetClientDomainConfigUseCase useCase;

  /* package */ GetClientDomainConfigController(final GetClientDomainConfigUseCase useCase) {
    this.useCase = useCase;
  }

  // Two exits (404 on a bogus organizationId/oauthClientId pair, 200 on success) — same rationale
  // as GetClientBrandingController's own identical suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @Operation(summary = "Read an OAuthClient's custom domain configuration (ADR-0009 §2)")
  @ApiResponse(
      responseCode = "200",
      description =
          "The configured domain, or all-unconfigured (SHARED mode) defaults if never set")
  @ApiResponse(
      responseCode = "404",
      description = "No OAuthClient exists with the given id under this Organization")
  @GetMapping("/api/v1/admin/organizations/{organizationId}/clients/{oauthClientId}/domain-config")
  /* package */ ResponseEntity<ClientDomainConfigResponse> get(
      @PathVariable final UUID organizationId, @PathVariable final UUID oauthClientId) {
    try {
      return ResponseEntity.ok(
          ClientDomainConfigResponse.from(useCase.handle(organizationId, oauthClientId)));
    } catch (final OAuthClientNotFoundException _) {
      return ResponseEntity.notFound().build();
    }
  }
}
