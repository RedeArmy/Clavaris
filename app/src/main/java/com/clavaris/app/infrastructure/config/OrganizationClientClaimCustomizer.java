package com.clavaris.app.infrastructure.config;

import com.clavaris.clientregistry.application.usecase.createorganizationclient.OrganizationClientRepository;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.stereotype.Component;

/**
 * ADR-0023: adds the {@code organization_id} claim to a token minted for an {@code
 * OrganizationClient} (Secret Key) — absent entirely for a {@code PlatformClient} token, which
 * stays exactly as unscoped as it is today. Composed onto {@code
 * PlatformAuthorizationServerConfig}'s own {@code jwtGenerator.setJwtCustomizer(...)} chain
 * alongside {@code TokenIssuanceEventLogger}, same "chain a second customizer" precedent {@code
 * ImpersonationTokenIssuer}'s own {@code tokenIssuanceLogger.customize(context);
 * addImpersonationClaims(...)} composition already establishes — not a reason to hand-mint outside
 * the normal {@code client_credentials} dispatch the way {@code ImpersonationTokenIssuer} itself
 * must (that class mints a token *as if* a different identity had authenticated; an {@code
 * OrganizationClient} really is presenting its own real credential through the real grant, so the
 * real dispatch is the right place for this).
 *
 * <p>One extra lookup per token mint (by {@code client_id}, not a hot path — {@code
 * client_credentials} tokens are requested per client session, not per resource-server request) —
 * {@code OrganizationClientOwnershipFilter} is what actually enforces the consequence on every
 * subsequent admin-API request, this class only stamps the fact into the token itself.
 */
// LongVariable: ORGANIZATION_ID_CLAIM/organizationClient(s) name exactly what they are.
// LawOfDemeter:
// JwtEncodingContext is a flat, purpose-built accessor facade, same "not a coupling smell"
// reasoning
// TokenIssuanceEventLogger's own identical suppression already documents for this exact type.
@SuppressWarnings({"PMD.LongVariable", "PMD.LawOfDemeter"})
@Component
class OrganizationClientClaimCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

  /**
   * Package-visible so {@link OrganizationClientOwnershipFilter} reads back the exact same claim
   * name — same "define once, reference from the one place that reads it" convention as {@code
   * SocialLoginRedirectController.ORGANIZATION_ID_SESSION_ATTRIBUTE}.
   */
  /* package */ static final String ORGANIZATION_ID_CLAIM = "organization_id";

  private final OrganizationClientRepository organizationClients;

  /* package */ OrganizationClientClaimCustomizer(
      final OrganizationClientRepository organizationClients) {
    this.organizationClients = organizationClients;
  }

  @Override
  public void customize(final JwtEncodingContext context) {
    organizationClients
        .findByClientId(context.getRegisteredClient().getClientId())
        .ifPresent(
            organizationClient ->
                context
                    .getClaims()
                    .claim(ORGANIZATION_ID_CLAIM, organizationClient.organizationId().toString()));
  }
}
