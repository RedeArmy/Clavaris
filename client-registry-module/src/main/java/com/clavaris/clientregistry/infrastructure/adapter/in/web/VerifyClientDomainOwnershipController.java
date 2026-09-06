package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import com.clavaris.clientregistry.application.usecase.verifyclientdomainownership.ClientDomainConfigNotFoundException;
import com.clavaris.clientregistry.application.usecase.verifyclientdomainownership.OAuthClientNotFoundException;
import com.clavaris.clientregistry.application.usecase.verifyclientdomainownership.VerifyClientDomainOwnershipCommand;
import com.clavaris.clientregistry.application.usecase.verifyclientdomainownership.VerifyClientDomainOwnershipResult;
import com.clavaris.clientregistry.application.usecase.verifyclientdomainownership.VerifyClientDomainOwnershipUseCase;
import com.clavaris.common.domain.model.AuditActor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADR-0009 §2: {@code POST
 * /api/v1/admin/organizations/{organizationId}/clients/{oauthClientId}/domain-config:verify-ownership}
 * — same colon-command convention {@code CreateProductionEnvironmentController} already establishes
 * for an operator-triggered, non-idempotent-in-effect action. Admin-triggered, not a background
 * poller — see {@code VerifyClientDomainOwnershipService}'s own Javadoc. A response of {@code 200}
 * with {@code verificationStatus: "FAILED"} is a normal, retryable outcome, never an error — the
 * DNS lookup simply didn't find the expected record yet.
 */
@RestController
class VerifyClientDomainOwnershipController {

  private final VerifyClientDomainOwnershipUseCase useCase;

  /* package */ VerifyClientDomainOwnershipController(
      final VerifyClientDomainOwnershipUseCase useCase) {
    this.useCase = useCase;
  }

  // Three exits (404 on a bogus organizationId/oauthClientId pair, 404 on no domain ever
  // requested, 200 on a completed attempt whether VERIFIED or FAILED) — same rationale as every
  // other multi-exit controller in this module.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @Operation(
      summary = "Verify an OAuthClient's custom domain ownership via DNS TXT record (ADR-0009 §2)")
  @ApiResponse(
      responseCode = "200",
      description = "Verification attempted — check verificationStatus for the outcome")
  @ApiResponse(
      responseCode = "404",
      description =
          "No OAuthClient exists with the given id under this Organization, or no domain has ever"
              + " been requested for it")
  @PostMapping(
      "/api/v1/admin/organizations/{organizationId}/clients/{oauthClientId}/domain-config:verify-ownership")
  /* package */ ResponseEntity<ClientDomainConfigResponse> verify(
      @PathVariable final UUID organizationId,
      @PathVariable final UUID oauthClientId,
      final Authentication authentication) {
    final VerifyClientDomainOwnershipResult result;
    try {
      result =
          useCase.handle(
              new VerifyClientDomainOwnershipCommand(
                  organizationId,
                  oauthClientId,
                  AuditActor.platformClient(authentication.getName())));
    } catch (final OAuthClientNotFoundException | ClientDomainConfigNotFoundException _) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(ClientDomainConfigResponse.from(result.config()));
  }
}
