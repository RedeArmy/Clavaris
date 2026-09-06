package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import com.clavaris.clientregistry.application.usecase.getclientbranding.GetClientBrandingUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET .../branding} — always 200, never 404, same "an unconfigured OAuthClient's implicit
 * defaults are a legitimate answer" convention {@link GetRedirectPolicyController} already
 * establishes.
 */
@RestController
class GetClientBrandingController {

  private final GetClientBrandingUseCase useCase;

  /* package */ GetClientBrandingController(final GetClientBrandingUseCase useCase) {
    this.useCase = useCase;
  }

  @Operation(summary = "Read an OAuthClient's hosted-login branding (ADR-0009 §3)")
  @ApiResponse(
      responseCode = "200",
      description = "The configured branding, or all-unconfigured defaults if never set")
  @GetMapping("/api/v1/admin/organizations/{organizationId}/clients/{oauthClientId}/branding")
  /* package */ ResponseEntity<SetClientBrandingResponse> get(
      @PathVariable final UUID organizationId, @PathVariable final UUID oauthClientId) {
    return ResponseEntity.ok(SetClientBrandingResponse.from(useCase.handle(oauthClientId)));
  }
}
