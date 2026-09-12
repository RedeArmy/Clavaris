package com.clavaris.organization.application.usecase.getorganizationforplatformaccount;

import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.domain.model.Organization;
import java.util.Optional;

/**
 * Orchestration for {@link GetOrganizationForPlatformAccountUseCase}.
 *
 * <p>Same cross-tenant defense-in-depth {@code RedirectUrlResolverBridge}/{@code
 * ClientBrandingProviderBridge} already establish elsewhere: an Organization that exists but is
 * owned by a different {@code PlatformAccount} than the one asking is treated exactly like one that
 * doesn't exist at all — never a distinguishable "found, but not yours" outcome a caller could use
 * to enumerate real Organization ids by probing this endpoint. {@code ownerPlatformAccountId}
 * always comes from the authenticated session ({@code PlatformOrganizationDetailController}'s own
 * resolved principal), never from the request — {@code organizationId} is the only
 * caller-controlled input this check exists to defend against.
 */
public class GetOrganizationForPlatformAccountService
    implements GetOrganizationForPlatformAccountUseCase {

  private final OrganizationRepository organizations;

  public GetOrganizationForPlatformAccountService(final OrganizationRepository organizations) {
    this.organizations = organizations;
  }

  @Override
  public Optional<Organization> handle(final GetOrganizationForPlatformAccountQuery query) {
    return organizations
        .findById(query.organizationId())
        .filter(
            organization ->
                organization.ownerPlatformAccountId().equals(query.ownerPlatformAccountId()));
  }
}
