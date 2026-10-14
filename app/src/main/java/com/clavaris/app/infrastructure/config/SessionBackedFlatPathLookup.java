package com.clavaris.app.infrastructure.config;

import com.clavaris.identity.application.usecase.resolveorganizationforclient.OrganizationForClientResolver;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.infrastructure.adapter.in.web.SocialLoginRedirectController;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;

/**
 * Both collaborators are looked up per request through an {@link ObjectProvider}: the session
 * cookie serializer is created while the servlet context starts, before the JPA-backed beans and
 * the session repository exist, and resolving them eagerly here would break that start-up (the same
 * ordering problem {@code FilterOrderingConfig} documents).
 */
// PMD.LongVariable: names say exactly what they hold, same precedent as the rest of this package.
// PMD.LawOfDemeter: ObjectProvider.getIfAvailable() and the repository/resolver calls on its result
// are the only way to reach these lazily; PMD.OnlyOneReturn: a missing collaborator and a real
// answer are two distinct outcomes.
@SuppressWarnings({"PMD.LongVariable", "PMD.LawOfDemeter", "PMD.OnlyOneReturn"})
final class SessionBackedFlatPathLookup implements FlatPathSessionLookup {

  private final ObjectProvider<OrganizationForClientResolver> clientOrganizations;
  private final ObjectProvider<SessionRepository<?>> sessions;

  /* package */ SessionBackedFlatPathLookup(
      final ObjectProvider<OrganizationForClientResolver> clientOrganizations,
      final ObjectProvider<SessionRepository<?>> sessions) {
    this.clientOrganizations = clientOrganizations;
    this.sessions = sessions;
  }

  @Override
  public Optional<UUID> organizationForClient(final String clientId) {
    final OrganizationForClientResolver resolver = clientOrganizations.getIfAvailable();
    return resolver == null
        ? Optional.empty()
        : resolver.resolve(clientId).map(OrganizationId::value);
  }

  @Override
  public boolean hasSocialLoginInProgress(final String sessionId) {
    final SessionRepository<?> repository = sessions.getIfAvailable();
    if (repository == null) {
      return false;
    }
    final Session session = repository.findById(sessionId);
    return session != null
        && session.getAttribute(SocialLoginRedirectController.ORGANIZATION_ID_SESSION_ATTRIBUTE)
            != null;
  }
}
