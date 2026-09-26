package com.clavaris.webhook.infrastructure.adapter.in.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Web-layer only — {@code WebhookEndpoint.subscribedEventTypes} carries no domain-level catalog
 * restriction (see {@code WebhookEndpoint#requireNonEmptyEventTypes}: any non-blank string is
 * accepted), same "free-form, caller-defined" posture {@code OAuthClient.allowedScopes} already
 * establishes for an identical reason — a consuming application's own event needs aren't something
 * this module can enumerate exhaustively in advance either. This is purely the checkbox vocabulary
 * the dashboard's own register/edit forms offer: every {@code event_outbox} row this codebase's own
 * producers (identity-module, organization-module) actually write today, confirmed by reading every
 * {@code EventOutboxWriter#write} call site — not an exhaustive or domain-enforced allowlist, and
 * genuinely incomplete the moment a new producer ships a new event type this list hasn't been
 * updated to include.
 *
 * <p>Live UX request, 2026-09-25 (Clerk-parity Event Catalog): {@link EventTypeOption} adds a
 * {@code category()} (derived from {@code value}'s own leading {@code x.y} segment, not a second,
 * independently-typed literal — every value here already follows that convention, so a separate
 * field could only ever drift out of sync with it) and a one-line human {@code description} — the
 * dashboard's own event picker groups by category and shows the description as a tooltip, same
 * shape Clerk's own "Event Catalog" tab presents. {@link #DASHBOARD_OPTIONS} is kept, unchanged,
 * for any caller that only ever needed the bare value list.
 */
public final class KnownWebhookEventTypeOptions {

  public static final List<EventTypeOption> CATALOG =
      List.of(
          new EventTypeOption("account.created", "A new Account was registered."),
          new EventTypeOption("account.deleted", "An Account was permanently deleted."),
          new EventTypeOption("account.email_verified", "An Account's email address was verified."),
          new EventTypeOption("account.reactivated", "A suspended Account was reactivated."),
          new EventTypeOption("account.suspended", "An Account was suspended."),
          new EventTypeOption("organization.deleted", "An Organization was permanently deleted."),
          new EventTypeOption(
              "password_reset.completed", "An Account's password was successfully reset."),
          new EventTypeOption("password_reset.requested", "A password reset was requested."),
          new EventTypeOption(
              "refresh_token.reuse_detected",
              "A rotated refresh token was reused — every active token for the Account was"
                  + " revoked."),
          new EventTypeOption(
              "social_identity.linked",
              "A social identity (Google, GitHub) was linked to an Account."),
          new EventTypeOption("workspace.created", "A new Workspace was created."),
          new EventTypeOption("workspace_membership.added", "A member joined a Workspace."),
          new EventTypeOption(
              "workspace_membership.removed", "A member left or was removed from a Workspace."),
          new EventTypeOption(
              "workspace_membership.role_changed", "A member's Workspace role changed."));

  public static final List<String> DASHBOARD_OPTIONS =
      CATALOG.stream().map(EventTypeOption::value).toList();

  private KnownWebhookEventTypeOptions() {
    // Constants only.
  }

  /**
   * Live UX request, 2026-09-25 (Event Catalog tab, collapsible-per-category polish): {@code
   * CATALOG} itself stays a flat, ordered list (every other caller — the dashboard's own event
   * picker, {@link #DASHBOARD_OPTIONS} — genuinely needs it flat); this is purely a display-layer
   * grouping for {@code webhook-event-catalog.html}'s own one {@code <details>} disclosure per
   * category. {@code LinkedHashMap} as the {@code groupingBy} supplier preserves {@code CATALOG}'s
   * own category order (already contiguous by construction) rather than an unrelated hash order.
   */
  public static List<CategoryGroup> groupedByCategory() {
    return CATALOG.stream()
        .collect(
            Collectors.groupingBy(
                EventTypeOption::category, LinkedHashMap::new, Collectors.toList()))
        .entrySet()
        .stream()
        .map(entry -> new CategoryGroup(entry.getKey(), entry.getValue()))
        .toList();
  }

  public record EventTypeOption(String value, String description) {

    public String category() {
      return value.substring(0, value.indexOf('.'));
    }
  }

  public record CategoryGroup(String category, List<EventTypeOption> events) {}
}
