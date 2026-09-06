package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import com.clavaris.clientregistry.application.usecase.requestclientdomainconfig.HostnameAlreadyClaimedException;
import com.clavaris.clientregistry.application.usecase.requestclientdomainconfig.OAuthClientNotFoundException;
import com.clavaris.clientregistry.application.usecase.requestclientdomainconfig.RequestClientDomainConfigCommand;
import com.clavaris.clientregistry.application.usecase.requestclientdomainconfig.RequestClientDomainConfigResult;
import com.clavaris.clientregistry.application.usecase.requestclientdomainconfig.RequestClientDomainConfigUseCase;
import com.clavaris.common.domain.model.AuditActor;
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
 * ADR-0009 §2: {@code PUT
 * /api/v1/admin/organizations/{organizationId}/clients/{oauthClientId}/domain-config} —
 * operator-managed only in v1, same posture as {@link SetClientBrandingController}. Always mints a
 * fresh DNS TXT challenge and resets to {@code PENDING}, whether this is a first-time request or a
 * re-request — see {@code RequestClientDomainConfigService}'s own Javadoc.
 */
@RestController
class RequestClientDomainConfigController {

  private final RequestClientDomainConfigUseCase useCase;

  /* package */ RequestClientDomainConfigController(
      final RequestClientDomainConfigUseCase useCase) {
    this.useCase = useCase;
  }

  // Four exits (404 on a bogus organizationId/oauthClientId pair, 409 on a hostname already
  // claimed elsewhere, 400 on invalid domain content, 200 on success) — same rationale as
  // SetRateLimitPolicyController's own identical suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @Operation(summary = "Request/re-request an OAuthClient's custom domain (ADR-0009 §2)")
  @ApiResponse(responseCode = "200", description = "Domain request created or updated, PENDING")
  @ApiResponse(
      responseCode = "400",
      description = "hostname failed ClientDomainConfig's own validation")
  @ApiResponse(
      responseCode = "404",
      description = "No OAuthClient exists with the given id under this Organization")
  @ApiResponse(
      responseCode = "409",
      description = "hostname is already claimed by a different OAuthClient")
  @PutMapping("/api/v1/admin/organizations/{organizationId}/clients/{oauthClientId}/domain-config")
  /* package */ ResponseEntity<ClientDomainConfigResponse> request(
      @PathVariable final UUID organizationId,
      @PathVariable final UUID oauthClientId,
      @Valid @RequestBody final RequestClientDomainConfigRequest request,
      final Authentication authentication) {
    final RequestClientDomainConfigResult result;
    try {
      result =
          useCase.handle(
              new RequestClientDomainConfigCommand(
                  organizationId,
                  oauthClientId,
                  request.mode(),
                  request.hostname(),
                  request.embeddingOrigin(),
                  AuditActor.platformClient(authentication.getName())));
    } catch (final OAuthClientNotFoundException _) {
      return ResponseEntity.notFound().build();
    } catch (final HostnameAlreadyClaimedException _) {
      return ResponseEntity.status(HttpStatus.CONFLICT).build();
    } catch (final IllegalArgumentException _) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
    }
    return ResponseEntity.ok(ClientDomainConfigResponse.from(result.config()));
  }
}
