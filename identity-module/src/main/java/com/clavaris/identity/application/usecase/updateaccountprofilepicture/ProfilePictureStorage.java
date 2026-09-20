package com.clavaris.identity.application.usecase.updateaccountprofilepicture;

/**
 * ADR-0026: outbound port to the object-storage backend holding uploaded profile pictures —
 * implemented by {@code SupabaseS3ProfilePictureStorage} (infrastructure). Deliberately
 * storage-agnostic at this layer: no S3/Supabase concept (bucket, endpoint, credentials) appears
 * here, only "store these bytes under this key, get the same bytes back later" — the hexagonal
 * dependency rule (CLAUDE.md §7.2). {@code GetAccountAvatarService}/{@code
 * GetPlatformAccountAvatarService} (the read side) are this port's other callers — see either one's
 * own Javadoc for why the key never doubles as a directly fetchable URL.
 *
 * <p>{@code ownerKey} is a caller-computed, stable, unique-per-owner string (e.g. {@code "avatars/"
 * + accountId} for a tenant {@code Account}, {@code "platform-avatars/" + platformAccountId} for a
 * {@code PlatformAccount}) — deliberately a plain {@code String}, not a typed id, so this one
 * port/adapter pair serves every kind of account this codebase has, present or future, without a
 * near-duplicate port per aggregate type. Key construction is the caller's own responsibility
 * precisely because this layer has no business knowing what a caller's id type even is.
 */
public interface ProfilePictureStorage {

  /**
   * Uploads {@code content} under {@code ownerKey}, replacing any picture previously stored under
   * that same key. Returns {@code ownerKey} back, unchanged — kept as a return value (not void) so
   * every caller can treat this the same way regardless of whether the key happens to be
   * caller-supplied (it always is, today) purely for call-site symmetry with {@link #download}/
   * {@link #delete}, which both accept exactly what this method returns.
   */
  String upload(String ownerKey, byte[] content, String contentType);

  /** Retrieves a previously-uploaded picture's raw bytes and content type. */
  StoredProfilePicture download(String storageKey);

  /** Deletes a previously-uploaded picture — a no-op if the key no longer exists. */
  void delete(String storageKey);
}
