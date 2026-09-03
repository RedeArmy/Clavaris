package com.clavaris.identity.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Shared state and lifecycle for {@link SigningKey}/{@link PlatformSigningKey} — TD-ARCH-009's own
 * third extraction (named 2026-08-31): {@link #retire()} and every field except {@code
 * organizationId} are provably, permanently identical between the two.
 *
 * <p>Deliberately <b>not</b> generic over an owning-id type, unlike {@link
 * AbstractPendingSocialLink}/{@link AbstractVerificationToken}/{@link AbstractPasswordCredential} —
 * {@link PlatformSigningKey} has no owning id at all (ADR-0010, Organization provisioning: the
 * platform tier's own signing key belongs to no `Organization`, structurally, not merely an unset
 * field), so there is nothing here for a type parameter to express. {@link SigningKey} adds its own
 * {@code organizationId} field directly, the same way {@link PendingSocialLinkEntity}'s persistence
 * counterpart adds its own owning-id column on top of a base with none — see that class's own
 * Javadoc for the identical shape at the persistence layer, and {@link AbstractSigningKeyEntity}
 * for this pair's own persistence-layer mirror of this exact split.
 *
 * <p>Package-private: only this package's own two subclasses ever need to see it. Same
 * record-style-accessor and structural-metric PMD suppressions as its siblings.
 *
 * <p><b>Live-caught footgun, not hypothetical:</b> a method reference like {@code SigningKey::kid}
 * written from outside {@code domain.model} (e.g. a persistence-layer test) throws {@code
 * LambdaConversionException: MethodHandle(SigningKey)String is not direct or cannot be cracked} at
 * runtime — {@code kid()} is only actually declared here, on this package-private class, and the
 * JDK's lambda-metafactory is stricter about that than an ordinary {@code invokevirtual} call would
 * be, even though the method itself is {@code public} and reachable through the public {@link
 * SigningKey} subclass. An explicit lambda ({@code key -> key.kid()}) compiles to plain
 * virtual-dispatch bytecode instead and doesn't hit this path — use that form, not a method
 * reference, when calling one of this class's inherited accessors from another package.
 */
@SuppressWarnings({
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.ShortVariable",
  "PMD.ShortMethodName",
  "PMD.AbstractClassWithoutAbstractMethod",
  "PMD.PublicMemberInNonPublicType",
  "PMD.DataClass"
})
abstract class AbstractSigningKey {

  private final UUID id;
  private final String kid;
  private final String algorithm;
  private final Instant activeFrom;
  private Instant retiredAt;

  protected AbstractSigningKey(
      final UUID id,
      final String kid,
      final String algorithm,
      final Instant activeFrom,
      final Instant retiredAt) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.kid = Objects.requireNonNull(kid, "kid must not be null");
    this.algorithm = Objects.requireNonNull(algorithm, "algorithm must not be null");
    this.activeFrom = Objects.requireNonNull(activeFrom, "activeFrom must not be null");
    this.retiredAt = retiredAt;
  }

  /**
   * JWKS always exposes the previous key until every token signed under it has expired — retiring
   * stops it signing new tokens, doesn't remove the row.
   */
  public final void retire() {
    this.retiredAt = Instant.now();
  }

  /**
   * TD-SEC-029: emergency, zero-overlap purge for a <b>confirmed</b> compromise — unlike {@link
   * #retire()} (which lets JWKS keep publishing this key for the normal overlap window), this
   * backdates {@code retiredAt} far enough into the past that {@code
   * SigningKeyRepository#findActiveAndRetiredSince}'s own overlap-window filter excludes it on the
   * very next JWKS read, regardless of the configured overlap duration ({@code
   * clavaris.signing-key.jwks-overlap-hours}). No new mechanism on the JWKS-serving side was needed
   * — that filter already excludes any key retired before its own cutoff; this method only chooses
   * a cutoff-proof timestamp. The accepted, deliberate cost: any legitimate, still-valid token
   * signed under this key stops verifying immediately too — the trade this operation exists to make
   * when the alternative (an attacker's own forged tokens continuing to verify for the rest of the
   * overlap window) is worse. See {@code incident-response-signing-key-compromise.md} §3.6 for when
   * to reach for this instead of plain {@link #retire()}.
   */
  public final void purgeImmediately() {
    this.retiredAt = Instant.EPOCH;
  }

  public final UUID id() {
    return id;
  }

  public final String kid() {
    return kid;
  }

  public final String algorithm() {
    return algorithm;
  }

  public final Instant activeFrom() {
    return activeFrom;
  }

  public final Optional<Instant> retiredAt() {
    return Optional.ofNullable(retiredAt);
  }
}
