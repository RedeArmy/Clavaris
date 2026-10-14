package com.clavaris.app.infrastructure.config;

import com.clavaris.identity.application.usecase.resolveorganizationforclient.OrganizationForClientResolver;
import com.clavaris.identity.domain.model.OrganizationId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The collaborator is looked up per request through an {@link ObjectProvider}: the session cookie
 * serializer is created while the servlet context starts, before the JPA-backed beans exist, and
 * resolving it eagerly here would break that start-up (the same ordering problem {@code
 * FilterOrderingConfig} documents).
 */
// PMD.LongVariable: names say exactly what they hold, same precedent as the rest of this package.
// PMD.LawOfDemeter: ObjectProvider.getIfAvailable() and the resolver call on its result are the
// only way to reach it lazily.
@SuppressWarnings({"PMD.LongVariable", "PMD.LawOfDemeter"})
final class SessionBackedFlatPathLookup implements FlatPathSessionLookup {

  private final ObjectProvider<OrganizationForClientResolver> clientOrganizations;

  /* package */ SessionBackedFlatPathLookup(
      final ObjectProvider<OrganizationForClientResolver> clientOrganizations) {
    this.clientOrganizations = clientOrganizations;
  }

  @Override
  public Optional<UUID> organizationForClient(final String clientId) {
    final OrganizationForClientResolver resolver = clientOrganizations.getIfAvailable();
    return resolver == null
        ? Optional.empty()
        : resolver.resolve(clientId).map(OrganizationId::value);
  }
}
