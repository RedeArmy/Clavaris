package com.clavaris.common.domain.image;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;

/**
 * TD-SEC-096: removes the chunks of a PNG that carry text, EXIF and a timestamp, and anything after
 * the end-of-image chunk, copying every other chunk byte for byte (so their checksums stay valid).
 * Not preserved: an orientation recorded in a PNG's {@code eXIf} chunk, which is rare and which
 * browsers apply only in recent versions.
 */
final class PngMetadata {

  private static final int SIGNATURE_LENGTH = 8;
  private static final int CHUNK_OVERHEAD = 12;
  private static final String END_TYPE = "IEND";
  private static final Set<String> DROPPED = Set.of("eXIf", "tEXt", "zTXt", "iTXt", "tIME");

  private PngMetadata() {
    // Static helper only — no instance state.
  }

  /** The cleaned file, or empty when the chunks cannot be walked to an {@code IEND}. */
  /* default */ static Optional<byte[]> strip(final byte[] image) {
    if (!ImageSignature.matches(ImageSignature.PNG, image)) {
      return Optional.empty();
    }
    final ByteArrayOutputStream out = new ByteArrayOutputStream(image.length);
    out.write(image, 0, SIGNATURE_LENGTH);
    int position = SIGNATURE_LENGTH;
    while (position + CHUNK_OVERHEAD <= image.length) {
      final long length = ImageBytes.be32(image, position);
      if (length > image.length - position - CHUNK_OVERHEAD) {
        return Optional.empty();
      }
      final String type = new String(image, position + 4, 4, StandardCharsets.US_ASCII);
      final int end = position + CHUNK_OVERHEAD + (int) length;
      if (!DROPPED.contains(type)) {
        out.write(image, position, end - position);
      }
      if (END_TYPE.equals(type)) {
        return Optional.of(out.toByteArray());
      }
      position = end;
    }
    return Optional.empty();
  }
}
