package com.clavaris.common.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditEventTest {

  @Test
  void recordCarriesEveryGivenFieldAndStampsAFreshId() {
    AuditActor actor = AuditActor.platformAccount(UUID.randomUUID());

    AuditEvent event =
        AuditEvent.of(actor, "organization.created", "Organization", "org-1", "name=Acme");

    assertThat(event.id()).isNotNull();
    assertThat(event.actor()).isEqualTo(actor);
    assertThat(event.action()).isEqualTo("organization.created");
    assertThat(event.targetType()).isEqualTo("Organization");
    assertThat(event.targetId()).contains("org-1");
    assertThat(event.detail()).contains("name=Acme");
    assertThat(event.occurredAt()).isNotNull();
  }

  @Test
  void targetIdAndDetailAreBothOptionalAndComeBackEmptyWhenOmitted() {
    AuditEvent event =
        AuditEvent.of(
            AuditActor.platformClient("bootstrap-client"),
            "organization.created",
            "Organization",
            null,
            null);

    assertThat(event.targetId()).isEmpty();
    assertThat(event.detail()).isEmpty();
  }

  @Test
  void rejectsABlankAction() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> AuditEvent.of(AuditActor.platformClient("c"), " ", "Organization", null, null));
  }

  @Test
  void rejectsABlankTargetType() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                AuditEvent.of(
                    AuditActor.platformClient("c"), "organization.created", "", null, null));
  }

  @Test
  void rejectsANullActor() {
    assertThatNullPointerException()
        .isThrownBy(() -> AuditEvent.of(null, "organization.created", "Organization", null, null));
  }

  @Test
  void reconstituteKeepsTheRealPersistedIdRatherThanMintingANewOne() {
    // Same bug class already caught once in Organization's/RateLimitPolicy's own history —
    // reconstitute must return the exact id passed in, not a fresh UUID.randomUUID().
    UUID persistedId = UUID.randomUUID();
    java.time.Instant persistedAt = java.time.Instant.parse("2026-01-01T00:00:00Z");
    AuditActor actor = AuditActor.platformAccount(UUID.randomUUID());

    AuditEvent event =
        AuditEvent.reconstitute(
            persistedId,
            actor,
            "signing_key.rotated",
            "SigningKey",
            "kid-1",
            "overlap=true",
            persistedAt);

    assertThat(event.id()).isEqualTo(persistedId);
    assertThat(event.occurredAt()).isEqualTo(persistedAt);
  }

  @Test
  void platformAccountActorRendersItsUuidAsTheId() {
    UUID platformAccountId = UUID.randomUUID();

    AuditActor actor = AuditActor.platformAccount(platformAccountId);

    assertThat(actor.type()).isEqualTo(AuditActor.AuditActorType.PLATFORM_ACCOUNT);
    assertThat(actor.id()).isEqualTo(platformAccountId.toString());
  }

  @Test
  void platformClientActorKeepsItsClientIdVerbatim() {
    AuditActor actor = AuditActor.platformClient("jobseeker-admin-tool");

    assertThat(actor.type()).isEqualTo(AuditActor.AuditActorType.PLATFORM_CLIENT);
    assertThat(actor.id()).isEqualTo("jobseeker-admin-tool");
  }

  @Test
  void rejectsABlankActorId() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new AuditActor(AuditActor.AuditActorType.PLATFORM_CLIENT, " "));
  }
}
