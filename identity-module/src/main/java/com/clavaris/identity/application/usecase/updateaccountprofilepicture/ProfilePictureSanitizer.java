package com.clavaris.identity.application.usecase.updateaccountprofilepicture;

import com.clavaris.common.domain.image.ImageMetadata;

/**
 * TD-SEC-096: what a profile picture goes through before it is stored. {@link
 * ProfilePictureValidator} judges it (type, bytes, size), then the metadata that identifies the
 * person (GPS position, device, time, comments) is removed from the file itself, keeping only the
 * orientation a phone photo needs to stand upright. A file whose structure cannot be cleaned is
 * refused rather than stored as it came. Extracted so the account and the platform-account upload
 * apply exactly the same steps.
 */
public final class ProfilePictureSanitizer {

  private ProfilePictureSanitizer() {
    // Static helper only — no instance state.
  }

  /** The bytes to store; throws {@link InvalidProfilePictureException} for a file that fails. */
  public static byte[] sanitise(final byte[] content, final String contentType) {
    ProfilePictureValidator.validate(content, contentType);
    return ImageMetadata.strip(contentType, content)
        .orElseThrow(
            () ->
                new InvalidProfilePictureException(
                    "The image could not be processed: its structure is not valid " + contentType));
  }
}
