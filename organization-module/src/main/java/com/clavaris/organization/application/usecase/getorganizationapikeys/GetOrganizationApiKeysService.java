package com.clavaris.organization.application.usecase.getorganizationapikeys;

import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.OrganizationEnvironment;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only, entirely derived from data that already exists — see {@link OrganizationApiKeys}'s own
 * Javadoc for the field-by-field mapping. {@code configuredApiVersion}/{@code latestApiVersion} are
 * one small named constant here (ADR-0008's own URI-path versioning — {@code /api/v1/admin} is the
 * only version that has ever existed), not scattered string literals, so both update in exactly one
 * place the day a {@code v2} ships. {@code PMD.LongVariable}: these names are exactly what they are
 * — the shortened alternatives would only make this small class harder to read.
 */
@SuppressWarnings("PMD.LongVariable")
public class GetOrganizationApiKeysService implements GetOrganizationApiKeysUseCase {

  private static final String CURRENT_API_VERSION = "v1";
  private static final String DEVELOPMENT_PUBLISHABLE_KEY_PREFIX = "pk_test_";
  private static final String PRODUCTION_PUBLISHABLE_KEY_PREFIX = "pk_live_";

  private final OrganizationRepository organizations;
  private final OrganizationSigningKeyPublicKeyProvider publicKeyProvider;
  private final String clavarisBaseUrl;

  public GetOrganizationApiKeysService(
      final OrganizationRepository organizations,
      final OrganizationSigningKeyPublicKeyProvider publicKeyProvider,
      final String clavarisBaseUrl) {
    this.organizations = organizations;
    this.publicKeyProvider = publicKeyProvider;
    this.clavarisBaseUrl = clavarisBaseUrl;
  }

  @Override
  public Optional<OrganizationApiKeys> handle(final UUID organizationId) {
    return organizations.findById(organizationId).map(this::toApiKeys);
  }

  private OrganizationApiKeys toApiKeys(final Organization organization) {
    final String organizationId = organization.id().toString();
    final String publishableKeyPrefix =
        organization.environment() == OrganizationEnvironment.DEVELOPMENT
            ? DEVELOPMENT_PUBLISHABLE_KEY_PREFIX
            : PRODUCTION_PUBLISHABLE_KEY_PREFIX;
    final String publishableKey =
        publishableKeyPrefix
            + Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(organizationId.getBytes(StandardCharsets.UTF_8));
    final String frontendApiUrl = clavarisBaseUrl + "/o/" + organizationId;

    return new OrganizationApiKeys(
        publishableKey,
        frontendApiUrl,
        clavarisBaseUrl + "/oauth2/token",
        frontendApiUrl + "/oauth2/jwks",
        publicKeyProvider.pemPublicKeyFor(organization.id()).orElse(null),
        CURRENT_API_VERSION,
        CURRENT_API_VERSION);
  }
}
