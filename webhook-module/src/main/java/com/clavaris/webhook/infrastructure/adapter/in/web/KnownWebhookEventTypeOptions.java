package com.clavaris.webhook.infrastructure.adapter.in.web;

import java.util.List;

/**
 * Web-layer only — {@code WebhookEndpoint.subscribedEventTypes} carries no domain-level catalog
 * restriction (see {@code WebhookEndpoint#requireNonEmptyEventTypes}: any non-blank string is
 * accepted), same "free-form, caller-defined" posture {@code OAuthClient.allowedScopes} already
 * establishes for an identical reason — a consuming application's own event needs aren't something
 * this module can enumerate exhaustively in advance either. This is purely the checkbox vocabulary
 * the dashboard's own register form offers: every {@code event_outbox} row this codebase's own
 * producers (identity-module, organization-module) actually write today, confirmed by reading every
 * {@code EventOutboxWriter#write} call site — not an exhaustive or domain-enforced allowlist, and
 * genuinely incomplete the moment a new producer ships a new event type this list hasn't been
 * updated to include.
 */
public final class KnownWebhookEventTypeOptions {

  public static final List<String> DASHBOARD_OPTIONS =
      List.of(
          "account.created",
          "account.deleted",
          "account.email_verified",
          "account.reactivated",
          "account.suspended",
          "organization.deleted",
          "password_reset.completed",
          "password_reset.requested",
          "refresh_token.reuse_detected",
          "social_identity.linked",
          "workspace.created",
          "workspace_membership.added",
          "workspace_membership.removed",
          "workspace_membership.role_changed");

  private KnownWebhookEventTypeOptions() {
    // Constants only.
  }
}
