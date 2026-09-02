package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.purgesigningkeyfororganization.PurgeSigningKeyForOrganizationCommand;
import com.clavaris.identity.application.usecase.purgesigningkeyfororganization.PurgeSigningKeyForOrganizationResult;
import com.clavaris.identity.application.usecase.purgesigningkeyfororganization.PurgeSigningKeyForOrganizationUseCase;
import com.clavaris.identity.application.usecase.purgesigningkeyfororganization.SigningKeyNotFoundException;
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
 * TD-SEC-029: {@code POST /api/v1/admin/organizations/{organizationId}/signing-keys/{kid}:purge} —
 * the emergency, zero-overlap containment lever {@code incident-response-signing-key-compromise.md}
 * §3.6 names, now a real, audited operation instead of a raw-SQL fallback. Reserved for a
 * <b>confirmed</b> compromise, not routine rotation — it accepts breaking any legitimate token
 * signed under the purged key that was still valid. Operator only (never self-service, never a
 * tenant's own token) — enforced by {@code app}'s own {@code AdminApiSecurityConfig}, not here,
 * same separation of concerns as every other {@code /api/v1/admin/**} controller in this codebase.
 */
@RestController
class PurgeSigningKeyController {

  private final PurgeSigningKeyForOrganizationUseCase useCase;

  /* package */ PurgeSigningKeyController(final PurgeSigningKeyForOrganizationUseCase useCase) {
    this.useCase = useCase;
  }

  // Two exits (404 on an unknown kid, 200 on success) — same rationale as
  // RotateSigningKeyController's own identical suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @Operation(
      summary =
          "Emergency zero-overlap purge of one SigningKey for a confirmed compromise (TD-SEC-029)")
  @ApiResponse(responseCode = "200", description = "Purged — the kid is now excluded from JWKS")
  @ApiResponse(
      responseCode = "404",
      description = "No SigningKey with the given kid exists for this Organization")
  @PostMapping("/api/v1/admin/organizations/{organizationId}/signing-keys/{kid}:purge")
  /* package */ ResponseEntity<PurgeSigningKeyResponse> purge(
      @PathVariable final UUID organizationId,
      @PathVariable final String kid,
      final Authentication authentication) {
    final PurgeSigningKeyForOrganizationResult result;
    try {
      result =
          useCase.handle(
              new PurgeSigningKeyForOrganizationCommand(
                  new OrganizationId(organizationId),
                  kid,
                  AuditActor.platformClient(authentication.getName())));
    } catch (final SigningKeyNotFoundException _) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(PurgeSigningKeyResponse.from(result));
  }
}
