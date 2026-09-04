package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import com.clavaris.clientregistry.application.usecase.listorganizationclients.ListOrganizationClientsUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/admin/organizations/{organizationId}/secret-keys} — read-only, no dedicated
 * scope, same "only mutating actions get their own scope" precedent {@code
 * ListOrganizationSocialCredentialsController} already establishes. Never includes any secret
 * material.
 */
@RestController
class ListOrganizationClientsController {

  private final ListOrganizationClientsUseCase useCase;

  /* package */ ListOrganizationClientsController(final ListOrganizationClientsUseCase useCase) {
    this.useCase = useCase;
  }

  @Operation(summary = "List an Organization's own OrganizationClients / Secret Keys (ADR-0023)")
  @ApiResponse(responseCode = "200", description = "Possibly empty list, never includes secrets")
  @GetMapping("/api/v1/admin/organizations/{organizationId}/secret-keys")
  /* package */ List<ListedOrganizationClient> list(@PathVariable final UUID organizationId) {
    return useCase.handle(organizationId).stream().map(ListedOrganizationClient::from).toList();
  }
}
