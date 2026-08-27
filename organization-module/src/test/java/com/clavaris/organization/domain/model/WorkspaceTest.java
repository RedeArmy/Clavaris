package com.clavaris.organization.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkspaceTest {

  @Test
  void registerAssignsARandomIdAndCapturesTheGivenFields() {
    UUID organizationId = UUID.randomUUID();

    Workspace workspace = Workspace.register(organizationId, "Engineering");

    assertThat(workspace.id()).isNotNull();
    assertThat(workspace.organizationId()).isEqualTo(organizationId);
    assertThat(workspace.name()).isEqualTo("Engineering");
    assertThat(workspace.createdAt()).isNotNull();
  }

  @Test
  void rejectsABlankName() {
    UUID organizationId = UUID.randomUUID();

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> Workspace.register(organizationId, "   "));
  }

  @Test
  void rejectsANameLongerThan255Characters() {
    UUID organizationId = UUID.randomUUID();
    String tooLong = "a".repeat(256);

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> Workspace.register(organizationId, tooLong));
  }

  @Test
  void reconstitutePreservesTheRealPersistedFields() {
    UUID id = UUID.randomUUID();
    UUID organizationId = UUID.randomUUID();
    Instant createdAt = Instant.now().minusSeconds(60);

    Workspace workspace = Workspace.reconstitute(id, organizationId, "Sales", createdAt);

    assertThat(workspace.id()).isEqualTo(id);
    assertThat(workspace.organizationId()).isEqualTo(organizationId);
    assertThat(workspace.name()).isEqualTo("Sales");
    assertThat(workspace.createdAt()).isEqualTo(createdAt);
  }
}
