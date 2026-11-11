package com.clavaris.identity.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.domain.model.OrganizationId;
import jakarta.persistence.EntityManagerFactory;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * TD-PERF-057, against a real Postgres: deleting every Account of an Organization is one set-based
 * statement, whatever the number of Accounts. It used to be a Spring Data derived delete, which
 * loads each entity and removes it on its own (verified in spring-data-jpa 4.1.2's
 * DeleteExecution), so an Organization with a million Accounts meant a million entities in memory
 * and a million DELETEs in one transaction.
 *
 * <p>What the test counts is the SQL statements Hibernate prepares, because the result alone cannot
 * tell the two apart: both remove the same rows. It also checks that the database cascades still
 * remove what hangs from each Account, and that another Organization is left alone.
 */
@SpringBootTest(classes = JpaAccountBulkDeleteTest.TestConfig.class)
@Testcontainers
@Transactional
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class JpaAccountBulkDeleteTest {

  private static final int ACCOUNTS = 60;

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private AccountRepository repository;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EntityManagerFactory entityManagerFactory;

  @Test
  void deletingAnOrganizationsAccountsIsOneStatementNotOnePerAccount() {
    final UUID organization = UUID.randomUUID();
    final UUID other = UUID.randomUUID();
    seed(organization, ACCOUNTS);
    seed(other, 2);
    final Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    statistics.clear();

    repository.deleteAllByOrganizationId(new OrganizationId(organization));

    assertThat(statistics.getPrepareStatementCount())
        .as("a derived delete prepares one statement per Account, this one prepares one in all")
        .isLessThan(5);
    assertThat(count("accounts", organization)).isZero();
    assertThat(count("accounts", other)).as("the other Organization is untouched").isEqualTo(2);
  }

  @Test
  void theDatabaseCascadesStillRemoveWhatHangsFromEachAccount() {
    final UUID organization = UUID.randomUUID();
    seed(organization, 3);

    repository.deleteAllByOrganizationId(new OrganizationId(organization));

    assertThat(jdbc.queryForObject("select count(*) from password_credentials", Integer.class))
        .as("no credential row is left pointing at a deleted Account")
        .isZero();
  }

  private void seed(final UUID organizationId, final int accounts) {
    for (int i = 0; i < accounts; i++) {
      final UUID id = UUID.randomUUID();
      jdbc.update(
          "insert into accounts (id, organization_id, email, status, created_at)"
              + " values (?, ?, ?, 'ACTIVE', now())",
          id,
          organizationId,
          "bulk-" + id + "@example.com");
      jdbc.update(
          "insert into password_credentials (account_id, password_hash) values (?, ?)",
          id,
          "not-a-real-hash-this-suite-never-logs-in");
    }
  }

  private int count(final String table, final UUID organizationId) {
    return jdbc.queryForObject(
        "select count(*) from " + table + " where organization_id = ?",
        Integer.class,
        organizationId);
  }

  @Configuration
  @EnableAutoConfiguration
  @EnableJpaRepositories(
      basePackageClasses = SpringDataAccountJpaRepository.class,
      includeFilters = {
        @ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = SpringDataAccountJpaRepository.class),
        @ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = SpringDataPasswordCredentialJpaRepository.class)
      })
  @Import(JpaAccountRepository.class)
  static class TestConfig {}
}
