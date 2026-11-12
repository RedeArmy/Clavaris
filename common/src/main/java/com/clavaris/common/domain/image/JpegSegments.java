package com.clavaris.common.domain.image;

/**
 * TD-SEC-096: walking the marker structure of a JPEG, for the code that removes its metadata.
 * Everything here takes untrusted bytes and answers -1 rather than reading past their end.
 */
final class JpegSegments {

  /* default */ static final int PREFIX = 0xFF;
  /* default */ static final int EOI = 0xD9;
  /* default */ static final int SOS = 0xDA;
  private static final int SOI = 0xD8;
  private static final int FIRST_RST = 0xD0;
  private static final int LAST_RST = 0xD7;
  private static final int TEM = 0x01;
  private static final int HEADER_LENGTH = 4;

  private JpegSegments() {
    // Static helper only — no instance state.
  }

  /** The marker byte at {@code position}, or -1 when there is no 0xFF prefix there. */
  /* default */ static int markerAt(final byte[] image, final int position) {
    return ImageBytes.unsigned(image, position) == PREFIX
        ? ImageBytes.unsigned(image, position + 1)
        : -1;
  }

  /** Markers that stand alone, with no length and no payload. */
  /* default */ static boolean isStandalone(final int marker) {
    return marker >= FIRST_RST && marker <= LAST_RST || marker == TEM || marker == SOI;
  }

  /**
   * Index just past the segment starting at {@code position}, or -1 when its length is unusable.
   */
  /* default */ static int segmentEnd(final byte[] image, final int position) {
    if (position + HEADER_LENGTH > image.length) {
      return -1;
    }
    final int length = ImageBytes.be16(image, position + 2);
    final int end = position + 2 + length;
    return length < 2 || end > image.length ? -1 : end;
  }

  // From the start-of-scan marker, over its header and the entropy-coded data (where an 0xFF is
  // followed by 0x00 or a restart marker), through any later segments, to the end-of-image marker.
  /* default */ static int imageEnd(final byte[] image, final int scanAt) {
    if (markerAt(image, scanAt) != SOS) {
      return scanAt + 2;
    }
    int position = segmentEnd(image, scanAt);
    while (position >= 0 && position + 1 < image.length) {
      if (ImageBytes.unsigned(image, position) == PREFIX
          && ImageBytes.unsigned(image, position + 1) == EOI) {
        return position + 2;
      }
      position = skipOne(image, position);
    }
    return -1;
  }

  /** The next position to examine inside a scan and the segments that may follow it. */
  private static int skipOne(final byte[] image, final int position) {
    if (ImageBytes.unsigned(image, position) != PREFIX) {
      return position + 1;
    }
    final int marker = ImageBytes.unsigned(image, position + 1);
    if (marker == 0 || marker >= FIRST_RST && marker <= LAST_RST) {
      return position + 2;
    }
    return marker == PREFIX ? position + 1 : segmentEnd(image, position);
  }
}
