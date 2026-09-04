package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.organization.application.usecase.getorganizationapikeys.GetOrganizationApiKeysUseCase;
import com.clavaris.organization.application.usecase.getorganizationapikeys.OrganizationApiKeys;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/admin/organizations/{organizationId}/api-keys} — mirrors
 * https://clerk.com/docs/guides/development/clerk-environment-variables. Read-only, no dedicated
 * scope, same "only mutating actions get their own scope" precedent every other {@code GET} on this
 * surface follows.
 */
@RestController
class GetOrganizationApiKeysController {

  private final GetOrganizationApiKeysUseCase useCase;

  /* package */ GetOrganizationApiKeysController(final GetOrganizationApiKeysUseCase useCase) {
    this.useCase = useCase;
  }

  @Operation(summary = "Get an Organization's API keys / instance info (Clerk parity)")
  @ApiResponse(
      responseCode = "200",
      description = "Publishable key, API URLs, JWKS public key, API version")
  @ApiResponse(responseCode = "404", description = "No Organization exists with the given id")
  @GetMapping("/api/v1/admin/organizations/{organizationId}/api-keys")
  /* package */ ResponseEntity<OrganizationApiKeys> get(@PathVariable final UUID organizationId) {
    return useCase
        .handle(organizationId)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }
}
