package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.application.usecase.createproductionenvironment.CreateProductionEnvironmentCommand;
import com.clavaris.organization.application.usecase.createproductionenvironment.CreateProductionEnvironmentResult;
import com.clavaris.organization.application.usecase.createproductionenvironment.CreateProductionEnvironmentUseCase;
import com.clavaris.organization.application.usecase.createproductionenvironment.OrganizationAlreadyHasLinkedEnvironmentException;
import com.clavaris.organization.application.usecase.createproductionenvironment.OrganizationNotDevelopmentException;
import com.clavaris.organization.application.usecase.createproductionenvironment.OrganizationNotFoundException;
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
 * SDE-III feature build, 2026-09-04 (Clerk Development/Production instances analysis): {@code POST
 * /api/v1/admin/organizations/{id}:create-production-environment} — operator only, same tier as
 * {@code CreateOrganizationController}, gated by the same {@code platform:organizations:write}
 * scope (this is structurally the same class of action — creating a new Organization row — reached
 * through a different, promotion-shaped entry point, not a new risk tier warranting its own scope).
 */
@RestController
class CreateProductionEnvironmentController {

  private final CreateProductionEnvironmentUseCase useCase;

  /* package */ CreateProductionEnvironmentController(
      final CreateProductionEnvironmentUseCase useCase) {
    this.useCase = useCase;
  }

  // Four exits (404 unknown Organization, 409 not DEVELOPMENT / already linked, 201 success) —
  // same "one exit per distinct outcome" rationale as every other admin-API controller here.
  @SuppressWarnings({"PMD.OnlyOneReturn", "PMD.ShortVariable"})
  @Operation(
      summary = "Promote a DEVELOPMENT Organization by creating its paired PRODUCTION sibling")
  @ApiResponse(
      responseCode = "201",
      description = "PRODUCTION Organization created; initial SigningKey provisioned (BR-ORG-06)")
  @ApiResponse(responseCode = "404", description = "No Organization exists with the given id")
  @ApiResponse(
      responseCode = "409",
      description =
          "The Organization is not a DEVELOPMENT environment, or already has a linked one")
  @PostMapping("/api/v1/admin/organizations/{id}:create-production-environment")
  /* package */ ResponseEntity<CreateProductionEnvironmentResponse> create(
      @PathVariable final UUID id,
      @Valid @RequestBody final CreateProductionEnvironmentRequest request,
      final Authentication authentication) {
    final CreateProductionEnvironmentResult result;
    try {
      result =
          useCase.handle(
              new CreateProductionEnvironmentCommand(
                  id, request.name(), AuditActor.platformClient(authentication.getName())));
    } catch (final OrganizationNotFoundException _) {
      return ResponseEntity.notFound().build();
    } catch (final OrganizationNotDevelopmentException
        | OrganizationAlreadyHasLinkedEnvironmentException _) {
      return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(CreateProductionEnvironmentResponse.from(result));
  }
}
