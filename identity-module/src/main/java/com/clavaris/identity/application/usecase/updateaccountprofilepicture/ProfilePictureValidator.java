package com.clavaris.identity.application.usecase.updateaccountprofilepicture;

import com.clavaris.common.domain.image.ImageDimensions;
import com.clavaris.common.domain.image.ImageSignature;
import java.util.Set;

/**
 * ADR-0026: the upload validation {@link UpdateAccountProfilePictureService} and {@code
 * updateplatformaccountprofilepicture.UpdatePlatformAccountProfilePictureService} both need,
 * identically — extracted here once both needed it (CPD), same "one algorithm, two call sites"
 * precedent {@code InitialsAvatarGenerator} already establishes for the read side.
 */
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

  // TD-SEC-096: what a header may declare. 10,000 px a side and 80 megapixels cover any phone
  // camera
  // (a 48 MP photo is about 8,000 x 6,000) and refuse the small file that declares billions of
  // pixels for whoever draws it to allocate.
  public static final long MAX_SIDE_PIXELS = 10_000L;
  public static final long MAX_PIXELS = 80_000_000L;

  private ProfilePictureValidator() {
    // Static helper only — no instance state.
  }

  public static void validate(final byte[] content, final String contentType) {
    if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
      throw new InvalidProfilePictureException("Unsupported image type: " + contentType);
    }
    requireUsableSize(content);
    // The declared type comes from the client; the bytes must agree with it (TD-SEC-096), the same
    // standard an Organization's logo already holds.
    if (!ImageSignature.matches(contentType, content)) {
      throw new InvalidProfilePictureException("The file is not a valid " + contentType + " image");
    }
    requireDeclaredSizeWithinLimits(content, contentType);
  }

  private static void requireUsableSize(final byte[] content) {
    if (content.length == 0) {
      throw new InvalidProfilePictureException("The uploaded file is empty");
    }
    if (content.length > MAX_BYTES) {
      throw new InvalidProfilePictureException("The uploaded file exceeds the 10MB limit");
    }
  }

  private static void requireDeclaredSizeWithinLimits(
      final byte[] content, final String contentType) {
    final ImageDimensions.Size size =
        ImageDimensions.read(contentType, content)
            .orElseThrow(
                () -> new InvalidProfilePictureException("The image's size could not be read"));
    if (size.width() > MAX_SIDE_PIXELS
        || size.height() > MAX_SIDE_PIXELS
        || size.pixels() > MAX_PIXELS) {
      throw new InvalidProfilePictureException(
          "The image is larger than " + MAX_SIDE_PIXELS + " pixels a side");
    }
  }
}
