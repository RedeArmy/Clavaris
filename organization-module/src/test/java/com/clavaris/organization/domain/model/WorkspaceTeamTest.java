package com.clavaris.organization.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkspaceTeamTest {

  @Test
  void defineAssignsARandomIdAndCapturesTheGivenFields() {
    UUID workspaceId = UUID.randomUUID();

    WorkspaceTeam team = WorkspaceTeam.define(workspaceId, "QA");

    assertThat(team.id()).isNotNull();
    assertThat(team.workspaceId()).isEqualTo(workspaceId);
    assertThat(team.name()).isEqualTo("QA");
    assertThat(team.createdAt()).isNotNull();
  }

  @Test
  void rejectsABlankName() {
    UUID workspaceId = UUID.randomUUID();

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> WorkspaceTeam.define(workspaceId, "   "));
  }

  @Test
  void rejectsANameLongerThan255Characters() {
    UUID workspaceId = UUID.randomUUID();
    String tooLong = "a".repeat(256);

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> WorkspaceTeam.define(workspaceId, tooLong));
  }

  @Test
  void reconstitutePreservesTheRealPersistedFields() {
    UUID id = UUID.randomUUID();
    UUID workspaceId = UUID.randomUUID();
    Instant createdAt = Instant.now().minusSeconds(60);

    WorkspaceTeam team = WorkspaceTeam.reconstitute(id, workspaceId, "Support", createdAt);

    assertThat(team.id()).isEqualTo(id);
    assertThat(team.workspaceId()).isEqualTo(workspaceId);
    assertThat(team.name()).isEqualTo("Support");
    assertThat(team.createdAt()).isEqualTo(createdAt);
  }

  @Test
  void withNameKeepsEveryOtherFieldUnchanged() {
    WorkspaceTeam team = WorkspaceTeam.define(UUID.randomUUID(), "QA");

    WorkspaceTeam renamed = team.withName("Quality Assurance");

    assertThat(renamed.id()).isEqualTo(team.id());
    assertThat(renamed.workspaceId()).isEqualTo(team.workspaceId());
    assertThat(renamed.createdAt()).isEqualTo(team.createdAt());
    assertThat(renamed.name()).isEqualTo("Quality Assurance");
  }
}
