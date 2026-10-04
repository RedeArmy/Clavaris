package com.clavaris.organization.infrastructure.adapter.in.web.auditlog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.common.domain.model.AuditEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditLogPresenterTest {

  private static final UUID ME = UUID.randomUUID();
  private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");

  private static AuditLogPresenter.Context context(final AuditDetailFormatter.Names names) {
    return new AuditLogPresenter.Context(ME.toString(), names, NOW);
  }

  private static AuditEvent event(
      final AuditActor actor, final String action, final String targetType, final String detail) {
    return AuditEvent.reconstitute(
        UUID.randomUUID(),
        actor,
        action,
        targetType,
        "target-1",
        detail,
        NOW.minusSeconds(3 * 3600));
  }

  @Test
  void theSignedInAccountsOwnActionsReadYouAndTheRawActorStaysInTheTechnicalFields() {
    final AuditLogEntryView view =
        AuditLogPresenter.present(
            event(
                AuditActor.platformAccount(ME),
                "organization_client.deleted",
                "Organization",
                "deletedClientId=sk_test_example0-0000-0000-0000-000000000000"),
            context(AuditDetailFormatter.Names.none()));

    assertEquals("You", view.actorLabel());
    assertTrue(view.actorIsYou());
    assertEquals("Secret Key deleted", view.label());
    assertEquals(AuditCategory.SECRET_KEYS, view.category());
    assertEquals("Client", view.details().get(0).label());
    assertEquals("PLATFORM_ACCOUNT:" + ME, view.rawActor());
    assertEquals("organization_client.deleted", view.rawAction());
    assertEquals("Organization:target-1", view.rawTarget());
    assertTrue(view.rawDetail().startsWith("deletedClientId="));
  }

  @Test
  void otherActorsAreNamedByWhatTheyAreNotByTheirIds() {
    final AuditLogPresenter.Context context = context(AuditDetailFormatter.Names.none());

    final AuditLogEntryView another =
        AuditLogPresenter.present(
            event(
                AuditActor.platformAccount(UUID.randomUUID()),
                "workspace.created",
                "Workspace",
                null),
            context);
    final AuditLogEntryView operator =
        AuditLogPresenter.present(
            event(AuditActor.platformClient("localdev"), "workspace.created", "Workspace", null),
            context);
    final AuditLogEntryView user =
        AuditLogPresenter.present(
            event(
                AuditActor.account(UUID.randomUUID()), "account.session_revoked", "Session", null),
            context);

    assertEquals("Another platform account", another.actorLabel());
    assertFalse(another.actorIsYou());
    assertEquals("Operator (localdev)", operator.actorLabel());
    assertEquals("A user of this Organization", user.actorLabel());
  }

  @Test
  void aWorkspaceTargetIsNamedWhenItStillExists() {
    final AuditDetailFormatter.Names names =
        new AuditDetailFormatter.Names(Map.of("target-1", "Hiring"), Map.of());

    final AuditLogEntryView view =
        AuditLogPresenter.present(
            event(
                AuditActor.platformAccount(ME),
                "workspace.created",
                "Workspace",
                "organizationId=abc"),
            context(names));

    assertEquals(1, view.details().size());
    assertEquals("Workspace", view.details().get(0).label());
    assertEquals("Hiring", view.details().get(0).shown());
  }

  @Test
  void anUnlistedActionStillReadsAsASentence() {
    final AuditLogEntryView view =
        AuditLogPresenter.present(
            event(AuditActor.platformAccount(ME), "brand_new.thing_happened", "Thing", null),
            context(AuditDetailFormatter.Names.none()));

    assertEquals("Brand new thing happened", view.label());
    assertEquals(AuditCategory.OTHER, view.category());
    assertEquals("Thing:target-1", view.rawTarget());
  }

  @Test
  void relativeTimeReadsLikeAPersonWouldSayIt() {
    assertEquals("Just now", AuditLogPresenter.relative(NOW.minusSeconds(20), NOW));
    assertEquals("1 minute ago", AuditLogPresenter.relative(NOW.minusSeconds(65), NOW));
    assertEquals(
        "12 minutes ago", AuditLogPresenter.relative(NOW.minus(Duration.ofMinutes(12)), NOW));
    assertEquals("1 hour ago", AuditLogPresenter.relative(NOW.minus(Duration.ofMinutes(61)), NOW));
    assertEquals("3 hours ago", AuditLogPresenter.relative(NOW.minus(Duration.ofHours(3)), NOW));
    assertEquals("Yesterday", AuditLogPresenter.relative(NOW.minus(Duration.ofHours(30)), NOW));
    assertEquals("4 days ago", AuditLogPresenter.relative(NOW.minus(Duration.ofDays(4)), NOW));
    assertEquals(
        "September 20th, 2026", AuditLogPresenter.relative(NOW.minus(Duration.ofDays(13)), NOW));
  }

  @Test
  void theExactMomentIsAvailableForTheTooltip() {
    final AuditLogEntryView view =
        AuditLogPresenter.present(
            event(AuditActor.platformAccount(ME), "workspace.created", "Workspace", null),
            context(AuditDetailFormatter.Names.none()));

    assertEquals("3 hours ago", view.whenRelative());
    assertEquals("October 3, 2026 at 09:00 UTC", view.whenFull());
    assertEquals("2026-10-03T09:00:00Z", view.whenIso());
  }
}
