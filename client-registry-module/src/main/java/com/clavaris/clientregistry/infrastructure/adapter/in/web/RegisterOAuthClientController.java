package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OrganizationNotFoundException;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.RegisterOAuthClientCommand;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.RegisterOAuthClientResult;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.RegisterOAuthClientUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADR-0010, `api-contract-overview.md` §3: {@code POST
 * /api/v1/admin/organizations/{organizationId}/clients} — operator only, a separate, subsequent
 * call from {@code CreateOrganization} itself (BR-ORG-06's own wording: provisioning a tenant and
 * registering its first client are two distinct, individually-auditable actions, never bundled).
 * Authentication/authorization is enforced by {@code app}'s own {@code AdminApiSecurityConfig}, not
 * here, same separation of concerns as {@code CreateOrganizationController}.
 */
@RestController
class RegisterOAuthClientController {

  private final RegisterOAuthClientUseCase useCase;

  /* package */ RegisterOAuthClientController(final RegisterOAuthClientUseCase useCase) {
    this.useCase = useCase;
  }

  // Two exits (404 on a missing Organization, 201 on success) is clearer here than forcing a
  // single-return shape onto two genuinely different outcomes — same rationale as
  // RegisterAccountController's own suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @Operation(summary = "Register a new OAuthClient under an Organization (ADR-0010)")
  @ApiResponse(
      responseCode = "201",
      description =
          "OAuthClient created — clientSecret is shown here exactly once, never retrievable again")
  @ApiResponse(responseCode = "404", description = "No Organization exists with the given id")
  @PostMapping("/api/v1/admin/organizations/{organizationId}/clients")
  /* package */ ResponseEntity<RegisterOAuthClientResponse> register(
      @PathVariable final UUID organizationId,
      @Valid @RequestBody final RegisterOAuthClientRequest request) {
    final RegisterOAuthClientResult result;
    try {
      result =
          useCase.handle(
              new RegisterOAuthClientCommand(
                  organizationId,
                  request.redirectUris(),
                  request.allowedGrantTypes(),
                  request.allowedScopes(),
                  // TD-SEC-026/ADR-0017: an omitted requireConsent field means "safe default",
                  // never "no consent" — resolved here, at the one boundary where "absent" is a
                  // meaningful value, rather than pushed into the domain layer as an implicit
                  // default it would have to guess at.
                  Objects.requireNonNullElse(request.requireConsent(), Boolean.TRUE),
                  // TD-FUT-018: an omitted field means "not configured" (empty allowlist), same
                  // resolve-absence-at-the-boundary discipline as requireConsent above.
                  Objects.requireNonNullElse(request.postLogoutRedirectUris(), List.of())));
    } catch (final OrganizationNotFoundException _) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.status(HttpStatus.CREATED).body(RegisterOAuthClientResponse.from(result));
  }
}
