package com.clavaris.identity.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.clavaris.identity.application.usecase.activatesigningkeyfororganization.SigningKeyRepository;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.SigningKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * test-strategy.md §2: a real-Postgres integration test for the per-Organization signing-key
 * adapter — proves {@code toDomain()} really calls {@code SigningKey.reconstitute(...)} (not {@code
 * activate()}, which would mint a fresh id) and that {@code findActive} is genuinely scoped per
 * {@code organization_id}, not a global "any active key" query.
 *
 * <p>Deliberately NOT {@code @DataJpaTest}: confirmed live (client-registry-module's own
 * JpaPlatformClientRepositoryTest) that Spring Boot 4.1 no longer has that test slice at all — see
 * that test's Javadoc for the full finding. Same hand-assembled {@code @SpringBootTest} pattern
 * here.
 *
 * <p>{@code @Transactional}: each test method rolls back automatically rather than relying on a
 * fresh random {@code OrganizationId} per method to avoid row collisions (same real bug already hit
 * and fixed in {@link JpaPlatformSigningKeyRepositoryTest} — that one has no per-tenant scoping to
 * fall back on, so this test gets the same real isolation instead of an accident of unique ids).
 */
@SpringBootTest(classes = JpaSigningKeyRepositoryTest.TestConfig.class)
@Testcontainers
@Transactional
class JpaSigningKeyRepositoryTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private SigningKeyRepository repository;

  @Test
  void savesAndFindsTheActiveKey_reconstituteKeepsTheRealPersistedId() {
    OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
    SigningKey key = SigningKey.activate(organizationId, "a-kid", "RS256");

    repository.save(key);
    Optional<SigningKey> found = repository.findActive(organizationId);

    assertThat(found).isPresent();
    assertThat(found.get().id()).isEqualTo(key.id());
    assertThat(found.get().kid()).isEqualTo("a-kid");
    assertThat(found.get().algorithm()).isEqualTo("RS256");
  }

  @Test
  void findActiveIsScopedToOneOrganizationOnly() {
    // BR-ORG-01/BR-ORG-04: two Organizations' key lifecycles must never leak into each other.
    OrganizationId first = new OrganizationId(UUID.randomUUID());
    OrganizationId second = new OrganizationId(UUID.randomUUID());
    repository.save(SigningKey.activate(first, "first-kid", "RS256"));

    assertThat(repository.findActive(first)).isPresent();
    assertThat(repository.findActive(second)).isEmpty();
  }

  // SDE-III review, 2026-09-03: proves lockForRotation is a real, callable Postgres advisory-lock
  // query (not a typo'd native SQL string that would only surface at first real use) — the actual
  // serialization behavior is proven end-to-end, real concurrent HTTP calls, by
  // SigningKeyRotationIntegrationTest; a single-threaded test here can't demonstrate blocking.
  @Test
  void lockForRotationCompletesWithoutErrorAndDoesNotBlockASubsequentCallInTheSameTransaction() {
    OrganizationId organizationId = new OrganizationId(UUID.randomUUID());

    // Postgres advisory locks are re-entrant within the same session/transaction — a second call
    // here must not deadlock against the first, and (SonarCloud finding) this is now a real
    // assertion, not just two unchecked calls: a deadlock or a malformed query would surface as a
    // thrown exception, which this explicitly asserts never happens.
    assertThatCode(
            () -> {
              repository.lockForRotation(organizationId);
              repository.lockForRotation(organizationId);
            })
        .doesNotThrowAnyException();
  }

  @Test
  void findActiveIgnoresARetiredKey() {
    OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
    SigningKey key = SigningKey.activate(organizationId, "a-kid", "RS256");
    key.retire();

    repository.save(key);

    assertThat(repository.findActive(organizationId)).isEmpty();
  }

  // TD-SEC-008: proves findActiveAndRetiredSince returns both the active key and a recently-
  // retired one still within the overlap window.
  @Test
  void findActiveAndRetiredSinceReturnsBothTheActiveKeyAndARecentlyRetiredOne() {
    OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
    Instant now = Instant.now();
    SigningKey retiredFiveMinutesAgo =
        SigningKey.reconstitute(
            UUID.randomUUID(),
            organizationId,
            "retired-kid",
            "RS256",
            now.minus(1, ChronoUnit.HOURS),
            now.minus(5, ChronoUnit.MINUTES));
    SigningKey active =
        SigningKey.reconstitute(
            UUID.randomUUID(), organizationId, "active-kid", "RS256", now, null);
    repository.save(retiredFiveMinutesAgo);
    repository.save(active);

    List<SigningKey> found =
        repository.findActiveAndRetiredSince(organizationId, now.minus(1, ChronoUnit.DAYS));

    assertThat(found)
        // TD-ARCH-009 (SigningKey/PlatformSigningKey extraction): kid() is inherited from the
        // package-private AbstractSigningKey — a SigningKey::kid method reference from this
        // different package fails at runtime (LambdaConversionException: "MethodHandle is not
        // direct or cannot be cracked", a known JDK limitation cracking a direct method handle
        // for a method whose declaring class isn't accessible to the caller, even though the
        // method itself is public and reachable through the public SigningKey subclass). A plain
        // lambda calling key.kid() compiles to ordinary virtual-dispatch bytecode instead and
        // doesn't hit this path.
        .extracting(key -> key.kid())
        .containsExactlyInAnyOrder("retired-kid", "active-kid");
  }

  // TD-SEC-008: a key retired before the overlap cutoff must age out of the result — this is what
  // keeps a compromised key's material from staying "live" in JWKS forever.
  @Test
  void findActiveAndRetiredSinceExcludesAKeyRetiredBeforeTheCutoff() {
    OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
    Instant now = Instant.now();
    SigningKey retiredTwoDaysAgo =
        SigningKey.reconstitute(
            UUID.randomUUID(),
            organizationId,
            "long-retired-kid",
            "RS256",
            now.minus(3, ChronoUnit.DAYS),
            now.minus(2, ChronoUnit.DAYS));
    repository.save(retiredTwoDaysAgo);

    List<SigningKey> found =
        repository.findActiveAndRetiredSince(organizationId, now.minus(1, ChronoUnit.DAYS));

    assertThat(found).isEmpty();
  }

  // TD-SEC-008: regression test for a real bug caught and fixed while writing this query — a
  // derived Spring Data method name (findByOrganizationIdAndRetiredAtIsNullOrRetiredAtAfter)
  // parses as (organizationId = X AND retiredAt IS NULL) OR retiredAt > Y, which would leak every
  // OTHER Organization's still-in-window retired keys into this one's own JWKS. The @Query this
  // repository actually uses must never regress to that shape.
  @Test
  void findActiveAndRetiredSinceNeverLeaksAnotherOrganizationsRetiredKey() {
    OrganizationId thisOrganization = new OrganizationId(UUID.randomUUID());
    OrganizationId otherOrganization = new OrganizationId(UUID.randomUUID());
    Instant now = Instant.now();
    SigningKey otherOrganizationsRecentlyRetiredKey =
        SigningKey.reconstitute(
            UUID.randomUUID(),
            otherOrganization,
            "other-org-kid",
            "RS256",
            now.minus(1, ChronoUnit.HOURS),
            now.minus(1, ChronoUnit.MINUTES));
    repository.save(otherOrganizationsRecentlyRetiredKey);

    List<SigningKey> found =
        repository.findActiveAndRetiredSince(thisOrganization, now.minus(1, ChronoUnit.DAYS));

    assertThat(found)
        .as("a query bug here is a real cross-tenant signing-key-material leak, not cosmetic")
        .isEmpty();
  }

  // TD-SEC-029: findByKid must find a key regardless of active/retired state — the emergency
  // purge needs to be able to target an already-rotated-away key discovered compromised after
  // the fact, not just the currently active one.
  @Test
  void findByKidFindsBothAnActiveAndAnAlreadyRetiredKey() {
    OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
    SigningKey active = SigningKey.activate(organizationId, "active-kid", "RS256");
    SigningKey retired = SigningKey.activate(organizationId, "retired-kid", "RS256");
    retired.retire();
    repository.save(active);
    repository.save(retired);

    assertThat(repository.findByKid(organizationId, "active-kid")).isPresent();
    assertThat(repository.findByKid(organizationId, "retired-kid")).isPresent();
  }

  @Test
  void findByKidIsScopedToOneOrganizationOnly() {
    OrganizationId first = new OrganizationId(UUID.randomUUID());
    OrganizationId second = new OrganizationId(UUID.randomUUID());
    repository.save(SigningKey.activate(first, "shared-looking-kid", "RS256"));

    assertThat(repository.findByKid(first, "shared-looking-kid")).isPresent();
    assertThat(repository.findByKid(second, "shared-looking-kid")).isEmpty();
  }

  @Test
  void findByKidIsEmptyForAnUnknownKid() {
    OrganizationId organizationId = new OrganizationId(UUID.randomUUID());

    assertThat(repository.findByKid(organizationId, "never-registered-kid")).isEmpty();
  }

  // @Import, not @ComponentScan: this package also holds JpaPlatformSigningKeyRepositoryTest's
  // own nested TestConfig (same class shape, same package) — confirmed live that scanning the
  // whole package picks up that sibling test's @Configuration too and double-registers its
  // Spring Data repositories across both contexts. @Import registers exactly the one bean this
  // test needs, nothing else. Same reasoning applies to @EnableJpaRepositories' includeFilters
  // below — restricts it to this test's own Spring Data interface, not every repository in the
  // package.
  @Configuration
  @EnableAutoConfiguration
  @EnableJpaRepositories(
      basePackageClasses = SpringDataSigningKeyJpaRepository.class,
      includeFilters =
          @ComponentScan.Filter(
              type = FilterType.ASSIGNABLE_TYPE,
              classes = SpringDataSigningKeyJpaRepository.class))
  @Import(JpaSigningKeyRepository.class)
  static class TestConfig {}
}
