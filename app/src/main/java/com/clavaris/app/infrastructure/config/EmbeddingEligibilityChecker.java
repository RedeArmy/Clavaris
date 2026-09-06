package com.clavaris.app.infrastructure.config;

import java.util.Optional;

/**
 * ADR-0009 §1/§4: whether {@code display=modal} is honored for one specific {@code OAuthClient},
 * and if so, which origin {@code frame-ancestors} relaxes to for that one request. A separate port
 * from {@link
 * com.clavaris.clientregistry.application.usecase.requestclientdomainconfig.ClientDomainConfigRepository}
 * itself — {@link ContentSecurityPolicyHeaderWriter} is a presentation-layer concern ({@code app}'s
 * own composition, not any bounded context's own application layer), so this composes that
 * repository plus the environment/domain-verification rules rather than exposing them directly.
 */
@FunctionalInterface
interface EmbeddingEligibilityChecker {

  /**
   * @param clientId the OAuth2 spec's own {@code client_id} — forwarded onto the login page's own
   *     {@code clientId} query param (Phase 3, {@code OrganizationLoginRedirectEntryPoint})
   * @return the single origin to allow in {@code frame-ancestors}, or empty if this client is not
   *     embedding-eligible right now (unknown client, production without a verified domain and a
   *     registered {@code embeddingOrigin}, or no {@code clientId} at all)
   */
  Optional<String> resolveAllowedFrameAncestor(String clientId);
}
