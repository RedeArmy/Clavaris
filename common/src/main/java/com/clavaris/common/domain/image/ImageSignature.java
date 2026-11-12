package com.clavaris.common.domain.image;

import java.util.Arrays;
import java.util.Map;

/**
 * TD-SEC-096: whether the first bytes of an uploaded file are those of the image type it claims.
 * The declared {@code Content-Type} comes from the client and proves nothing, so every upload that
 * accepts an image (an Organization's logo, a person's profile picture) holds the same check
 * instead of each keeping its own copy. Only the container signature is looked at; it does not
 * decode anything.
 */
public final class ImageSignature {

  public static final String PNG = "image/png";
  public static final String JPEG = "image/jpeg";
  public static final String GIF = "image/gif";
  public static final String WEBP = "image/webp";

  private static final Map<String, byte[]> PREFIXES =
      Map.of(
          PNG, new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'},
          JPEG, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF},
          GIF, new byte[] {'G', 'I', 'F', '8'});

  private static final byte[] RIFF = {'R', 'I', 'F', 'F'};
  private static final byte[] WEBP_TAG = {'W', 'E', 'B', 'P'};

  private ImageSignature() {
    // Static helper only — no instance state.
  }

  /** The content types this class can check. */
  public static boolean isSupported(final String contentType) {
    return WEBP.equals(contentType) || PREFIXES.containsKey(contentType);
  }

  public static boolean matches(final String contentType, final byte[] bytes) {
    final boolean matches;
    if (bytes == null) {
      matches = false;
    } else if (WEBP.equals(contentType)) {
      // RIFF <4 bytes of length> WEBP
      matches =
          bytes.length >= 12
              && Arrays.equals(Arrays.copyOfRange(bytes, 0, 4), RIFF)
              && Arrays.equals(Arrays.copyOfRange(bytes, 8, 12), WEBP_TAG);
    } else {
      final byte[] prefix = PREFIXES.get(contentType);
      matches =
          prefix != null
              && bytes.length >= prefix.length
              && Arrays.equals(Arrays.copyOfRange(bytes, 0, prefix.length), prefix);
    }
    return matches;
  }
}
