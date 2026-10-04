package com.clavaris.organization.infrastructure.adapter.in.web.auditlog;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.common.domain.model.AuditEvent;
import com.clavaris.common.domain.model.LongEnglishDateFormatter;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns the raw audit events into rows a person can read: an actor named for what they are to the
 * reader ("You", an operator, a user), the action as a sentence, the time as "3 hours ago" with the
 * exact moment on hover, and the detail as labelled pairs with names instead of ids. The raw values
 * are kept alongside for the collapsed "Technical details" disclosure.
 */
public final class AuditLogPresenter {

  private static final String WORKSPACE = "Workspace";
  private static final long MINUTE_SECONDS = 60;
  private static final long MINUTES_PER_HOUR = 60;
  private static final long HOURS_PER_DAY = 24;
  private static final long DAYS_AS_RELATIVE = 7;
  private static final long YESTERDAY_DAYS = 2;
  private static final DateTimeFormatter EXACT =
      DateTimeFormatter.ofPattern("MMMM d, uuuu 'at' HH:mm 'UTC'", Locale.ENGLISH)
          .withZone(ZoneOffset.UTC);

  /**
   * What the presenter needs to know about the moment and the reader.
   *
   * @param currentAccountId the signed-in platform account's id, so its own actions read "You"
   * @param names lookups for the workspaces and roles that still exist
   * @param now the instant relative times are measured from
   */
  public record Context(String currentAccountId, AuditDetailFormatter.Names names, Instant now) {}

  private AuditLogPresenter() {
    // Static helpers only.
  }

  public static AuditLogEntryView present(final AuditEvent event, final Context context) {
    final AuditActionCatalog.ActionInfo action = AuditActionCatalog.describe(event.action());
    final boolean you = isCurrentAccount(event.actor(), context.currentAccountId());
    final List<DetailItem> details =
        new ArrayList<>(AuditDetailFormatter.format(event.detail().orElse(null), context.names()));
    addNamedTarget(details, event, context.names());

    return new AuditLogEntryView(
        relative(event.occurredAt(), context.now()),
        EXACT.format(event.occurredAt()),
        event.occurredAt().toString(),
        actorLabel(event.actor(), you),
        you,
        action.label(),
        action.category(),
        action.tone(),
        details,
        event.actor().type() + ":" + event.actor().id(),
        event.action(),
        event.targetId().map(id -> event.targetType() + ":" + id).orElse(event.targetType()),
        event.detail().orElse(""));
  }

  // An event whose target is a workspace says which one by name, when it still exists.
  private static void addNamedTarget(
      final List<DetailItem> details,
      final AuditEvent event,
      final AuditDetailFormatter.Names names) {
    final boolean alreadyNamed = details.stream().anyMatch(item -> WORKSPACE.equals(item.label()));
    final String workspaceName =
        WORKSPACE.equals(event.targetType())
            ? event.targetId().map(names.workspaces()::get).orElse(null)
            : null;
    if (workspaceName != null && !alreadyNamed) {
      details.add(0, DetailItem.plain(WORKSPACE, workspaceName));
    }
  }

  private static boolean isCurrentAccount(final AuditActor actor, final String currentAccountId) {
    return actor.type() == AuditActor.AuditActorType.PLATFORM_ACCOUNT
        && actor.id().equals(currentAccountId);
  }

  private static String actorLabel(final AuditActor actor, final boolean you) {
    return switch (actor.type()) {
      case PLATFORM_ACCOUNT -> you ? "You" : "Another platform account";
      case PLATFORM_CLIENT -> "Operator (" + actor.id() + ")";
      case ACCOUNT -> "A user of this Organization";
    };
  }

  /**
   * "just now", "5 minutes ago", "3 hours ago", "Yesterday", "4 days ago", else the date.
   * Package-private for the unit test.
   */
  /* default */ static String relative(final Instant when, final Instant now) {
    final Duration age = Duration.between(when, now);
    final long minutes = age.toMinutes();
    final long hours = age.toHours();
    final long days = age.toDays();
    final String text;
    if (age.getSeconds() < MINUTE_SECONDS) {
      text = "Just now";
    } else if (minutes < MINUTES_PER_HOUR) {
      text = plural(minutes, "minute") + " ago";
    } else if (hours < HOURS_PER_DAY) {
      text = plural(hours, "hour") + " ago";
    } else if (days < YESTERDAY_DAYS) {
      text = "Yesterday";
    } else if (days < DAYS_AS_RELATIVE) {
      text = days + " days ago";
    } else {
      text = LongEnglishDateFormatter.format(when);
    }
    return text;
  }

  private static String plural(final long count, final String unit) {
    return count + " " + unit + (count == 1 ? "" : "s");
  }
}
