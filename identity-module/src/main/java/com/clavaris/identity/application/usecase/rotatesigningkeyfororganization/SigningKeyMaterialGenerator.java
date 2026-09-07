package com.clavaris.identity.application.usecase.rotatesigningkeyfororganization;

import com.clavaris.identity.domain.model.OrganizationId;

/**
 * Outbound port — generates fresh RSA key material for {@code organizationId}, without touching any
 * metadata row (that's {@code ActivateSigningKeyForOrganizationUseCase}'s own job, called
 * separately). Deliberately doesn't reference {@code
 * infrastructure.adapter.out.security.OrganizationSigningKeyMaterialFactory} directly from this
 * application-layer service — this project's own hexagonal dependency rule applies within one
 * module too, not only across module boundaries. {@code OrganizationSigningKeyMaterialFactory}
 * implements this port directly; its own {@code generateFor(OrganizationId)} method already has
 * this exact shape, so no separate bridge class is needed the way {@code
 * CreateOrganizationSigningKeyBridge} was for the cross-module case.
 *
 * <p><b>TD-SEC-051 (2026-09-06): two methods, not one, and every caller must call both, in this
 * order.</b> This used to be a {@code @FunctionalInterface} — {@link #generateFor} alone both
 * generated the key material <em>and</em> cached it as the Organization's own active key. That
 * combined step raced against {@code ActivateSigningKeyForOrganizationService}'s own Postgres
 * advisory lock (added 2026-09-03 to serialize concurrent rotations at the DB level): two
 * concurrent rotations for the same Organization could each call {@link #generateFor} — an
 * unlocked, unordered write to the in-memory cache — before either one acquired the lock that
 * actually decides which {@code kid} wins in the database. The lock correctly serialized the DB row
 * transitions; it did nothing for the cache, so the last {@link #generateFor} call to run (in real
 * wall-clock time) could cache a {@code KeyPair} for a {@code kid} that was NOT the one the
 * lock-serialized DB transition ultimately activated — every subsequent token for that Organization
 * would then be signed with the wrong private key under the right {@code kid} label, and signature
 * verification would fail for every relying party of that tenant. See {@code
 * OrganizationSigningKeyMaterialFactory#cacheActive}'s own Javadoc for the fix and the exact
 * ordering every caller must now follow.
 */
public interface SigningKeyMaterialGenerator {

  /**
   * Generates a fresh RSA key pair for {@code organizationId} and persists it to the key store
   * under a new, random {@code kid} — deliberately does NOT cache it as this Organization's own
   * active key. Safe to call without holding any lock: each call mints its own fresh {@code kid},
   * so there is no shared mutable state two concurrent calls could conflict over here (the key
   * store's own writes are independently synchronized, see {@code SigningKeyStore}'s own Javadoc).
   *
   * @return the newly generated key's {@code kid}
   */
  String generateFor(OrganizationId organizationId);

  /**
   * TD-SEC-051: records {@code kid} as {@code organizationId}'s own currently active key in the
   * in-memory cache {@link #generateFor} deliberately does not touch. Every real call site must
   * call this only <b>after</b> {@code ActivateSigningKeyForOrganizationUseCase#handle} has already
   * committed {@code kid} as active in the database — and, since that use case joins the caller's
   * own surrounding {@code @Transactional} method (default {@code REQUIRED} propagation, the same
   * behavior its own Postgres advisory lock already relies on to span both classes), the lock
   * acquired inside it is still held for the remainder of that transaction. Calling this method
   * while that lock is still held — as the very last statement before the transaction returns and
   * commits, not immediately after {@code activate.handle(...)}, to minimize the (unavoidable,
   * inherent to any in-memory-plus-DB-transaction pairing) window where a later statement in the
   * same method could still roll the transaction back after the cache has already been updated — is
   * what makes the cache write happen in the same real order as the DB row transition each
   * concurrent caller's own lock acquisition already serializes, closing the race {@link
   * #generateFor}'s own unlocked write used to leave open.
   *
   * @throws IllegalStateException if {@code kid} has no matching entry in the key store — every
   *     real caller only ever passes a {@code kid} this same class's own {@link #generateFor} just
   *     minted, so this signals a real data-integrity violation, not an expected outcome.
   */
  void cacheActive(OrganizationId organizationId, String kid);
}
