package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.app.support.RedisBackedIntegrationTest;
import com.clavaris.app.support.TestMailSenderConfig;
import com.clavaris.organization.application.usecase.addaccessrestrictionentry.AccessRestrictionLimitExceededException;
import com.clavaris.organization.application.usecase.addaccessrestrictionentry.AddAccessRestrictionEntryCommand;
import com.clavaris.organization.application.usecase.addaccessrestrictionentry.AddAccessRestrictionEntryService;
import com.clavaris.organization.application.usecase.addaccessrestrictionentry.AddAccessRestrictionEntryUseCase;
import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.RestrictionType;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * TD-SEC-094, against the real service and a real PostgreSQL: the cap on an Organization's
 * access-restriction entries is strict. The duplicate check, the count and the insert used to be
 * three steps with nothing between them, so parallel adds each passed the count and the list ended
 * above the limit.
 */
@SpringBootTest
@Testcontainers
@Import(TestMailSenderConfig.class)
class AccessRestrictionLimitConcurrencyIntegrationTest extends RedisBackedIntegrationTest {

  private static final int LIMIT = AddAccessRestrictionEntryService.MAX_ENTRIES_PER_ORGANIZATION;
  private static final int ROOM = 2;
  private static final int ADDERS = 10;

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private AddAccessRestrictionEntryUseCase addEntry;
  @Autowired private OrganizationRepository organizations;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void parallelAddsNeverTakeTheListPastTheLimit() throws Exception {
    final Organization organization = Organization.register("Cap Co", UUID.randomUUID());
    organizations.save(organization);
    final UUID organizationId = organization.id();
    // Leave room for exactly two more entries.
    for (int i = 0; i < LIMIT - ROOM; i++) {
      jdbcTemplate.update(
          "insert into access_restriction_entries (id, organization_id, type, identifier)"
              + " values (?, ?, 'BLOCKLIST', ?)",
          UUID.randomUUID(),
          organizationId,
          "seed-" + i + "@example.com");
    }

    final CyclicBarrier together = new CyclicBarrier(ADDERS);
    final AtomicInteger accepted = new AtomicInteger();
    final AtomicInteger refused = new AtomicInteger();
    final ExecutorService pool = Executors.newFixedThreadPool(ADDERS);
    try {
      final List<Future<?>> adds = new ArrayList<>();
      for (int i = 0; i < ADDERS; i++) {
        final String identifier = "racer-" + i + "@example.com";
        adds.add(
            pool.submit(
                () -> {
                  together.await();
                  try {
                    addEntry.handle(
                        new AddAccessRestrictionEntryCommand(
                            organizationId, RestrictionType.ALLOWLIST, identifier));
                    accepted.incrementAndGet();
                  } catch (final AccessRestrictionLimitExceededException _) {
                    refused.incrementAndGet();
                  }
                  return null;
                }));
      }
      for (final Future<?> add : adds) {
        add.get();
      }
    } finally {
      pool.shutdownNow();
    }

    final Integer stored =
        jdbcTemplate.queryForObject(
            "select count(*) from access_restriction_entries where organization_id = ?",
            Integer.class,
            organizationId);
    assertThat(stored).as("entries stored").isEqualTo(LIMIT);
    assertThat(accepted.get()).as("adds accepted").isEqualTo(ROOM);
    assertThat(refused.get()).as("adds refused for a full list").isEqualTo(ADDERS - ROOM);
  }
}
