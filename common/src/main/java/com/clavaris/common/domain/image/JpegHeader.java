package com.clavaris.common.domain.image;

import java.util.Optional;

/** The size in a JPEG's first start-of-frame segment. */
final class JpegHeader {

  private static final int MARKER_PREFIX = 0xFF;
  private static final int FIRST_SOF = 0xC0;
  private static final int LAST_SOF = 0xCF;
  private static final int DHT = 0xC4;
  private static final int JPG = 0xC8;
  private static final int DAC = 0xCC;
  private static final int FIRST_RST = 0xD0;
  private static final int EOI = 0xD9;
  private static final int TEM = 0x01;
  private static final int SOF_HEIGHT_AT = 5;
  private static final int SOF_WIDTH_AT = 7;
  private static final int AFTER_SOI = 2;

  private JpegHeader() {
    // Static helper only — no instance state.
  }

  /** Walks the marker segments after SOI up to the first start-of-frame. */
  /* default */ static Optional<ImageDimensions.Size> size(final byte[] bytes) {
    int offset = AFTER_SOI;
    while (offset + 3 < bytes.length) {
      final int marker = markerAt(bytes, offset);
      if (marker < 0) {
        return Optional.empty();
      }
      if (isStartOfFrame(marker)) {
        return frameSize(bytes, offset);
      }
      final int step = segmentStep(bytes, offset, marker);
      if (step <= 0) {
        return Optional.empty();
      }
      offset += step;
    }
    return Optional.empty();
  }

  /** The marker byte at {@code offset}, or -1 when there is no 0xFF prefix there. */
  private static int markerAt(final byte[] bytes, final int offset) {
    return ImageBytes.unsigned(bytes, offset) == MARKER_PREFIX
        ? ImageBytes.unsigned(bytes, offset + 1)
        : -1;
  }

  private static boolean isStartOfFrame(final int marker) {
    return marker >= FIRST_SOF
        && marker <= LAST_SOF
        && marker != DHT
        && marker != JPG
        && marker != DAC;
  }

  // Fill byte (0xFF 0xFF) advances one; markers without a payload advance two; the rest carry a
  // 16-bit length that includes itself. Zero means the length is not usable.
  private static int segmentStep(final byte[] bytes, final int offset, final int marker) {
    final int step;
    if (marker == MARKER_PREFIX) {
      step = 1;
    } else if (marker >= FIRST_RST && marker <= EOI || marker == TEM) {
      step = 2;
    } else {
      final int length = ImageBytes.be16(bytes, offset + 2);
      step = length < 2 ? 0 : 2 + length;
    }
    return step;
  }

  private static Optional<ImageDimensions.Size> frameSize(final byte[] bytes, final int offset) {
    return offset + SOF_WIDTH_AT + 1 < bytes.length
        ? Optional.of(
            new ImageDimensions.Size(
                ImageBytes.be16(bytes, offset + SOF_WIDTH_AT),
                ImageBytes.be16(bytes, offset + SOF_HEIGHT_AT)))
        : Optional.empty();
  }
}
