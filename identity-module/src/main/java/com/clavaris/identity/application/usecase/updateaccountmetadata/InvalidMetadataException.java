package com.clavaris.identity.application.usecase.updateaccountmetadata;

/**
 * Thrown by {@link UpdateAccountMetadataService} when a submitted tier is syntactically invalid
 * JSON, or exceeds {@link UpdateAccountMetadataService#MAX_METADATA_LENGTH}. Carries which tier
 * failed ({@code "publicMetadata"}, {@code "privateMetadata"}, or {@code "unsafeMetadata"}) so the
 * web layer can report a specific, actionable 400, not a generic one.
 */
public final class InvalidMetadataException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String invalidTier;

  public InvalidMetadataException(final String tier, final String reason) {
    super(tier + ": " + reason);
    this.invalidTier = tier;
  }

  /**
   * Same message, plus the low-level parse exception that revealed the tier wasn't valid JSON —
   * preserves its stack trace instead of discarding it, same "keep the real cause" convention
   * {@code EmailAlreadyRegisteredException}'s own two-constructor shape already establishes.
   */
  public InvalidMetadataException(final String tier, final String reason, final Throwable cause) {
    super(tier + ": " + reason, cause);
    this.invalidTier = tier;
  }

  public String tier() {
    return invalidTier;
  }
}
