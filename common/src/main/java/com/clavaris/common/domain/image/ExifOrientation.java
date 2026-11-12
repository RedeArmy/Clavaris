package com.clavaris.common.domain.image;

/**
 * TD-SEC-096: the one thing worth keeping from a photo's EXIF block. A phone stores a picture as
 * the sensor saw it and records in the Orientation tag how to turn it upright, so dropping all of
 * EXIF would show portrait photos sideways, while keeping all of it keeps the GPS position, the
 * device and the time. This reads that single tag and writes a block that holds only it.
 */
final class ExifOrientation {

  /** The value that means "upright"; also what an absent or unreadable tag is taken to be. */
  /* default */ static final int UPRIGHT = 1;

  private static final int TIFF_HEADER_LENGTH = 8;
  private static final int TIFF_MAGIC = 42;
  private static final int ENTRY_LENGTH = 12;
  private static final int ORIENTATION_TAG = 0x0112;
  private static final int SHORT_TYPE = 3;
  private static final int LAST_ORIENTATION = 8;
  private static final byte[] MINIMAL_TIFF = {
    'M',
    'M',
    0,
    42,
    0,
    0,
    0,
    8, // big-endian header, first directory at offset 8
    0,
    1, // one entry
    0x01,
    0x12,
    0,
    3,
    0,
    0,
    0,
    1,
    0,
    0,
    0,
    0, // Orientation, SHORT, count 1, value patched below
    0,
    0,
    0,
    0 // no next directory
  };
  private static final int VALUE_AT = 18;

  private ExifOrientation() {
    // Static helper only — no instance state.
  }

  /** The orientation (1 to 8) in a TIFF structure, or {@link #UPRIGHT} when absent or malformed. */
  /* default */ static int read(final byte[] tiff) {
    if (tiff.length < TIFF_HEADER_LENGTH) {
      return UPRIGHT;
    }
    final boolean little = tiff[0] == 'I' && tiff[1] == 'I';
    final boolean big = tiff[0] == 'M' && tiff[1] == 'M';
    if (!little && !big || u16(tiff, 2, little) != TIFF_MAGIC) {
      return UPRIGHT;
    }
    final long directory = u32(tiff, 4, little);
    if (directory < TIFF_HEADER_LENGTH || directory + 2 > tiff.length) {
      return UPRIGHT;
    }
    return findOrientation(tiff, (int) directory, little);
  }

  private static int findOrientation(final byte[] tiff, final int directory, final boolean little) {
    final int count = u16(tiff, directory, little);
    for (int index = 0; index < count; index++) {
      final int entry = directory + 2 + ENTRY_LENGTH * index;
      if (entry + ENTRY_LENGTH > tiff.length) {
        break;
      }
      if (u16(tiff, entry, little) == ORIENTATION_TAG) {
        final boolean oneShort =
            u16(tiff, entry + 2, little) == SHORT_TYPE && u32(tiff, entry + 4, little) == 1;
        final int value = u16(tiff, entry + 8, little);
        return oneShort && value > UPRIGHT && value <= LAST_ORIENTATION ? value : UPRIGHT;
      }
    }
    return UPRIGHT;
  }

  /** A TIFF structure holding only the Orientation tag set to {@code orientation}. */
  /* default */ static byte[] onlyOrientation(final int orientation) {
    final byte[] tiff = MINIMAL_TIFF.clone();
    tiff[VALUE_AT] = 0;
    tiff[VALUE_AT + 1] = (byte) orientation;
    return tiff;
  }

  private static int u16(final byte[] bytes, final int at, final boolean little) {
    final int first = ImageBytes.unsigned(bytes, at);
    final int second = ImageBytes.unsigned(bytes, at + 1);
    return little ? first | second << 8 : first << 8 | second;
  }

  private static long u32(final byte[] bytes, final int at, final boolean little) {
    return little ? ImageBytes.le32(bytes, at) : ImageBytes.be32(bytes, at);
  }
}
