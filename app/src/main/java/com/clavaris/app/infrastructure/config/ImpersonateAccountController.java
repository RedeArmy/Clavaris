package com.clavaris.app.infrastructure.config;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.impersonateaccount.AccountNotActiveException;
import com.clavaris.identity.application.usecase.impersonateaccount.AccountNotFoundException;
import com.clavaris.identity.application.usecase.impersonateaccount.ImpersonateAccountCommand;
import com.clavaris.identity.application.usecase.impersonateaccount.ImpersonateAccountResult;
import com.clavaris.identity.application.usecase.impersonateaccount.ImpersonateAccountUseCase;
import com.clavaris.identity.domain.model.AccountId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * SDE-III feature build, 2026-09-03 — Impersonation: {@code POST
 * /api/v1/admin/accounts/{id}:impersonate} mints a real, short-lived, revocable Bearer access token
 * scoped to the target Account and one of its own Organization's registered {@code OAuthClient}s, a
 * genuine "log in as this user" support/operator capability (same category of feature as Clerk's
 * own impersonation). Gated behind {@code platform:accounts:impersonate} ({@link
 * com.clavaris.clientregistry.domain.model.PlatformScopes#ACCOUNTS_IMPERSONATE}) and a dedicated,
 * tight rate limit — see {@code AdminApiSecurityConfig}'s own rule for this endpoint.
 *
 * <p><b>Deliberate v1 scope: access token only, no ID token, no refresh token.</b> The core need
 * ("call this consuming application's API as this user") is served by the access token alone;
 * skipping the ID token avoids {@code RefreshTokenRotationAuthenticationProvider}'s own {@code
 * sid}/{@code auth_time} placeholder machinery, which exists only to satisfy {@code JwtGenerator}'s
 * OIDC-specific requirements for a {@code REFRESH_TOKEN}-grant ID token — genuine complexity this
 * feature has no need to take on for a v1 whose real-world use case is API access, not rendering a
 * browser session as the impersonated user. <b>Real, live-verified consequence, not a hypothetical
 * one:</b> {@code /userinfo} does NOT work against an access-token-only impersonation token on this
 * codebase's own chain — {@code WorkspaceAwareOidcUserInfoMapper} unconditionally reads {@code
 * context.getAuthorization().getToken(OidcIdToken.class)}, which SAS's own {@code
 * OidcUserInfoEndpointFilter} requires to resolve before that mapper ever runs; an authorization
 * row with no ID token attached is rejected with {@code invalid_request} (400), caught by this
 * class's own integration test before this note was written. Consuming {@code /userinfo} with an
 * impersonation token is therefore genuinely out of v1 scope, not merely untested — a real ID token
 * would need to be minted too (RefreshTokenRotationAuthenticationProvider's own {@code sid}/{@code
 * auth_time} placeholder machinery) the day this is needed, tracked as a named follow-up rather
 * than silently assumed to already work. No refresh token: an impersonation session is meant to be
 * short and deliberately re-initiated, not silently extended indefinitely the way BR-ID-03's own
 * rotation chain extends a real login.
 */
// PMD.LongVariable: impersonateAccount names exactly what it is (the ImpersonateAccountUseCase
// collaborator) — same "abbreviating would only make the call site harder to read" precedent this
// codebase's own PlatformScopes/OrganizationAuthorizationServerConfig suppressions already use.
@SuppressWarnings("PMD.LongVariable")
@RestController
class ImpersonateAccountController {

  private final ImpersonateAccountUseCase impersonateAccount;
  private final ImpersonationTokenIssuer tokenIssuer;

  /* package */ ImpersonateAccountController(
      final ImpersonateAccountUseCase impersonateAccount,
      final ImpersonationTokenIssuer tokenIssuer) {
    this.impersonateAccount = impersonateAccount;
    this.tokenIssuer = tokenIssuer;
  }

  // Four exits (404 unknown account, 409 not active, 400 bad client/scope, 200 success) — same
  // "one exit per distinct outcome" rationale as every other admin-API controller in this codebase.
  @SuppressWarnings({"PMD.OnlyOneReturn", "PMD.ShortVariable"})
  @Operation(summary = "Mint a short-lived Bearer access token impersonating this Account")
  @ApiResponse(responseCode = "200", description = "Access token minted")
  @ApiResponse(responseCode = "404", description = "No Account exists with the given id")
  @ApiResponse(responseCode = "409", description = "The Account exists but is not ACTIVE")
  @ApiResponse(
      responseCode = "400",
      description =
          "clientId isn't registered under this Account's own Organization, or a "
              + "requested scope exceeds what that client allows")
  @PostMapping("/api/v1/admin/accounts/{id}:impersonate")
  /* package */ ResponseEntity<ImpersonateAccountResponse> impersonate(
      @PathVariable final UUID id,
      @Valid @RequestBody final ImpersonateAccountRequest request,
      final Authentication authentication,
      final HttpServletRequest servletRequest) {
    final AuditActor actor = AuditActor.platformClient(authentication.getName());
    final ImpersonateAccountResult result;
    try {
      result = impersonateAccount.handle(new ImpersonateAccountCommand(new AccountId(id), actor));
    } catch (final AccountNotFoundException _) {
      return ResponseEntity.notFound().build();
    } catch (final AccountNotActiveException _) {
      return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    // See ImpersonationTokenIssuer's own Javadoc for why the base URL must come from the actual
    // incoming request, not a fixed config value — this deployment serves both this admin surface
    // and the real per-Organization issuer on the same scheme/host/port.
    final String baseUrl =
        ServletUriComponentsBuilder.fromRequestUri(servletRequest)
            .replacePath(null)
            .build()
            .toUriString();

    final ImpersonationTokenIssuer.ImpersonationToken token;
    try {
      token =
          tokenIssuer.mint(
              result.accountId(),
              result.organizationId(),
              request.clientId(),
              request.scopes(),
              actor,
              baseUrl);
    } catch (final ImpersonationClientNotFoundException | ImpersonationScopeNotAllowedException _) {
      return ResponseEntity.badRequest().build();
    }

    return ResponseEntity.ok(ImpersonateAccountResponse.from(token));
  }
}
