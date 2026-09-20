package com.clavaris.identity.application.usecase.updateaccountprofilepicture;

import java.util.Set;

/**
 * ADR-0026: the upload validation {@link UpdateAccountProfilePictureService} and {@code
 * updateplatformaccountprofilepicture.UpdatePlatformAccountProfilePictureService} both need,
 * identically — extracted here once both needed it (CPD), same "one algorithm, two call sites"
 * precedent {@code InitialsAvatarGenerator} already establishes for the read side.
 */
// PMD.LongVariable: ALLOWED_CONTENT_TYPES names exactly what it is — a shortened identifier would
// only make this class harder to read, same convention every other descriptively-named constant
// in this codebase follows.
@SuppressWarnings("PMD.LongVariable")
public final class ProfilePictureValidator {

  // The pasted "Update profile... up to 10MB" UI copy's own stated cap, verbatim.
  public static final long MAX_BYTES = 10L * 1024 * 1024;

  // A conservative, real-world image-format allow-list — deliberately not "anything image/*":
  // image/svg+xml is excluded on purpose (an SVG can embed script, a real XSS vector for
  // anything that renders it inline rather than via <img>, which every consumer of this
  // codebase's own avatar endpoints does — the generated-initials fallback's own SVG is a
  // Clavaris-authored exception to this rule, never user-uploaded content).
  private static final Set<String> ALLOWED_CONTENT_TYPES =
      Set.of("image/jpeg", "image/png", "image/webp", "image/gif");

  private ProfilePictureValidator() {
    // Static helper only — no instance state.
  }

  public static void validate(final byte[] content, final String contentType) {
    if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
      throw new InvalidProfilePictureException("Unsupported image type: " + contentType);
    }
    if (content.length == 0) {
      throw new InvalidProfilePictureException("The uploaded file is empty");
    }
    if (content.length > MAX_BYTES) {
      throw new InvalidProfilePictureException("The uploaded file exceeds the 10MB limit");
    }
  }
}
