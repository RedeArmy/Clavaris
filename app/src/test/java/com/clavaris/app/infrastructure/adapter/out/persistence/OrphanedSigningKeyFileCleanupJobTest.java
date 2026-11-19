package com.clavaris.app.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.app.support.RedisBackedIntegrationTest;
import com.clavaris.app.support.TestMailSenderConfig;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.infrastructure.adapter.out.security.OrganizationSigningKeyMaterialFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// TD-SEC-052: a deleted Organization's private signing keys do not outlive it on disk.
@SpringBootTest
@Testcontainers
@Import(TestMailSenderConfig.class)
class OrphanedSigningKeyFileCleanupJobTest extends RedisBackedIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Value("${clavaris.signing-key-store.path}")
  private String keyStorePath;

  @Autowired private OrphanedSigningKeyFileCleanupJob job;
  @Autowired private OrganizationSigningKeyMaterialFactory keyMaterial;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void removesTheKeyFileOfAnOrganizationThatNoLongerExists() throws IOException {
    UUID gone = UUID.randomUUID();
    keyMaterial.generateFor(new OrganizationId(gone));
    makeOld(gone);

    job.sweepOrphanedKeyFiles();

    assertThat(fileOf(gone)).doesNotExist();
  }

  @Test
  void keepsTheKeyFileOfAnOrganizationThatStillExists() throws IOException {
    UUID alive = UUID.randomUUID();
    jdbcTemplate.update(
        "insert into organizations (id, name, owner_platform_account_id) values (?, ?, ?)",
        alive,
        "Still Alive Co",
        UUID.randomUUID());
    keyMaterial.generateFor(new OrganizationId(alive));
    makeOld(alive);

    job.sweepOrphanedKeyFiles();

    assertThat(fileOf(alive)).exists();
  }

  // The file is written before the transaction that creates the Organization commits, so a young
  // file with no row yet is an Organization being born, not an orphan.
  @Test
  void keepsAYoungKeyFileEvenWhenItsOrganizationRowDoesNotExistYet() {
    UUID beingCreated = UUID.randomUUID();
    keyMaterial.generateFor(new OrganizationId(beingCreated));

    job.sweepOrphanedKeyFiles();

    assertThat(fileOf(beingCreated)).exists();
  }

  private Path fileOf(final UUID organizationId) {
    return Path.of(keyStorePath)
        .toAbsolutePath()
        .normalize()
        .getParent()
        .resolve("signing-keys-org-" + organizationId + ".p12");
  }

  private void makeOld(final UUID organizationId) throws IOException {
    Files.setLastModifiedTime(
        fileOf(organizationId), FileTime.from(Instant.now().minus(Duration.ofDays(2))));
  }
}
