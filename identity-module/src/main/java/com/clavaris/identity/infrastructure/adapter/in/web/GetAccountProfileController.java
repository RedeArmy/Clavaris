package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.getaccountprofile.GetAccountProfileQuery;
import com.clavaris.identity.application.usecase.getaccountprofile.GetAccountProfileUseCase;
import com.clavaris.identity.domain.model.AccountId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * TD-FUT-041: {@code GET /api/v1/admin/accounts/{id}/profile} — Backend-API-style profile read for
 * a consuming application's own backend, Clerk {@code clerkClient.users.getUser} parity. Reachable
 * by both the platform operator's own {@code PlatformClient} and, via {@code
 * OrganizationClientOwnershipFilter}'s {@code oneHopAccount} allowlist entry for this route, a
 * tenant's own {@code OrganizationClient} ("Secret Key") — same dual-authority shape TD-FUT-034
 * already established for the sibling metadata endpoint, both gated by one scope, {@code
 * platform:accounts:profile:write}, covering this read and {@link UpdateAccountProfileController}'s
 * own write (a deliberate choice, not an oversight — see that scope's own Javadoc).
 */
// PMD.ShortVariable: id matches the path variable's own wire name (and GetAccountMetadataController
// -style siblings elsewhere in this codebase) — not an organically short name that should grow.
@SuppressWarnings("PMD.ShortVariable")
@RestController
class GetAccountProfileController {

  private final GetAccountProfileUseCase useCase;

  /* package */ GetAccountProfileController(final GetAccountProfileUseCase useCase) {
    this.useCase = useCase;
  }

  @Operation(summary = "Read an Account's firstName/lastName/username/phoneNumber (TD-FUT-041)")
  @ApiResponse(responseCode = "200", description = "Profile returned")
  @ApiResponse(responseCode = "404", description = "No Account exists with the given id")
  @GetMapping("/api/v1/admin/accounts/{id}/profile")
  /* package */ ResponseEntity<AccountProfileResponse> get(@PathVariable final UUID id) {
    return useCase
        .handle(new GetAccountProfileQuery(new AccountId(id)))
        .map(AccountProfileResponse::from)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }
}
