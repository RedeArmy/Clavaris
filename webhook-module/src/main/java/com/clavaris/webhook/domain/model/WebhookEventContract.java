package com.clavaris.webhook.domain.model;

/**
 * TD-ARCH-064 (ADR-0052): the version of the payload a webhook delivery carries, so a receiver can
 * tell which shape it was sent and a change to a payload is never a surprise.
 *
 * <p>The version is the {@code Clavaris-Event-Version} header, not a field of the body: the stored
 * payload is the exact byte sequence that is signed and that a replay sends again, and changing it
 * on the way out would make a replay differ from the original it replays.
 *
 * <p>The rule, which the contract test in {@code app} enforces by comparing every event record's
 * JSON with a checked-in sample:
 *
 * <ul>
 *   <li>Adding a field is not a new version. Receivers must ignore fields they do not know. The
 *       sample file is updated in the same change.
 *   <li>Removing a field, renaming one, changing its type or its meaning is a new version: raise
 *       {@link #CURRENT_VERSION}, keep delivering the old shape for the stated deprecation period
 *       (see {@code docs/03-architecture/api-contracts/webhook-contract.md}) and say so in the
 *       release notes.
 * </ul>
 */
public final class WebhookEventContract {

  /** The header that carries {@link #CURRENT_VERSION} on every delivery. */
  public static final String VERSION_HEADER = "Clavaris-Event-Version";

  /** The version of every payload shape delivered today. */
  public static final int CURRENT_VERSION = 1;

  private WebhookEventContract() {}
}
