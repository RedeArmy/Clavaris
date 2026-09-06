package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import com.clavaris.clientregistry.application.usecase.setclientbranding.OAuthClientNotFoundException;
import com.clavaris.clientregistry.application.usecase.setclientbranding.SetClientBrandingCommand;
import com.clavaris.clientregistry.application.usecase.setclientbranding.SetClientBrandingResult;
import com.clavaris.clientregistry.application.usecase.setclientbranding.SetClientBrandingUseCase;
import com.clavaris.common.domain.model.AuditActor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADR-0009 §3: {@code PUT
 * /api/v1/admin/organizations/{organizationId}/clients/{oauthClientId}/branding} — operator-managed
 * only in v1, same posture as {@link SetRedirectPolicyController}.
 */
@RestController
class SetClientBrandingController {

  private final SetClientBrandingUseCase useCase;

  /* package */ SetClientBrandingController(final SetClientBrandingUseCase useCase) {
    this.useCase = useCase;
  }

  // Three exits (404 on a bogus organizationId/oauthClientId pair, 400 on invalid branding
  // content, 200 on success) — same rationale as SetRateLimitPolicyController's own identical
  // suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @Operation(summary = "Set an OAuthClient's hosted-login branding (ADR-0009 §3)")
  @ApiResponse(responseCode = "200", description = "Branding created or updated")
  @ApiResponse(
      responseCode = "400",
      description =
          "logoUrl/primaryColor/applicationDisplayName failed ClientBranding's own validation")
  @ApiResponse(
      responseCode = "404",
      description = "No OAuthClient exists with the given id under this Organization")
  @PutMapping("/api/v1/admin/organizations/{organizationId}/clients/{oauthClientId}/branding")
  /* package */ ResponseEntity<SetClientBrandingResponse> set(
      @PathVariable final UUID organizationId,
      @PathVariable final UUID oauthClientId,
      @RequestBody final SetClientBrandingRequest request,
      final Authentication authentication) {
    final SetClientBrandingResult result;
    try {
      result =
          useCase.handle(
              new SetClientBrandingCommand(
                  organizationId,
                  oauthClientId,
                  request.logoUrl(),
                  request.primaryColor(),
                  request.applicationDisplayName(),
                  AuditActor.platformClient(authentication.getName())));
    } catch (final OAuthClientNotFoundException _) {
      return ResponseEntity.notFound().build();
    } catch (final IllegalArgumentException _) {
      // ClientBranding's own factory/update methods throw this for a malformed logoUrl, an
      // invalid primaryColor, or an out-of-bounds applicationDisplayName — same
      // SetRateLimitPolicyController precedent for surfacing a domain constructor's validation as
      // 400 rather than letting it fall through to GlobalExceptionHandler's generic 500.
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
    }
    return ResponseEntity.ok(SetClientBrandingResponse.from(result.branding()));
  }
}
