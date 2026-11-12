package com.clavaris.common.domain.image;

/** Fixed-width integers out of an image header, big- or little-endian, as unsigned values. */
final class ImageBytes {

  private static final int BYTE_MASK = 0xFF;

  private ImageBytes() {
    // Static helper only — no instance state.
  }

  /* default */ static int unsigned(final byte[] bytes, final int at) {
    return bytes[at] & BYTE_MASK;
  }

  /* default */ static long be32(final byte[] bytes, final int at) {
    return ((long) unsigned(bytes, at) << 24)
        | (unsigned(bytes, at + 1) << 16)
        | (unsigned(bytes, at + 2) << 8)
        | unsigned(bytes, at + 3);
  }

  /* default */ static int be16(final byte[] bytes, final int at) {
    return (unsigned(bytes, at) << 8) | unsigned(bytes, at + 1);
  }

  /* default */ static int le16(final byte[] bytes, final int at) {
    return unsigned(bytes, at) | (unsigned(bytes, at + 1) << 8);
  }

  /* default */ static int le24(final byte[] bytes, final int at) {
    return le16(bytes, at) | (unsigned(bytes, at + 2) << 16);
  }

  /* default */ static long le32(final byte[] bytes, final int at) {
    return le24(bytes, at) | ((long) unsigned(bytes, at + 3) << 24);
  }
}
