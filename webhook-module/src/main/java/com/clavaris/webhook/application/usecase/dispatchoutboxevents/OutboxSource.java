package com.clavaris.webhook.application.usecase.dispatchoutboxevents;

/**
 * Which physical table an {@link OutboxEvent} was claimed from — identity-module's own {@code
 * event_outbox} and organization-module's own {@code organization_event_outbox} are two genuinely
 * separate tables, each independently generating its own row {@code id}s (see either table's own
 * migration comment for why they can't be one shared table), so {@link
 * OutboxEventReader#markPublished} needs this to route the follow-up write back to the correct one.
 */
public enum OutboxSource {
  IDENTITY,
  ORGANIZATION
}
