package com.clavaris.identity.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A device this {@link Account} has successfully logged in from before — identified by an opaque,
 * high-entropy device token (see {@code deviceTokenHash}), not the raw {@code User-Agent} header
 * this class used to be keyed by. Outlives any single {@code HttpSession} on purpose — that store
 * expires every 30 minutes and gets a brand-new id on every login even from the same physical
 * browser, so it can't answer "have we seen this device before" the way this aggregate does.
 *
 * <p><b>TD-SEC-033 (SDE-III review, 2026-08-31):</b> the original {@code user_agent}-only
 * fingerprint was trivially spoofable — an attacker who stole a live session (or was probing
 * credential-stuffing hits) could send the victim's own real {@code User-Agent} string, which is
 * public/guessable, to suppress the "new device" notification outright. {@code deviceTokenHash} is
 * the hash of an opaque, cryptographically random value this system itself mints and hands the
 * browser as an {@code HttpOnly} cookie ({@code DeviceCookie}) — an attacker's own browser can
 * never present a value that hashes to a row it never received, regardless of what {@code
 * User-Agent} it sends. {@code userAgent} is kept for display/audit purposes only now, no longer
 * the match key — see {@link #recognize} and this module's own {@code KnownDeviceRepository} for
 * the full reasoning. Same hash-not-plaintext principle as {@link RefreshToken}/{@code
 * VerificationToken} — the raw token value is never persisted, only its hash.
 *
 * <p><b>Code review finding (2026-09-01), deliberately NOT fixed — read before "fixing" this:</b>
 * two concurrent logins with no presented cookie (e.g. two browser tabs racing) each mint their own
 * independent random token and both insert successfully, producing two rows and two notifications
 * for what's really one physical device. This is a direct, necessary consequence of TD-SEC-033
 * above, not an oversight: any attempt to correlate or merge concurrent inserts for the same
 * Account (by timing, request metadata, etc.) would reopen the exact hole TD-SEC-033 closed — an
 * attacker timing a stolen-session login to coincide with the real owner's own genuine login could
 * get their own device silently merged into "the same" notification, suppressing it. A rare,
 * low-severity double email is the correct, accepted cost of an anti-spoofing property that must
 * never depend on timing.
 *
 * <p><b>Client-side mitigation added (2026-09-01):</b> {@code identity/login.html}'s own {@code
 * login-submit-guard.js} now coordinates the common real-world instance of this race — the same
 * browser's own two tabs both submitting the login form — via a {@code localStorage} mutex that
 * runs entirely inside the browser, before any request is sent. This is safe where a server-side
 * fix isn't: an attacker's browser is a different origin's storage and structurally cannot read or
 * write this one's lock, so it cannot be timed or spoofed the way any server-side correlation could
 * be. It narrows the practical frequency of this race without touching the server-side invariant
 * above at all — two genuinely different devices still notify independently, correctly.
 *
 * <p>PMD's AvoidFieldNameMatchingMethodName/ShortVariable/ShortMethodName rules flag this class for
 * the same reason {@code Session}/{@code Account} suppress them — the deliberate record-style
 * accessor convention used throughout this codebase's value objects.
 */
@SuppressWarnings({
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.ShortVariable",
  "PMD.ShortMethodName"
})
public final class KnownDevice {

  private final UUID id;
  private final AccountId accountId;
  private final String userAgent;
  private final String deviceTokenHash;
  private final Instant firstSeenAt;
  private Instant lastSeenAt;

  private KnownDevice(
      final UUID id,
      final AccountId accountId,
      final String userAgent,
      final String deviceTokenHash,
      final Instant firstSeenAt,
      final Instant lastSeenAt) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.accountId = Objects.requireNonNull(accountId, "accountId must not be null");
    this.userAgent = Objects.requireNonNull(userAgent, "userAgent must not be null");
    this.deviceTokenHash =
        Objects.requireNonNull(deviceTokenHash, "deviceTokenHash must not be null");
    this.firstSeenAt = Objects.requireNonNull(firstSeenAt, "firstSeenAt must not be null");
    this.lastSeenAt = Objects.requireNonNull(lastSeenAt, "lastSeenAt must not be null");
  }

  /**
   * First-ever login this device token was minted for — the moment worth notifying about.
   *
   * @param deviceTokenHash already hashed (same split as {@code VerificationToken.issue}'s own
   *     {@code tokenHash} parameter) — hashing happens in the application layer ({@code
   *     RecordAccountLoginDeviceService}, via {@code RefreshTokenSecret}), never here.
   */
  public static KnownDevice recognize(
      final AccountId accountId, final String userAgent, final String deviceTokenHash) {
    final Instant now = Instant.now();
    return new KnownDevice(UUID.randomUUID(), accountId, userAgent, deviceTokenHash, now, now);
  }

  public static KnownDevice reconstitute(
      final UUID id,
      final AccountId accountId,
      final String userAgent,
      final String deviceTokenHash,
      final Instant firstSeenAt,
      final Instant lastSeenAt) {
    return new KnownDevice(id, accountId, userAgent, deviceTokenHash, firstSeenAt, lastSeenAt);
  }

  /** Called on every subsequent login from an already-known device — no notification, just this. */
  public void touch() {
    this.lastSeenAt = Instant.now();
  }

  public UUID id() {
    return id;
  }

  public AccountId accountId() {
    return accountId;
  }

  public String userAgent() {
    return userAgent;
  }

  public String deviceTokenHash() {
    return deviceTokenHash;
  }

  public Instant firstSeenAt() {
    return firstSeenAt;
  }

  public Instant lastSeenAt() {
    return lastSeenAt;
  }
}
