package com.clavaris.organization.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.application.usecase.setaccountauthenticationpolicyfororganization.AccountAuthenticationPolicyRepository;
import com.clavaris.organization.domain.model.AccountAuthenticationPolicy;
import com.clavaris.organization.domain.model.AccountAuthenticationSettings;
import com.clavaris.organization.domain.model.EmailVerificationMethod;
import com.clavaris.organization.domain.model.Organization;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * ADR-0024: real-Postgres integration test — same rationale/pattern as
 * JpaRateLimitPolicyRepositoryTest. A real {@code organizations} row is required before any {@code
 * AccountAuthenticationPolicy} save: {@code account_authentication_policies.organization_id} is a
 * real FK (both tables are this module's own migrations, so their ordering is guaranteed).
 */
@SpringBootTest(classes = JpaAccountAuthenticationPolicyRepositoryTest.TestConfig.class)
@Testcontainers
class JpaAccountAuthenticationPolicyRepositoryTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private OrganizationRepository organizations;
  @Autowired private AccountAuthenticationPolicyRepository policies;
  @Autowired private SpringDataAccountAuthenticationPolicyJpaRepository springDataRepository;

  @Test
  void savesAPolicyAndPersistsItsRealFields() {
    UUID organizationId = registerARealOrganization();
    AccountAuthenticationPolicy policy =
        AccountAuthenticationPolicy.define(
            organizationId,
            new AccountAuthenticationSettings(
                true, EmailVerificationMethod.CODE, true, false, false, true, false, true, false));

    policies.save(policy);

    AccountAuthenticationPolicyEntity persisted =
        springDataRepository.findById(policy.id()).orElseThrow();
    assertThat(persisted.getOrganizationId()).isEqualTo(organizationId);
    assertThat(persisted.isEmailVerificationRequiredAtSignIn()).isTrue();
    assertThat(persisted.getEmailVerificationMethod()).isEqualTo(EmailVerificationMethod.CODE);
    assertThat(persisted.isPasswordAtSignUpEnabled()).isFalse();
    assertThat(persisted.isDeviceTrustEnabled()).isTrue();
  }

  @Test
  void findByOrganizationIdReturnsEmptyWhenNoPolicyHasEverBeenSetForThatOrganization() {
    UUID organizationId = registerARealOrganization();

    Optional<AccountAuthenticationPolicy> found = policies.findByOrganizationId(organizationId);

    assertThat(found)
        .as(
            "absence must mean \"use AccountAuthenticationPolicy.defaults()\" (ADR-0024), not an"
                + " error")
        .isEmpty();
  }

  @Test
  void savingASecondTimeUpdatesTheSameRowRatherThanInsertingASecondOne() {
    UUID organizationId = registerARealOrganization();
    AccountAuthenticationPolicy original = AccountAuthenticationPolicy.defaults(organizationId);
    policies.save(original);

    AccountAuthenticationPolicy updated =
        original.withPolicy(
            new AccountAuthenticationSettings(
                true, EmailVerificationMethod.BOTH, true, true, true, true, false, true, false));
    policies.save(updated);

    assertThat(
            springDataRepository.findAll().stream()
                .filter(row -> row.getOrganizationId().equals(organizationId))
                .count())
        .as("one Organization must never accumulate two policy rows")
        .isEqualTo(1);
    AccountAuthenticationPolicy found = policies.findByOrganizationId(organizationId).orElseThrow();
    assertThat(found.emailVerificationMethod()).isEqualTo(EmailVerificationMethod.BOTH);
  }

  // TD-ARCH-046: the mapping from the five columns to the record is the one place the switches are
  // still positional, so each is flipped alone: a swapped pair of columns would come back in the
  // wrong place.
  @Test
  void eachSwitchSurvivesTheDatabaseInItsOwnPlace() {
    final List<UnaryOperator<AccountAuthenticationPolicy>> flips =
        List.of(
            policy -> policy.withSelfServiceEmailChange(false),
            policy -> policy.withCodeAfterPasswordRequired(true),
            policy -> policy.withTotpEnabled(true),
            policy -> policy.withPasskeysEnabled(false),
            policy -> policy.withPasskeyOfferEnabled(false));

    for (final UnaryOperator<AccountAuthenticationPolicy> flip : flips) {
      final UUID organizationId = registerARealOrganization();
      final AccountAuthenticationPolicy flipped =
          flip.apply(AccountAuthenticationPolicy.defaults(organizationId));
      policies.save(flipped);

      final AccountAuthenticationPolicy found =
          policies.findByOrganizationId(organizationId).orElseThrow();

      assertThat(found.security()).isEqualTo(flipped.security());
    }
  }

  private UUID registerARealOrganization() {
    Organization organization = Organization.register("Auth Policy Co", UUID.randomUUID());
    organizations.save(organization);
    return organization.id();
  }

  @Configuration
  @EnableAutoConfiguration
  @EnableJpaRepositories(
      basePackageClasses = {
        SpringDataOrganizationJpaRepository.class,
        SpringDataAccountAuthenticationPolicyJpaRepository.class
      })
  @Import({JpaOrganizationRepository.class, JpaAccountAuthenticationPolicyRepository.class})
  static class TestConfig {}
}
