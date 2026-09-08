package com.clavaris.identity.infrastructure.adapter.out.persistence;

import com.clavaris.identity.application.usecase.issuerefreshtoken.SessionRepository;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.Session;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Implements the outbound port; maps between {@code domain.model.Session} (framework-free) and
 * {@link SessionEntity}.
 *
 * <p>TD-PERF-019: {@code insert} calls {@link EntityManager#persist} directly, not {@code
 * SpringDataSessionJpaRepository#save} — see {@code SessionRepository#insert}'s own Javadoc for
 * which call site that's safe for and why {@code save} itself is unchanged.
 */
@Repository
class JpaSessionRepository implements SessionRepository {

  private final SpringDataSessionJpaRepository sessions;
  private final ObjectMapper objectMapper;
  private final EntityManager entityManager;

  /* package */ JpaSessionRepository(
      final SpringDataSessionJpaRepository sessions,
      final ObjectMapper objectMapper,
      final EntityManager entityManager) {
    this.sessions = sessions;
    this.objectMapper = objectMapper;
    this.entityManager = entityManager;
  }

  @SuppressWarnings("PMD.ShortVariable") // matches the port's own parameter naming
  @Override
  public Optional<Session> findById(final UUID id) {
    return sessions.findById(id).map(this::toDomain);
  }

  @Override
  public void save(final Session session) {
    sessions.save(toEntity(session));
  }

  @Override
  @Transactional
  public void insert(final Session session) {
    entityManager.persist(toEntity(session));
  }

  private SessionEntity toEntity(final Session session) {
    return new SessionEntity(
        session.id(),
        session.accountId().value(),
        objectMapper.writeValueAsString(session.scopes()),
        session.createdAt(),
        session.lastSeenAt(),
        session.revokedAt().orElse(null));
  }

  @Override
  public void revokeAllActiveForAccount(final AccountId accountId) {
    sessions.revokeAllActiveForAccount(accountId.value(), Instant.now());
  }

  private Session toDomain(final SessionEntity entity) {
    final List<String> scopes =
        Arrays.asList(objectMapper.readValue(entity.getScopes(), String[].class));
    return Session.reconstitute(
        entity.getId(),
        new AccountId(entity.getAccountId()),
        scopes,
        entity.getCreatedAt(),
        entity.getLastSeenAt(),
        entity.getRevokedAt());
  }
}
