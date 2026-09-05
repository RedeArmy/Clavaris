package com.clavaris.clientregistry.infrastructure.adapter.out.persistence;

import com.clavaris.clientregistry.application.usecase.setredirectpolicyforclient.RedirectPolicyRepository;
import com.clavaris.clientregistry.domain.model.RedirectPolicy;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Implements the outbound port. {@code save} doubles as insert-or-update — same "id is always a
 * real, already-assigned UUID, no {@code @GeneratedValue}" reasoning as {@code
 * JpaRateLimitPolicyRepository}'s own identical Javadoc.
 */
@Repository
class JpaRedirectPolicyRepository implements RedirectPolicyRepository {

  private final SpringDataRedirectPolicyJpaRepository policies;

  /* package */ JpaRedirectPolicyRepository(final SpringDataRedirectPolicyJpaRepository policies) {
    this.policies = policies;
  }

  @Override
  public Optional<RedirectPolicy> findByOAuthClientId(final UUID oauthClientId) {
    return policies.findByOauthClientId(oauthClientId).map(this::toDomain);
  }

  @Override
  public void save(final RedirectPolicy policy) {
    policies.save(
        new RedirectPolicyEntity(
            policy.id(),
            policy.oauthClientId(),
            policy.fallbackSignInRedirectUrl().orElse(null),
            policy.fallbackSignUpRedirectUrl().orElse(null),
            policy.forceSignInRedirectUrl().orElse(null),
            policy.forceSignUpRedirectUrl().orElse(null),
            policy.createdAt(),
            policy.updatedAt()));
  }

  private RedirectPolicy toDomain(final RedirectPolicyEntity entity) {
    return RedirectPolicy.reconstitute(
        entity.getId(),
        entity.getOauthClientId(),
        entity.getFallbackSignInRedirectUrl(),
        entity.getFallbackSignUpRedirectUrl(),
        entity.getForceSignInRedirectUrl(),
        entity.getForceSignUpRedirectUrl(),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }
}
