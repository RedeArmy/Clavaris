package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.organization.application.usecase.setratelimitpolicyfororganization.OrganizationNotFoundException;
import com.clavaris.organization.application.usecase.setratelimitpolicyfororganization.SetRateLimitPolicyForOrganizationCommand;
import com.clavaris.organization.application.usecase.setratelimitpolicyfororganization.SetRateLimitPolicyForOrganizationResult;
import com.clavaris.organization.application.usecase.setratelimitpolicyfororganization.SetRateLimitPolicyForOrganizationUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADR-0010 §6.2, BR-ORG-05: {@code PUT /api/v1/admin/organizations/{organizationId}/rate-limit-
 * policy} — the capacity layer's per-Organization aggregate ceiling, operator-managed only in v1
 * (never a tenant's own token — {@code AdminApiSecurityConfig} enforces platform-tier-only, same as
 * every other {@code /api/v1/admin/**} endpoint). A real code path, not raw SQL against production
 * — same reasoning TD-SEC-018 already flagged as the gap to avoid repeating for {@code
 * PlatformClient} rotation.
 *
 * <p>Deliberately never touches the anti-abuse layer (ADR-0010 §6.1) — that layer is fixed and
 * system-wide by design, never tenant-configurable, so it has no corresponding write endpoint at
 * all, here or anywhere else.
 */
@RestController
class SetRateLimitPolicyController {

  private final SetRateLimitPolicyForOrganizationUseCase useCase;

  /* package */ SetRateLimitPolicyController(
      final SetRateLimitPolicyForOrganizationUseCase useCase) {
    this.useCase = useCase;
  }

  // Three exits (404 on a bogus organizationId, 400 on a value over the hard system-wide cap, 200
  // on success) — same rationale as RegisterOAuthClientController's own suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @Operation(summary = "Set an Organization's rate-limit capacity ceiling (ADR-0010 §6.2)")
  @ApiResponse(responseCode = "200", description = "Policy created or updated")
  @ApiResponse(
      responseCode = "400",
      description = "requestsPerMinute exceeds the hard system-wide cap")
  @ApiResponse(responseCode = "404", description = "No Organization exists with the given id")
  @PutMapping("/api/v1/admin/organizations/{organizationId}/rate-limit-policy")
  /* package */ ResponseEntity<SetRateLimitPolicyResponse> set(
      @PathVariable final UUID organizationId,
      @Valid @RequestBody final SetRateLimitPolicyRequest request) {
    final SetRateLimitPolicyForOrganizationResult result;
    try {
      result =
          useCase.handle(
              new SetRateLimitPolicyForOrganizationCommand(
                  organizationId, request.requestsPerMinute()));
    } catch (final OrganizationNotFoundException _) {
      return ResponseEntity.notFound().build();
    } catch (final IllegalArgumentException _) {
      // RateLimitPolicy's own factory/update methods throw this for exactly one reason at this
      // call site: requestsPerMinute exceeded the hard system-wide cap (ADR-0010 §6.2) —
      // @Positive on the request DTO already rules out the only other case (a non-positive value)
      // before this method body ever runs.
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
    }
    return ResponseEntity.ok(SetRateLimitPolicyResponse.from(result.policy()));
  }
}
