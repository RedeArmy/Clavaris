package com.clavaris.clientregistry.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.domain.model.ConcurrentClientModificationException;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.common.domain.model.KeysetPageRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * test-strategy.md §2: a real-Postgres integration test for the tenant-scoped OAuth client adapter
 * — proves {@code toDomain()} really calls {@code OAuthClient.reconstitute(...)} and that all
 * JSON-serialized list columns (redirect_uris, allowed_grant_types, allowed_scopes,
 * post_logout_redirect_uris) round-trip correctly against the real migrated schema.
 *
 * <p>Deliberately NOT {@code @DataJpaTest} — see {@code JpaPlatformClientRepositoryTest}'s own
 * Javadoc for the full finding (removed in Spring Boot 4.1). {@code @Import}, not
 * {@code @ComponentScan}, for the same reason that test's {@code TestConfig} now uses it too: this
 * package holds both tests' nested {@code @Configuration} classes.
 */
@SpringBootTest(classes = JpaOAuthClientRepositoryTest.TestConfig.class)
@Testcontainers
class JpaOAuthClientRepositoryTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private OAuthClientRepository repository;
  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  void savesAndReadsBackAnOAuthClient_reconstituteKeepsTheRealPersistedId() {
    UUID organizationId = UUID.randomUUID();
    OAuthClient client =
        OAuthClient.register(
            organizationId,
            "a-client-id",
            "argon2id$hashed",
            List.of("https://jobseeker.example.com/callback"),
            List.of("authorization_code", "refresh_token"),
            List.of("openid", "profile"),
            false,
            List.of("https://jobseeker.example.com/logged-out"));

    repository.save(client);
    Optional<OAuthClient> found = repository.findByClientId("a-client-id");

    assertThat(found).isPresent();
    assertThat(found.get().id()).isEqualTo(client.id());
    assertThat(found.get().organizationId()).isEqualTo(organizationId);
    assertThat(found.get().clientSecretHash()).isEqualTo("argon2id$hashed");
    assertThat(found.get().redirectUris())
        .containsExactly("https://jobseeker.example.com/callback");
    assertThat(found.get().allowedGrantTypes())
        .containsExactly("authorization_code", "refresh_token");
    assertThat(found.get().allowedScopes()).containsExactly("openid", "profile");
    // TD-SEC-026/ADR-0017: the boolean column must round-trip too — false chosen deliberately
    // here (not the DB column's own default true) so this test cannot pass by accident if the
    // column were never actually wired through JpaOAuthClientRepository at all.
    assertThat(found.get().requireConsent()).isFalse();
    // TD-FUT-018: same "prove it's really wired, not just present" bar as requireConsent above.
    assertThat(found.get().postLogoutRedirectUris())
        .containsExactly("https://jobseeker.example.com/logged-out");
    // SDE-III review, 2026-09-11: a freshly-registered client is active by default.
    assertThat(found.get().active()).isTrue();
  }

  // SDE-III review, 2026-09-11: the real, load-bearing behavior behind switching save() from an
  // insert-only entityManager.persist to SpringData's own upsert save — a second save() call for
  // the same id must update the existing row, not throw a duplicate-key violation.
  @Test
  void savingAnAlreadyPersistedClientAgainUpdatesTheExistingRowRatherThanFailing() {
    OAuthClient client =
        OAuthClient.register(
            UUID.randomUUID(),
            "an-updatable-client-id",
            "argon2id$hashed",
            List.of("https://jobseeker.example.com/callback"),
            List.of("authorization_code"),
            List.of("openid"),
            true,
            List.of());
    repository.save(client);

    OAuthClient deactivated = client.deactivate();
    repository.save(deactivated);
    Optional<OAuthClient> found = repository.findByClientId("an-updatable-client-id");

    assertThat(found).isPresent();
    assertThat(found.get().id()).isEqualTo(client.id());
    assertThat(found.get().active()).isFalse();
  }

  @Test
  void savingARotatedSecretUpdatesTheClientSecretHash() {
    OAuthClient client =
        OAuthClient.register(
            UUID.randomUUID(),
            "a-rotatable-client-id",
            "argon2id$original-hashed",
            List.of("https://jobseeker.example.com/callback"),
            List.of("authorization_code"),
            List.of("openid"),
            true,
            List.of());
    repository.save(client);

    repository.save(client.rotateSecret("argon2id$rotated-hashed"));
    Optional<OAuthClient> found = repository.findByClientId("a-rotatable-client-id");

    assertThat(found).isPresent();
    assertThat(found.get().clientSecretHash()).isEqualTo("argon2id$rotated-hashed");
  }

  // SDE-III review, 2026-09-15: the real regression this guards — before this fix, save() built a
  // brand-new detached entity with no concurrency guard at all, so a second save() against a row
  // already mutated by another transaction silently won regardless of what it had actually read.
  // A single-threaded, deterministic proof of the row-count-driven version bump; real concurrent-
  // caller behavior is proved separately below, since sequential calls in one JVM thread can't
  // show whether a genuinely concurrent second caller is forced to observe the conflict.
  @Test
  void savingAStaleReadLosesToAnAlreadyAppliedConcurrentChange() {
    OAuthClient client =
        OAuthClient.register(
            UUID.randomUUID(),
            "a-racy-client-id",
            "argon2id$hashed",
            List.of("https://jobseeker.example.com/callback"),
            List.of("authorization_code"),
            List.of("openid"),
            true,
            List.of());
    repository.save(client); // DB version now 0 (first INSERT)

    // Two independent readers, both looking at the same DB version 0 row.
    OAuthClient readerA = repository.findByClientId("a-racy-client-id").orElseThrow();
    OAuthClient readerB = repository.findByClientId("a-racy-client-id").orElseThrow();

    repository.save(readerA.deactivate()); // wins: DB version 0 -> 1

    assertThatExceptionOfType(ConcurrentClientModificationException.class)
        .isThrownBy(() -> repository.save(readerB.rotateSecret("argon2id$rotated-hashed")));

    // The loser's change never landed — active stays false (readerA's own write), the secret
    // hash stays the original one (readerB's rotate never committed).
    OAuthClient found = repository.findByClientId("a-racy-client-id").orElseThrow();
    assertThat(found.active()).isFalse();
    assertThat(found.clientSecretHash()).isEqualTo("argon2id$hashed");
  }

  // BR-something-like real-concurrency proof:
  // savingAStaleReadLosesToAnAlreadyAppliedConcurrentChange
  // above only proves the row-version mechanism reacts correctly to a *sequential* stale write —
  // it cannot prove PostgreSQL/Hibernate actually force a *genuinely concurrent* second
  // transaction to observe that conflict rather than, say, silently interleaving. This test proves
  // that, against a real database, the same way JpaRefreshTokenRepositoryTest's own identical
  // real-concurrency proof does — two separate threads, two separate transactions, two separate
  // connections, racing the exact same row: one "revoke," one "rotate secret," exactly the
  // production race this whole fix closes.
  @Test
  void concurrentDeactivateAndRotateSecretAgainstTheSameClientNeverBothWin_realConcurrencyProof() {
    OAuthClient client =
        OAuthClient.register(
            UUID.randomUUID(),
            "a-concurrently-raced-client-id",
            "argon2id$hashed",
            List.of("https://jobseeker.example.com/callback"),
            List.of("authorization_code"),
            List.of("openid"),
            true,
            List.of());
    repository.save(client);
    OAuthClient readAtVersionZero =
        repository.findByClientId("a-concurrently-raced-client-id").orElseThrow();
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

    // ConcurrentClientModificationException must be caught OUTSIDE transactionTemplate.execute,
    // not inside its own callback: saveAndFlush's own flush() marks the transaction rollback-only
    // the instant Hibernate detects the version mismatch (standard JPA behavior — the persistence
    // context is unusable for the rest of that transaction regardless of whether application code
    // catches the translated exception afterward), so a callback that swallows the exception and
    // returns normally makes TransactionTemplate try to commit an already-doomed transaction,
    // itself throwing UnexpectedRollbackException instead of ever reaching this test's own
    // assertions — confirmed live, an earlier version of this exact test failed on precisely this.
    CompletableFuture<Boolean> deactivateAttempt =
        CompletableFuture.supplyAsync(
            () -> attemptSave(transactionTemplate, readAtVersionZero.deactivate()));
    CompletableFuture<Boolean> rotateAttempt =
        CompletableFuture.supplyAsync(
            () ->
                attemptSave(
                    transactionTemplate,
                    readAtVersionZero.rotateSecret("argon2id$rotated-hashed")));

    boolean deactivateWon = deactivateAttempt.join();
    boolean rotateWon = rotateAttempt.join();

    assertThat(deactivateWon ^ rotateWon)
        .as("exactly one of two concurrent callers may ever win against the same version")
        .isTrue();
  }

  // See concurrentDeactivateAndRotateSecretAgainstTheSameClientNeverBothWin_realConcurrencyProof's
  // own comment for why the catch lives here, outside transactionTemplate.execute's own callback.
  private boolean attemptSave(
      final TransactionTemplate transactionTemplate, final OAuthClient toSave) {
    try {
      transactionTemplate.executeWithoutResult(status -> repository.save(toSave));
      return true;
    } catch (final ConcurrentClientModificationException _) {
      return false;
    }
  }

  @Test
  void findByClientIdIsEmptyForAnUnknownClient() {
    assertThat(repository.findByClientId(UUID.randomUUID().toString())).isEmpty();
  }

  @Test
  void findByIdReturnsTheSameClientLookedUpByItsOwnPersistedId() {
    // TD-SEC-010: this is the exact lookup OrganizationRegisteredClientRepository.findById now
    // performs on JdbcOAuth2AuthorizationService's behalf (TD-SEC-003) when reloading a persisted
    // OAuth2Authorization row.
    OAuthClient client =
        OAuthClient.register(
            UUID.randomUUID(),
            "another-client-id",
            "argon2id$hashed",
            List.of("https://jobseeker.example.com/callback"),
            List.of("authorization_code"),
            List.of("openid"),
            true,
            List.of());
    repository.save(client);

    Optional<OAuthClient> found = repository.findById(client.id());

    assertThat(found).isPresent();
    assertThat(found.get().clientId()).isEqualTo("another-client-id");
  }

  @Test
  void findByIdIsEmptyForAnUnknownId() {
    assertThat(repository.findById(UUID.randomUUID())).isEmpty();
  }

  // SDE-III review, 2026-09-11: this method genuinely didn't exist until now — see this port's own
  // Javadoc (technical-debt-register.md TD-FUT-032) for why.
  @Test
  void findAllByOrganizationIdReturnsOnlyThatOrganizationsOwnClients() {
    UUID organizationId = UUID.randomUUID();
    UUID otherOrganizationId = UUID.randomUUID();
    OAuthClient ownClient =
        OAuthClient.register(
            organizationId,
            "own-client-id",
            "argon2id$hashed",
            List.of("https://jobseeker.example.com/callback"),
            List.of("authorization_code"),
            List.of("openid"),
            true,
            List.of());
    OAuthClient otherOrganizationClient =
        OAuthClient.register(
            otherOrganizationId,
            "other-org-client-id",
            "argon2id$hashed",
            List.of("https://jobseeker.example.com/callback"),
            List.of("authorization_code"),
            List.of("openid"),
            true,
            List.of());
    repository.save(ownClient);
    repository.save(otherOrganizationClient);

    List<OAuthClient> found = repository.findAllByOrganizationId(organizationId);

    assertThat(found).extracting(OAuthClient::clientId).containsExactly("own-client-id");
  }

  @Test
  void findAllByOrganizationIdIsEmptyForAnOrganizationWithNoClients() {
    assertThat(repository.findAllByOrganizationId(UUID.randomUUID())).isEmpty();
  }

  // TD-PERF-020 (keyset revision, 2026-09-14): real-Postgres proof of the paginated sibling,
  // newest-first, forward and backward navigation — same "reconstitute with explicit createdAt
  // instants" discipline organization-module's JpaOrganizationRepositoryTest own identical test
  // already establishes.
  @Test
  void findKeysetPageByOrganizationIdReturnsNewestFirstAndSupportsForwardAndBackwardNavigation() {
    UUID organizationId = UUID.randomUUID();
    Instant now = Instant.now();
    OAuthClient first = reconstituteAt(organizationId, "client-first", now.minusSeconds(20));
    OAuthClient second = reconstituteAt(organizationId, "client-second", now.minusSeconds(10));
    OAuthClient third = reconstituteAt(organizationId, "client-third", now);
    repository.save(first);
    repository.save(second);
    repository.save(third);
    repository.save(reconstituteAt(UUID.randomUUID(), "client-other-org", now));

    KeysetPage<OAuthClient> firstPage =
        repository.findKeysetPageByOrganizationId(
            organizationId, new KeysetPageRequest(null, null, 2));

    assertThat(firstPage.content())
        .extracting(OAuthClient::id)
        .containsExactly(third.id(), second.id());
    assertThat(firstPage.hasNext()).isTrue();
    assertThat(firstPage.hasPrevious()).isFalse();

    KeysetPage<OAuthClient> secondPage =
        repository.findKeysetPageByOrganizationId(
            organizationId, new KeysetPageRequest(firstPage.endCursor(), null, 2));

    assertThat(secondPage.content()).extracting(OAuthClient::id).containsExactly(first.id());
    assertThat(secondPage.hasNext()).isFalse();
    assertThat(secondPage.hasPrevious()).isTrue();

    KeysetPage<OAuthClient> backToFirstPage =
        repository.findKeysetPageByOrganizationId(
            organizationId, new KeysetPageRequest(null, secondPage.startCursor(), 2));

    assertThat(backToFirstPage.content())
        .extracting(OAuthClient::id)
        .containsExactly(third.id(), second.id());
    assertThat(backToFirstPage.hasNext()).isTrue();
    assertThat(backToFirstPage.hasPrevious()).isFalse();
  }

  private static OAuthClient reconstituteAt(
      final UUID organizationId, final String clientId, final Instant createdAt) {
    return OAuthClient.reconstitute(
        UUID.randomUUID(),
        organizationId,
        clientId,
        "hash",
        List.of("https://example.com/callback"),
        List.of("authorization_code"),
        List.of("openid"),
        true,
        List.of(),
        createdAt,
        true,
        0);
  }

  // @Import, not @ComponentScan — see JpaPlatformClientRepositoryTest's own TestConfig comment
  // for why: this package also holds that test's nested TestConfig, and scanning the whole
  // package here would pick it up too, double-registering Spring Data repositories across both
  // contexts (confirmed live, identity-module).
  @Configuration
  @EnableAutoConfiguration
  @EnableJpaRepositories(
      basePackageClasses = SpringDataOAuthClientJpaRepository.class,
      includeFilters =
          @ComponentScan.Filter(
              type = FilterType.ASSIGNABLE_TYPE,
              classes = SpringDataOAuthClientJpaRepository.class))
  @Import(JpaOAuthClientRepository.class)
  static class TestConfig {}
}
