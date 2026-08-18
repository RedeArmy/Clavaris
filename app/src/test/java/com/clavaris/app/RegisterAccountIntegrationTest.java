package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.identity.application.usecase.registeraccount.EmailAlreadyRegisteredException;
import com.clavaris.identity.application.usecase.registeraccount.RegisterAccountCommand;
import com.clavaris.identity.application.usecase.registeraccount.RegisterAccountUseCase;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Exercises RegisterAccount against a real Postgres, through every layer — the actual migrations
 * (identity-module/src/main/resources/db/migration), the actual JPA adapters, the actual unique
 * constraint. first-vertical-slice-blueprint.md §2.5's integration-level requirement: prove the
 * unique constraint on accounts.(organization_id, email) actually throws under a genuine concurrent
 * double-insert, not a mocked assumption that it would (test-strategy.md §2).
 */
@SpringBootTest
@Testcontainers
class RegisterAccountIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private RegisterAccountUseCase useCase;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void registersAnAccountAndPersistsItAcrossAllThreeTables() {
    OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
    Email email = new Email("real-flow@example.com");

    var accountId =
        useCase.handle(new RegisterAccountCommand(organizationId, email, "a-valid-password"));

    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from accounts where id = ?", Integer.class, accountId.value()))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from password_credentials where account_id = ?",
                Integer.class,
                accountId.value()))
        .isEqualTo(1);
    // ADR-0007 §1: same transaction as the account insert.
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from event_outbox where aggregate_id = ? and event_type = 'account.created'",
                Integer.class,
                accountId.value()))
        .isEqualTo(1);
  }

  @Test
  void theSameEmailInADifferentOrganizationIsNotAConflict() {
    // BR-ORG-01: uniqueness is scoped to (organization_id, email), not global.
    Email sharedEmail = new Email("shared-address@example.com");

    var firstAccountId =
        useCase.handle(
            new RegisterAccountCommand(
                new OrganizationId(UUID.randomUUID()), sharedEmail, "a-valid-password"));
    var secondAccountId =
        useCase.handle(
            new RegisterAccountCommand(
                new OrganizationId(UUID.randomUUID()), sharedEmail, "a-different-password"));

    assertThat(firstAccountId).isNotEqualTo(secondAccountId);
  }

  @Test
  void aConcurrentDoubleRegistrationOfTheSameEmailInTheSameOrganizationLetsExactlyOneWin()
      throws Exception {
    OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
    Email email = new Email("race@example.com");

    CountDownLatch bothReady = new CountDownLatch(2);
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      List<Future<Boolean>> results =
          List.of(
              executor.submit(
                  () -> attemptRegistration(organizationId, email, "password-one", bothReady, go)),
              executor.submit(
                  () -> attemptRegistration(organizationId, email, "password-two", bothReady, go)));

      bothReady.await(5, TimeUnit.SECONDS);
      go.countDown(); // release both threads at (as close to) the same instant as possible

      AtomicInteger succeeded = new AtomicInteger();
      for (Future<Boolean> result : results) {
        if (result.get(10, TimeUnit.SECONDS)) {
          succeeded.incrementAndGet();
        }
      }

      assertThat(succeeded.get())
          .as("exactly one of the two concurrent registrations must have won the race")
          .isEqualTo(1);
      assertThat(
              jdbcTemplate.queryForObject(
                  "select count(*) from accounts where organization_id = ? and email = ?",
                  Integer.class,
                  organizationId.value(),
                  email.value()))
          .as(
              "the unique constraint must have prevented a second row, not just the application-layer pre-check")
          .isEqualTo(1);
    } finally {
      executor.shutdownNow();
    }
  }

  /**
   * @return true if this attempt won the race (registered successfully), false if it lost
   *     (EmailAlreadyRegisteredException).
   */
  private boolean attemptRegistration(
      OrganizationId organizationId,
      Email email,
      String password,
      CountDownLatch bothReady,
      CountDownLatch go)
      throws InterruptedException {
    bothReady.countDown();
    go.await(5, TimeUnit.SECONDS);
    try {
      useCase.handle(new RegisterAccountCommand(organizationId, email, password));
      return true;
    } catch (EmailAlreadyRegisteredException lostTheRace) {
      return false;
    }
  }
}
