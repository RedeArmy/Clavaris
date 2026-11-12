package com.clavaris.common.domain.image;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** The size in a WebP's first chunk: lossy "VP8 ", lossless "VP8L" or extended "VP8X". */
final class WebpHeader {

  private static final int MIN_LENGTH = 30;
  private static final int FIRST_CHUNK_AT = 12;
  private static final int VP8L_SIGNATURE = 0x2F;
  private static final int LOSSY_FRAME_MASK = 0x3FFF;
  private static final int VP8L_MASK = 0x3FFF;
  private static final int VP8L_HEIGHT_SHIFT = 14;

  private WebpHeader() {
    // Static helper only — no instance state.
  }

  /* default */ static Optional<ImageDimensions.Size> size(final byte[] bytes) {
    if (bytes.length < MIN_LENGTH) {
      return Optional.empty();
    }
    return switch (new String(bytes, FIRST_CHUNK_AT, 4, StandardCharsets.US_ASCII)) {
      case "VP8 " -> lossy(bytes);
      case "VP8L" -> lossless(bytes);
      case "VP8X" ->
          Optional.of(
              new ImageDimensions.Size(
                  ImageBytes.le24(bytes, 24) + 1L, ImageBytes.le24(bytes, 27) + 1L));
      default -> Optional.empty();
    };
  }

  private static Optional<ImageDimensions.Size> lossy(final byte[] bytes) {
    final boolean startCode =
        ImageBytes.unsigned(bytes, 23) == 0x9D
            && ImageBytes.unsigned(bytes, 24) == 0x01
            && ImageBytes.unsigned(bytes, 25) == 0x2A;
    return startCode
        ? Optional.of(
            new ImageDimensions.Size(
                ImageBytes.le16(bytes, 26) & LOSSY_FRAME_MASK,
                ImageBytes.le16(bytes, 28) & LOSSY_FRAME_MASK))
        : Optional.empty();
  }

  private static Optional<ImageDimensions.Size> lossless(final byte[] bytes) {
    if (ImageBytes.unsigned(bytes, 20) != VP8L_SIGNATURE) {
      return Optional.empty();
    }
    final long bits = ImageBytes.le32(bytes, 21);
    return Optional.of(
        new ImageDimensions.Size(
            (bits & VP8L_MASK) + 1, ((bits >> VP8L_HEIGHT_SHIFT) & VP8L_MASK) + 1));
  }
}
