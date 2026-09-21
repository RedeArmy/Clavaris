package com.clavaris.identity.application.usecase.updateaccountprofilepicture;

/**
 * The storage backend (Supabase, ADR-0026) rejected or could not complete an upload/download. Never
 * thrown by {@link ProfilePictureStorage#delete} — deleting an already-gone object is a no-op, same
 * "malformed/absent input surfaces as absent, never an exception" convention {@code
 * JpaAccountRepository#deleteById}'s own Javadoc already documents for this codebase.
 */
public final class ProfilePictureStorageException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public ProfilePictureStorageException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
