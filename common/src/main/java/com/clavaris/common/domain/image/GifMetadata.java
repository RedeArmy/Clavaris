package com.clavaris.common.domain.image;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * TD-SEC-096: removes the comment and application extensions of a GIF, where editors and cameras
 * leave text and XMP, and anything after the trailer. Kept: the header, the colour tables, every
 * frame, the graphic-control extensions that time an animation and the loop-count extension
 * (NETSCAPE2.0 and ANIMEXTS1.0), so an animated picture still animates.
 */
final class GifMetadata {

  private static final int FINISHED = Integer.MIN_VALUE;
  private static final int HEADER_LENGTH = 6;
  private static final int SCREEN_LENGTH = 7;
  private static final int DESCRIPTOR_LENGTH = 9;
  private static final int EXTENSION = 0x21;
  private static final int IMAGE = 0x2C;
  private static final int TRAILER = 0x3B;
  private static final int GRAPHIC_CONTROL = 0xF9;
  private static final int PLAIN_TEXT = 0x01;
  private static final int APPLICATION = 0xFF;
  private static final int TABLE_FLAG = 0x80;
  private static final int TABLE_SIZE_MASK = 0x07;
  private static final int COLOUR_BYTES = 3;
  private static final int APPLICATION_ID_LENGTH = 11;

  private GifMetadata() {
    // Static helper only — no instance state.
  }

  /** The cleaned file, or empty when the blocks cannot be walked. */
  /* default */ static Optional<byte[]> strip(final byte[] image) {
    if (!ImageSignature.matches(ImageSignature.GIF, image)
        || image.length < HEADER_LENGTH + SCREEN_LENGTH) {
      return Optional.empty();
    }
    final ByteArrayOutputStream out = new ByteArrayOutputStream(image.length);
    int position = HEADER_LENGTH + SCREEN_LENGTH + tableLength(image[HEADER_LENGTH + 4]);
    if (position > image.length) {
      return Optional.empty();
    }
    out.write(image, 0, position);
    while (position < image.length) {
      position = copyBlock(image, position, out);
      if (position == FINISHED) {
        return Optional.of(out.toByteArray());
      }
      if (position < 0) {
        return Optional.empty();
      }
    }
    // Ended on a block boundary without a trailer: add the one the format asks for.
    out.write(TRAILER);
    return Optional.of(out.toByteArray());
  }

  /** Copies the block at {@code position} if it is kept; returns where the next one starts. */
  private static int copyBlock(
      final byte[] image, final int position, final ByteArrayOutputStream out) {
    final int block = ImageBytes.unsigned(image, position);
    int end = -1;
    boolean keep = false;
    if (block == TRAILER) {
      out.write(TRAILER);
      return FINISHED;
    } else if (block == EXTENSION) {
      end = subBlocksEnd(image, position + 2);
      keep = end >= 0 && keepsExtension(image, position);
    } else if (block == IMAGE) {
      end = frameEnd(image, position);
      keep = end >= 0;
    }
    if (keep) {
      out.write(image, position, end - position);
    }
    return end;
  }

  private static int tableLength(final byte packed) {
    final int flags = packed & 0xFF;
    return (flags & TABLE_FLAG) == 0 ? 0 : COLOUR_BYTES * (1 << ((flags & TABLE_SIZE_MASK) + 1));
  }

  private static boolean keepsExtension(final byte[] image, final int position) {
    final int label = ImageBytes.unsigned(image, position + 1);
    if (label == GRAPHIC_CONTROL || label == PLAIN_TEXT) {
      return true;
    }
    if (label != APPLICATION) {
      return false;
    }
    final int idAt = position + 3;
    if (idAt + APPLICATION_ID_LENGTH > image.length) {
      return false;
    }
    final String id = new String(image, idAt, APPLICATION_ID_LENGTH, StandardCharsets.US_ASCII);
    return "NETSCAPE2.0".equals(id) || "ANIMEXTS1.0".equals(id);
  }

  /** Index past an image descriptor, its optional colour table, code size and data sub-blocks. */
  private static int frameEnd(final byte[] image, final int position) {
    final int packedAt = position + DESCRIPTOR_LENGTH;
    if (packedAt >= image.length) {
      return -1;
    }
    final int dataAt = position + 1 + DESCRIPTOR_LENGTH + tableLength(image[packedAt]);
    return dataAt >= image.length ? -1 : subBlocksEnd(image, dataAt + 1);
  }

  // A run of sub-blocks, each a length byte and that many bytes, ended by a zero length.
  private static int subBlocksEnd(final byte[] image, final int from) {
    int position = from;
    while (position < image.length) {
      final int size = ImageBytes.unsigned(image, position);
      position += 1 + size;
      if (size == 0) {
        return position <= image.length ? position : -1;
      }
    }
    return -1;
  }
}
