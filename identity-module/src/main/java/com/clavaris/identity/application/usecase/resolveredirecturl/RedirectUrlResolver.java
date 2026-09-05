package com.clavaris.identity.application.usecase.resolveredirecturl;

import com.clavaris.identity.domain.model.OrganizationId;
import java.util.Optional;

/**
 * Clerk "customize redirect URLs" parity: resolves the post-authentication landing page to use ONLY
 * for the case where there is no in-flight {@code /oauth2/authorize} request to resume — see {@code
 * RedirectPolicy}'s own Javadoc (client-registry-module) for the full precedence chain and why a
 * Force redirect can never override the OAuth2 authorization code's own mandatory return to a
 * client's registered {@code redirect_uri}. Every caller (see {@code LoginController}) still checks
 * {@code AuthenticatedSessionEstablisher}'s own {@code SavedRequest} first, unconditionally, before
 * this resolver's answer is ever used as the {@code fallbackUrl} argument.
 *
 * <p>Deliberately does not reference client-registry-module's {@code OAuthClient}/{@code
 * RedirectPolicy} types directly — same module-independence rule {@code
 * AccountAuthenticationPolicyProvider} already follows for an identical cross-module need.
 * Implemented in {@code app} by delegating to client-registry-module's own {@code
 * OAuthClientRepository}/{@code GetRedirectPolicyForClientUseCase}.
 */
// PMD.LongVariable: requestedRedirectUrl matches Clerk's own equivalent concept (redirect_url),
// not arbitrarily long — same precedent RedirectPolicy's own suppression documents.
@SuppressWarnings("PMD.LongVariable")
@FunctionalInterface
public interface RedirectUrlResolver {

  /**
   * @param organizationId the path's own Organization — a {@code clientId} that resolves to a
   *     different Organization is treated exactly like an unknown one (empty), same BR-ORG-02
   *     cross-tenant defence-in-depth {@code SetRedirectPolicyForClientService} already applies.
   * @param clientId the OAuth2 {@code client_id}, or {@code null} when the current request carries
   *     no client context at all (e.g. a direct hosted-login visit with no {@code client_id} query
   *     param) — always {@code Optional.empty()} in that case, nothing to resolve against.
   * @param requestedRedirectUrl the request's own {@code redirect_url} query param, or {@code null}
   *     — only ever honored when it is a verbatim member of the resolved client's own {@code
   *     redirectUris} allowlist (never a fresh, unvalidated open-redirect surface).
   * @return empty when nothing applies — the caller falls through to its own hardcoded default.
   */
  Optional<String> resolve(
      OrganizationId organizationId,
      String clientId,
      String requestedRedirectUrl,
      RedirectAction action);
}
