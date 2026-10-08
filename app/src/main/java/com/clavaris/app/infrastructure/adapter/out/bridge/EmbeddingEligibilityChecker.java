package com.clavaris.app.infrastructure.adapter.out.bridge;

import com.clavaris.app.infrastructure.config.ContentSecurityPolicyHeaderWriter;
import java.util.Optional;
import java.util.UUID;

/**
 * ADR-0009 §1/§4: whether {@code display=modal} is honored for one specific {@code OAuthClient},
 * and if so, which origin {@code frame-ancestors} relaxes to for that one request. A separate port
 * from {@link
 * com.clavaris.clientregistry.application.usecase.requestclientdomainconfig.ClientDomainConfigRepository}
 * itself — {@link ContentSecurityPolicyHeaderWriter} is a presentation-layer concern ({@code app}'s
 * own composition, not any bounded context's own application layer), so this composes that
 * repository plus the environment/domain-verification rules rather than exposing them directly.
 *
 * <p><b>Security finding, 2026-10-07 (live validation of TD-FUT-045): {@code
 * expectedOrganizationId} added.</b> This method used to resolve an origin from {@code clientId}
 * alone, with no check that the resolved {@code OAuthClient} actually belongs to the Organization
 * whose page is being framed — a real, previously-undocumented cross-tenant gap (confirmed against
 * {@code threat-model-stride.md}'s own existing clickjacking entry for this exact relaxation, which
 * never named this angle): an attacker who legitimately registers their own verified-domain {@code
 * OAuthClient} under their own Organization could frame a *different* Organization's own
 * login/profile page inside their own site, since nothing checked which Organization the resolved
 * client actually belonged to. Same {@code organizationId}-cross-check posture this codebase
 * already applies elsewhere for an identical reason ({@code DeviceTrustChallengeController}
 * re-checks a resumed challenge's stored {@code organizationId} against the request path's own,
 * BR-ORG-02). {@code null} means "no expected Organization to check against" — the one caller with
 * no {@code organizationId} in its own request path at all ({@code
 * ContentSecurityPolicyHeaderWriter}'s own consent-page relaxation, {@code /oauth2/consent} is
 * flat/org-agnostic) passes it, preserving that call site's own existing behavior unchanged; every
 * caller that does have one in its path (login, profile) must pass it.
 */
@FunctionalInterface
public interface EmbeddingEligibilityChecker {

  /**
   * @param clientId the OAuth2 spec's own {@code client_id} — forwarded onto the login page's own
   *     {@code clientId} query param (Phase 3, {@code OrganizationLoginRedirectEntryPoint})
   * @param expectedOrganizationId the Organization whose page is actually being requested, parsed
   *     from that request's own path — or {@code null} when the calling page's own path carries
   *     none (see this interface's own Javadoc). When non-null, a resolved {@code OAuthClient}
   *     belonging to a different Organization is treated identically to "unknown client" (empty),
   *     never partially trusted.
   * @return the single origin to allow in {@code frame-ancestors}, or empty if this client is not
   *     embedding-eligible right now (unknown client, wrong Organization, production without a
   *     verified domain and a registered {@code embeddingOrigin}, or no {@code clientId} at all)
   */
  // PMD.LongVariable: expectedOrganizationId names exactly what it is — same "abbreviating would
  // only make every call site harder to read" precedent this codebase's own PlatformScopes/
  // OrganizationAuthorizationServerConfig suppressions already use.
  @SuppressWarnings("PMD.LongVariable")
  Optional<String> resolveAllowedFrameAncestor(String clientId, UUID expectedOrganizationId);
}
