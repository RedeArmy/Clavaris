package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.organization.application.usecase.getaccountauthenticationpolicyfororganization.GetAccountAuthenticationPolicyForOrganizationUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADR-0024: {@code GET /api/v1/admin/organizations/{organizationId}/authentication-policy} —
 * read-only, unscoped (no dedicated scope needed), same "only mutating actions get their own scope"
 * precedent every other {@code GET} on this surface follows. Always returns a real policy, never
 * 404 — an unconfigured Organization's implicit defaults are a legitimate, well-defined answer, not
 * an error (see {@code GetAccountAuthenticationPolicyForOrganizationUseCase}'s own Javadoc).
 */
@RestController
class GetAccountAuthenticationPolicyController {

  private final GetAccountAuthenticationPolicyForOrganizationUseCase useCase;

  /* package */ GetAccountAuthenticationPolicyController(
      final GetAccountAuthenticationPolicyForOrganizationUseCase useCase) {
    this.useCase = useCase;
  }

  @Operation(summary = "Read an Organization's sign-up/sign-in options policy (ADR-0024)")
  @ApiResponse(responseCode = "200", description = "The Organization's policy, or its defaults")
  @GetMapping("/api/v1/admin/organizations/{organizationId}/authentication-policy")
  /* package */ AccountAuthenticationPolicyResponse get(@PathVariable final UUID organizationId) {
    return AccountAuthenticationPolicyResponse.from(useCase.handle(organizationId));
  }
}
