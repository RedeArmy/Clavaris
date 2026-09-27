package com.clavaris.identity.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.identity.application.usecase.listaccountsfororganization.WorkspaceRoleDisplay;
import com.clavaris.identity.application.usecase.listaccountsfororganization.WorkspaceRoleDisplayReader;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real-Postgres integration test for {@link JpaWorkspaceRoleDisplayReader} — proves it reads real
 * rows out of organization-module's own physical {@code workspace_memberships}/{@code
 * workspace_roles} tables (ADR-0029 §2's own data contract) via this module's independently-owned
 * read-side entities, never a mocked port. This module deliberately doesn't depend on
 * organization-module, so both tables are created here by hand, matching their real migrations'
 * final shape exactly — not by pulling in organization-module's own Flyway migrations, which would
 * reintroduce the very dependency ADR-0029 forbids (same reasoning {@code
 * JpaOutboxEventReaderTest}, webhook-module, already establishes for an identical situation).
 */
@SpringBootTest(classes = JpaWorkspaceRoleDisplayReaderTest.TestConfig.class)
@Testcontainers
@Transactional
class JpaWorkspaceRoleDisplayReaderTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private WorkspaceRoleDisplayReader reader;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void createProducerOwnedTables() {
    jdbcTemplate.execute(
        "create table if not exists workspace_roles ("
            + "id uuid primary key, organization_id uuid not null, name varchar(255) not null,"
            + " parent_role_id uuid, permissions text not null, reserved boolean not null default"
            + " false, created_at timestamptz not null default now())");
    jdbcTemplate.execute(
        "create table if not exists workspace_memberships ("
            + "id uuid primary key, workspace_id uuid not null, account_id uuid not null,"
            + " role_id uuid, created_at timestamptz not null default now())");
  }

  @Test
  void resolvesTheRoleNameForAnAccountsOwnMembership() {
    UUID accountId = UUID.randomUUID();
    UUID workspaceId = UUID.randomUUID();
    UUID roleId = insertRole("Admin");
    insertMembership(workspaceId, accountId, roleId);

    Map<UUID, WorkspaceRoleDisplay> found = reader.findByAccountIds(List.of(accountId));

    assertThat(found).containsKey(accountId);
    WorkspaceRoleDisplay display = found.get(accountId);
    assertThat(display.workspaceId()).isEqualTo(workspaceId);
    assertThat(display.roleId()).isEqualTo(roleId);
    assertThat(display.roleName()).isEqualTo("Admin");
  }

  @Test
  void resolvesANullRoleNameWhenTheMembershipHasNoRoleAssigned() {
    UUID accountId = UUID.randomUUID();
    insertMembership(UUID.randomUUID(), accountId, null);

    Map<UUID, WorkspaceRoleDisplay> found = reader.findByAccountIds(List.of(accountId));

    assertThat(found.get(accountId).roleId()).isNull();
    assertThat(found.get(accountId).roleName()).isNull();
  }

  @Test
  void omitsAnAccountWithNoWorkspaceMembershipAtAll() {
    UUID accountId = UUID.randomUUID();

    Map<UUID, WorkspaceRoleDisplay> found = reader.findByAccountIds(List.of(accountId));

    assertThat(found).doesNotContainKey(accountId);
  }

  private UUID insertRole(final String name) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "insert into workspace_roles (id, organization_id, name, permissions) values (?, ?, ?,"
            + " '[]')",
        id,
        UUID.randomUUID(),
        name);
    return id;
  }

  private void insertMembership(final UUID workspaceId, final UUID accountId, final UUID roleId) {
    jdbcTemplate.update(
        "insert into workspace_memberships (id, workspace_id, account_id, role_id) values (?, ?,"
            + " ?, ?)",
        UUID.randomUUID(),
        workspaceId,
        accountId,
        roleId);
  }

  @Configuration
  @EnableAutoConfiguration
  @EnableJpaRepositories(
      basePackageClasses = {
        SpringDataWorkspaceMembershipReadJpaRepository.class,
        SpringDataWorkspaceRoleReadJpaRepository.class
      })
  @Import(JpaWorkspaceRoleDisplayReader.class)
  static class TestConfig {}
}
