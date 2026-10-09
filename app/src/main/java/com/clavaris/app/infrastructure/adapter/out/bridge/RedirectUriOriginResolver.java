package com.clavaris.app.infrastructure.adapter.out.bridge;

import com.clavaris.app.infrastructure.config.ContentSecurityPolicyHeaderWriter;
import java.util.List;
import java.util.UUID;

/**
 * Security finding, 2026-10-09 (live-caught on preproduction, real browser, real OIDC client):
 * {@link ContentSecurityPolicyHeaderWriter}'s own {@code form-action 'self'} blocks the
 * Authorization Code flow's own final hop. The hosted login page's own POST, and the consent page's
 * own "Approve" POST, both ultimately end — after SAS's own internal redirects — in a 302 to the
 * requesting {@code OAuthClient}'s registered {@code redirect_uri}, which is, by design, never this
 * origin. Chrome and Safari enforce {@code form-action} against every redirect hop a form
 * submission's own navigation passes through, not just the form's literal {@code action} attribute
 * (Firefox does not — confirmed against real-world reports of this exact OAuth2/OIDC shape, e.g.
 * django-oauth-toolkit#1623, W3C webappsec-csp#8); curl-based and {@code HttpClient} -based tests
 * (this project's own {@code AuthorizationCodeFlowIntegrationTest} included) never enforce CSP at
 * all, which is exactly how this shipped unnoticed since TD-SEC-009 first added the header.
 *
 * <p>Resolves which origins a given {@code OAuthClient}'s own registered {@code redirectUris}
 * actually point at — the one, narrow allowlist {@code form-action} should widen to, mirroring
 * {@link EmbeddingEligibilityChecker}'s own {@code frame-ancestors} relaxation shape exactly (same
 * {@code clientId}/{@code expectedOrganizationId} cross-tenant-check posture, same {@code null} org
 * opt-out for the consent page's own flat path) rather than a blanket {@code form-action *} that
 * would accept a redirect to literally anywhere.
 */
@FunctionalInterface
public interface RedirectUriOriginResolver {

  /**
   * @param clientId the OAuth2 spec's own {@code client_id}
   * @param expectedOrganizationId the Organization whose page is actually being requested, parsed
   *     from that request's own path — or {@code null} when the calling page's own path carries
   *     none (the consent page, flat/org-agnostic — see {@link EmbeddingEligibilityChecker}'s own
   *     identical parameter for the full rationale). When non-null, a resolved {@code OAuthClient}
   *     belonging to a different Organization is treated identically to "unknown client" (empty),
   *     never partially trusted.
   * @return the distinct origins (scheme + host + optional port, never a path) this client's own
   *     registered {@code redirectUris} resolve to, or an empty list if this client is unknown,
   *     belongs to a different Organization than expected, or has no {@code clientId} at all
   */
  @SuppressWarnings("PMD.LongVariable")
  List<String> resolveAllowedFormActionOrigins(String clientId, UUID expectedOrganizationId);
}
