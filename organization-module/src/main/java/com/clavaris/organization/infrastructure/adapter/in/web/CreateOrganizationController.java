package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.application.usecase.createorganization.CreateOrganizationCommand;
import com.clavaris.organization.application.usecase.createorganization.CreateOrganizationResult;
import com.clavaris.organization.application.usecase.createorganization.CreateOrganizationUseCase;
import com.clavaris.organization.application.usecase.createorganization.PlatformAccountNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * BR-ORG-06, `api-contract-overview.md` §3: {@code POST /api/v1/admin/organizations} — operator
 * only. Authentication/authorization (platform-tier token, {@code platform:organizations:write}
 * scope) is enforced by {@code app}'s own {@code AdminApiSecurityConfig}, not here — this
 * controller only ever runs once a request has already passed that filter chain, same separation of
 * concerns as everywhere else in the codebase.
 */
@RestController
class CreateOrganizationController {

  private final CreateOrganizationUseCase useCase;

  /* package */ CreateOrganizationController(final CreateOrganizationUseCase useCase) {
    this.useCase = useCase;
  }

  // Two exits (404 on a bogus ownerPlatformAccountId, 201 on success) is clearer here than forcing
  // a single-return shape onto two genuinely different outcomes — same rationale as
  // RegisterOAuthClientController's own suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @Operation(summary = "Create a new Organization (tenant isolation boundary, ADR-0010)")
  @ApiResponse(
      responseCode = "201",
      description =
          "Organization created; initial SigningKey generated and activated synchronously (BR-ORG-06)")
  @ApiResponse(responseCode = "404", description = "No PlatformAccount exists with the given id")
  @PostMapping("/api/v1/admin/organizations")
  /* package */ ResponseEntity<CreateOrganizationResponse> create(
      @Valid @RequestBody final CreateOrganizationRequest request,
      final Authentication authentication) {
    final CreateOrganizationResult result;
    try {
      result =
          useCase.handle(
              new CreateOrganizationCommand(
                  request.name(),
                  request.ownerPlatformAccountId(),
                  // TD-SEC-007: this endpoint is reachable only via a platform-tier
                  // client_credentials token (AdminApiSecurityConfig) — Authentication#getName()
                  // resolves to that token's own client_id, SAS's default `sub` claim for this
                  // grant type, never a PlatformAccount (this is the operator/REST path, distinct
                  // from the self-service dashboard's own actor below).
                  AuditActor.platformClient(authentication.getName())));
    } catch (final PlatformAccountNotFoundException _) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.status(HttpStatus.CREATED).body(CreateOrganizationResponse.from(result));
  }
}
