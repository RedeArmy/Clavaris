package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.rotatesigningkeyfororganization.NoActiveSigningKeyException;
import com.clavaris.identity.application.usecase.rotatesigningkeyfororganization.RotateSigningKeyForOrganizationCommand;
import com.clavaris.identity.application.usecase.rotatesigningkeyfororganization.RotateSigningKeyForOrganizationResult;
import com.clavaris.identity.application.usecase.rotatesigningkeyfororganization.RotateSigningKeyForOrganizationUseCase;
import com.clavaris.identity.domain.model.OrganizationId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADR-0010 §5.2, TD-SEC-008: {@code POST
 * /api/v1/admin/organizations/{organizationId}/signing-keys/rotate} — manually-triggered, audited
 * key rotation with real overlap. Operator only (never self-service, never a tenant's own token) —
 * enforced by {@code app}'s own {@code AdminApiSecurityConfig}, not here, same separation of
 * concerns as every other {@code /api/v1/admin/**} controller in this codebase.
 */
@RestController
class RotateSigningKeyController {

  private final RotateSigningKeyForOrganizationUseCase useCase;

  /* package */ RotateSigningKeyController(final RotateSigningKeyForOrganizationUseCase useCase) {
    this.useCase = useCase;
  }

  // Two exits (404 on an Organization with no active key, 200 on success) — same rationale as
  // SetRateLimitPolicyController's own suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @Operation(summary = "Rotate an Organization's signing key, with JWKS overlap (ADR-0010 §5.2)")
  @ApiResponse(responseCode = "200", description = "Rotated — the new kid is now signing")
  @ApiResponse(
      responseCode = "404",
      description = "No Organization with an active signing key exists with the given id")
  @PostMapping("/api/v1/admin/organizations/{organizationId}/signing-keys/rotate")
  /* package */ ResponseEntity<RotateSigningKeyResponse> rotate(
      @PathVariable final UUID organizationId, final Authentication authentication) {
    final RotateSigningKeyForOrganizationResult result;
    try {
      result =
          useCase.handle(
              new RotateSigningKeyForOrganizationCommand(
                  new OrganizationId(organizationId),
                  // TD-SEC-007: AdminApiSecurityConfig gates this endpoint to a platform-tier
                  // client_credentials token only, same actor-resolution rationale as every other
                  // admin-API controller in this codebase.
                  AuditActor.platformClient(authentication.getName())));
    } catch (final NoActiveSigningKeyException _) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(RotateSigningKeyResponse.from(result));
  }
}
