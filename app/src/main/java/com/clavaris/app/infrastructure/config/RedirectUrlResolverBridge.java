package com.clavaris.app.infrastructure.config;

import com.clavaris.clientregistry.application.usecase.getredirectpolicyforclient.GetRedirectPolicyForClientUseCase;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.clientregistry.domain.model.RedirectPolicy;
import com.clavaris.identity.application.usecase.resolveredirecturl.RedirectAction;
import com.clavaris.identity.application.usecase.resolveredirecturl.RedirectUrlResolver;
import com.clavaris.identity.domain.model.OrganizationId;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Adapts client-registry-module's {@code OAuthClientRepository}/{@code
 * GetRedirectPolicyForClientUseCase} to identity-module's own {@link RedirectUrlResolver} port —
 * same module-independence-crossing-bridge pattern {@code
 * AccountAuthenticationPolicyProviderBridge} already establishes for an identical need.
 */
// PMD.LongVariable: requestedRedirectUrl matches Clerk's own equivalent concept, not arbitrarily
// long — same precedent RedirectUrlResolver's own suppression documents. PMD.OnlyOneReturn: four
// genuinely distinct early exits (no client context, unknown/cross-tenant client, a force URL, a
// validated query-param override) — same rationale OAuthClient's own multi-exit methods document.
@SuppressWarnings({"PMD.LongVariable", "PMD.OnlyOneReturn"})
@Component
class RedirectUrlResolverBridge implements RedirectUrlResolver {

  private final OAuthClientRepository oauthClients;
  private final GetRedirectPolicyForClientUseCase getRedirectPolicy;

  /* package */ RedirectUrlResolverBridge(
      final OAuthClientRepository oauthClients,
      final GetRedirectPolicyForClientUseCase getRedirectPolicy) {
    this.oauthClients = oauthClients;
    this.getRedirectPolicy = getRedirectPolicy;
  }

  @Override
  public Optional<String> resolve(
      final OrganizationId organizationId,
      final String clientId,
      final String requestedRedirectUrl,
      final RedirectAction action) {
    // No client context on this request at all — nothing to resolve against, same "absence is a
    // legitimate, non-error state" convention every other read port in this codebase follows.
    if (clientId == null) {
      return Optional.empty();
    }

    final Optional<OAuthClient> maybeClient = oauthClients.findByClientId(clientId);
    // BR-ORG-02-style cross-tenant defence in depth: a clientId that's unknown, or that resolves
    // to a different Organization than the path claims, is treated exactly like "no client
    // context" — never an error, never a hint about which combination is real.
    if (maybeClient.isEmpty()
        || !maybeClient.get().organizationId().equals(organizationId.value())) {
      return Optional.empty();
    }
    final OAuthClient client = maybeClient.get();

    final RedirectPolicy policy = getRedirectPolicy.handle(client.id());
    final Optional<String> forceUrl =
        action == RedirectAction.SIGN_IN
            ? policy.forceSignInRedirectUrl()
            : policy.forceSignUpRedirectUrl();
    if (forceUrl.isPresent()) {
      return forceUrl;
    }

    // The request's own redirect_url query param — only ever honored when it's a verbatim member
    // of this same client's already-vetted redirectUris allowlist (RedirectPolicy's own Javadoc:
    // never a fresh, unvalidated open-redirect surface).
    if (requestedRedirectUrl != null && client.redirectUris().contains(requestedRedirectUrl)) {
      return Optional.of(requestedRedirectUrl);
    }

    return action == RedirectAction.SIGN_IN
        ? policy.fallbackSignInRedirectUrl()
        : policy.fallbackSignUpRedirectUrl();
  }
}
