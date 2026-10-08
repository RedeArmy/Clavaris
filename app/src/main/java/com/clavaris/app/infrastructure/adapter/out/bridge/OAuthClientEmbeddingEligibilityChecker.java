package com.clavaris.app.infrastructure.adapter.out.bridge;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.OrganizationEnvironmentChecker;
import com.clavaris.clientregistry.application.usecase.requestclientdomainconfig.ClientDomainConfigRepository;
import com.clavaris.clientregistry.domain.model.ClientDomainConfig;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * ADR-0009 §4: production requires a {@code VERIFIED} {@link ClientDomainConfig} with a registered
 * {@code embeddingOrigin} — the mandatory-custom-domain-for-embedding rule (BR-CLIENT-04). A {@code
 * DEVELOPMENT} Organization's client is embedding-eligible unconditionally (no
 * domain/embeddingOrigin registration required) — a deliberate, documented testing convenience, not
 * production-hardened, hence the wildcard origin and the warning log line every time it's used.
 */
// PMD.AvoidDuplicateLiterals: the repeated string is "PMD.LongVariable" itself, used on three
// methods/parameters below (security finding, 2026-10-07, expectedOrganizationId) — same false-
// positive rationale PlatformScopes' own class-level suppression already documents.
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
@Component
class OAuthClientEmbeddingEligibilityChecker implements EmbeddingEligibilityChecker {

  private static final Logger LOG =
      LoggerFactory.getLogger(OAuthClientEmbeddingEligibilityChecker.class);

  // ADR-0009 §4: dev-mode embedding is a testing convenience, not production-hardened — no
  // registered embeddingOrigin is required, at the cost of allowing any origin to embed.
  private static final String WILDCARD_ORIGIN = "*";

  private final OAuthClientRepository oauthClients;
  private final ClientDomainConfigRepository domainConfigs;

  // PMD.LongVariable: same name/rationale as RegisterOAuthClientUseCase's own identical parameter.
  @SuppressWarnings("PMD.LongVariable")
  private final OrganizationEnvironmentChecker environmentChecker;

  /* package */ OAuthClientEmbeddingEligibilityChecker(
      final OAuthClientRepository oauthClients,
      final ClientDomainConfigRepository domainConfigs,
      @SuppressWarnings("PMD.LongVariable")
          final OrganizationEnvironmentChecker environmentChecker) {
    this.oauthClients = oauthClients;
    this.domainConfigs = domainConfigs;
    this.environmentChecker = environmentChecker;
  }

  // Three exits (unknown client / wrong Organization / resolved) is clearer here than forcing a
  // single-return shape — same rationale ClientDomainConfig's own validateHostnameIfPresent
  // suppression documents.
  // PMD.LongVariable: same rationale as EmbeddingEligibilityChecker's own identical suppression.
  @SuppressWarnings({"PMD.OnlyOneReturn", "PMD.LongVariable"})
  @Override
  public Optional<String> resolveAllowedFrameAncestor(
      final String clientId, final UUID expectedOrganizationId) {
    if (clientId == null) {
      return Optional.empty();
    }
    return oauthClients
        .findByClientId(clientId)
        .filter(client -> belongsToExpectedOrganization(client, expectedOrganizationId))
        .flatMap(this::resolveFor);
  }

  // Security finding, 2026-10-07 (see this interface's own Javadoc): a resolved OAuthClient
  // belonging to a different Organization than the one the request path actually names must be
  // treated identically to "unknown client" — never partially trusted just because it happens to
  // have a verified domain for ITS OWN, unrelated Organization. expectedOrganizationId == null is
  // the one deliberate opt-out (the consent page's own flat, org-agnostic path has none to check
  // against) — every other caller must pass a real value.
  @SuppressWarnings("PMD.LongVariable")
  private static boolean belongsToExpectedOrganization(
      final OAuthClient client, final UUID expectedOrganizationId) {
    return expectedOrganizationId == null || client.organizationId().equals(expectedOrganizationId);
  }

  // Two exits (dev wildcard / production domain-gated lookup) — same rationale as
  // resolveAllowedFrameAncestor's own identical suppression. PMD.GuardLogStatement: clientId is a
  // direct accessor already computed for this branch, not an expensive call this rule's "avoid
  // unconditional work" concern applies to.
  @SuppressWarnings({"PMD.OnlyOneReturn", "PMD.GuardLogStatement"})
  private Optional<String> resolveFor(final OAuthClient client) {
    if (environmentChecker.isDevelopment(client.organizationId())) {
      // BR-DATA-01: clientId is a public OAuth2 identifier, not a secret — safe to log.
      LOG.warn("event=embedding_allowed_development_environment clientId={}", client.clientId());
      return Optional.of(WILDCARD_ORIGIN);
    }
    return domainConfigs
        .findByOAuthClientId(client.id())
        .filter(ClientDomainConfig::isVerified)
        .flatMap(ClientDomainConfig::embeddingOrigin);
  }
}
