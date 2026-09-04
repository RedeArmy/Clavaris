package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import com.clavaris.clientregistry.application.usecase.createorganizationclient.CreateOrganizationClientCommand;
import com.clavaris.clientregistry.application.usecase.createorganizationclient.CreateOrganizationClientResult;
import com.clavaris.clientregistry.application.usecase.createorganizationclient.CreateOrganizationClientUseCase;
import com.clavaris.clientregistry.application.usecase.createorganizationclient.OrganizationNotFoundException;
import com.clavaris.common.domain.model.AuditActor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADR-0023: {@code POST /api/v1/admin/organizations/{organizationId}/secret-keys} — mints a new
 * {@code OrganizationClient} (Clerk "Secret Key" parity). Operator-only in v1 (no tenant
 * self-service, same deferral ADR-0010 already states for the rest of this surface) — {@code app}'s
 * own {@code AdminApiSecurityConfig} enforces this via {@code PlatformScopes.SECRET_KEYS_WRITE},
 * same separation of concerns as every other controller here.
 */
@RestController
class CreateOrganizationClientController {

  private final CreateOrganizationClientUseCase useCase;

  /* package */ CreateOrganizationClientController(final CreateOrganizationClientUseCase useCase) {
    this.useCase = useCase;
  }

  // Two exits (404 on a missing Organization, 201 on success) — same rationale as
  // RegisterOAuthClientController's own identical suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @Operation(summary = "Mint a new OrganizationClient / Secret Key for an Organization (ADR-0023)")
  @ApiResponse(
      responseCode = "201",
      description = "Created — clientSecret is shown here exactly once, never retrievable again")
  @ApiResponse(responseCode = "404", description = "No Organization exists with the given id")
  @PostMapping("/api/v1/admin/organizations/{organizationId}/secret-keys")
  /* package */ ResponseEntity<CreateOrganizationClientResponse> create(
      @PathVariable final UUID organizationId,
      @Valid @RequestBody final CreateOrganizationClientRequest request,
      final Authentication authentication) {
    final CreateOrganizationClientResult result;
    try {
      result =
          useCase.handle(
              new CreateOrganizationClientCommand(
                  organizationId,
                  request.allowedScopes(),
                  AuditActor.platformClient(authentication.getName())));
    } catch (final OrganizationNotFoundException _) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(CreateOrganizationClientResponse.from(result));
  }
}
