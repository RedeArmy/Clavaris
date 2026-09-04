package com.clavaris.organization.infrastructure.adapter.out.persistence;

import com.clavaris.organization.application.usecase.setaccountauthenticationpolicyfororganization.AccountAuthenticationPolicyRepository;
import com.clavaris.organization.domain.model.AccountAuthenticationPolicy;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Implements the outbound port. {@code save} doubles as insert-or-update — same "id is always a
 * real, already-assigned UUID by the time this is called" reasoning {@code
 * JpaRateLimitPolicyRepository}'s own identical Javadoc already establishes.
 */
@Repository
class JpaAccountAuthenticationPolicyRepository implements AccountAuthenticationPolicyRepository {

  private final SpringDataAccountAuthenticationPolicyJpaRepository policies;

  /* package */ JpaAccountAuthenticationPolicyRepository(
      final SpringDataAccountAuthenticationPolicyJpaRepository policies) {
    this.policies = policies;
  }

  @Override
  public Optional<AccountAuthenticationPolicy> findByOrganizationId(final UUID organizationId) {
    return policies.findByOrganizationId(organizationId).map(this::toDomain);
  }

  @Override
  public void save(final AccountAuthenticationPolicy policy) {
    policies.save(
        new AccountAuthenticationPolicyEntity(
            policy.id(),
            policy.organizationId(),
            policy.emailVerificationRequiredAtSignIn(),
            policy.emailVerificationMethod(),
            policy.emailCodeSignInEnabled(),
            policy.emailLinkSignInEnabled(),
            policy.usernameSignUpEnabled(),
            policy.usernameRequired(),
            policy.usernameSignInEnabled(),
            policy.passwordAtSignUpEnabled(),
            policy.deviceTrustEnabled(),
            policy.createdAt(),
            policy.updatedAt()));
  }

  private AccountAuthenticationPolicy toDomain(final AccountAuthenticationPolicyEntity entity) {
    return AccountAuthenticationPolicy.reconstitute(
        entity.getId(),
        entity.getOrganizationId(),
        entity.isEmailVerificationRequiredAtSignIn(),
        entity.getEmailVerificationMethod(),
        entity.isEmailCodeSignInEnabled(),
        entity.isEmailLinkSignInEnabled(),
        entity.isUsernameSignUpEnabled(),
        entity.isUsernameRequired(),
        entity.isUsernameSignInEnabled(),
        entity.isPasswordAtSignUpEnabled(),
        entity.isDeviceTrustEnabled(),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }
}
