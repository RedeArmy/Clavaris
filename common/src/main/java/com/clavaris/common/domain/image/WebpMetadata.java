package com.clavaris.common.domain.image;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * TD-SEC-096: removes the {@code EXIF} and {@code XMP } chunks of a WebP and clears their flags in
 * the extended header, copying every other chunk untouched. As with JPEG, the EXIF orientation is
 * kept: when a photo is not upright a new {@code EXIF} chunk holding only that tag is written.
 */
final class WebpMetadata {

  private static final int RIFF_HEADER = 12;
  private static final int CHUNK_HEADER = 8;
  private static final int VP8X_FLAGS_AT = 8;
  private static final int EXIF_FLAG = 0x08;
  private static final int XMP_FLAG = 0x04;
  private static final String EXIF = "EXIF";
  private static final String XMP = "XMP ";
  private static final String VP8X = "VP8X";
  private static final byte[] EXIF_PREFIX = {'E', 'x', 'i', 'f', 0, 0};

  private WebpMetadata() {
    // Static helper only — no instance state.
  }

  /** The cleaned file, or empty when the chunks cannot be walked. */
  /* default */ static Optional<byte[]> strip(final byte[] image) {
    if (!ImageSignature.matches(ImageSignature.WEBP, image)) {
      return Optional.empty();
    }
    final long declared = ImageBytes.le32(image, 4) + CHUNK_HEADER;
    final int end = (int) Math.min(image.length, declared);
    final List<byte[]> chunks = new ArrayList<>();
    int orientation = ExifOrientation.UPRIGHT;
    int position = RIFF_HEADER;
    while (position + CHUNK_HEADER <= end) {
      final String type = new String(image, position, 4, StandardCharsets.US_ASCII);
      final long size = ImageBytes.le32(image, position + 4);
      final long chunkEnd = position + CHUNK_HEADER + size + (size & 1);
      if (chunkEnd > end) {
        return Optional.empty();
      }
      if (EXIF.equals(type)) {
        orientation = orientationIn(image, position + CHUNK_HEADER, (int) size);
      } else if (!XMP.equals(type)) {
        chunks.add(Arrays.copyOfRange(image, position, (int) chunkEnd));
      }
      position = (int) chunkEnd;
    }
    return Optional.of(assemble(chunks, orientation));
  }

  private static int orientationIn(final byte[] image, final int from, final int size) {
    final int skip =
        size >= EXIF_PREFIX.length
                && Arrays.equals(
                    Arrays.copyOfRange(image, from, from + EXIF_PREFIX.length), EXIF_PREFIX)
            ? EXIF_PREFIX.length
            : 0;
    return ExifOrientation.read(Arrays.copyOfRange(image, from + skip, from + size));
  }

  private static byte[] assemble(final List<byte[]> chunks, final int orientation) {
    final boolean extended =
        !chunks.isEmpty()
            && VP8X.equals(new String(chunks.get(0), 0, 4, StandardCharsets.US_ASCII));
    final boolean keepOrientation = extended && orientation > ExifOrientation.UPRIGHT;
    if (extended) {
      final byte[] header = chunks.get(0);
      header[VP8X_FLAGS_AT] &= (byte) ~(EXIF_FLAG | XMP_FLAG);
      if (keepOrientation) {
        header[VP8X_FLAGS_AT] |= (byte) EXIF_FLAG;
      }
    }
    if (keepOrientation) {
      chunks.add(exifChunk(ExifOrientation.onlyOrientation(orientation)));
    }
    final ByteArrayOutputStream body = new ByteArrayOutputStream();
    body.writeBytes("WEBP".getBytes(StandardCharsets.US_ASCII));
    chunks.forEach(body::writeBytes);
    final byte[] payload = body.toByteArray();
    final ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length + CHUNK_HEADER);
    out.writeBytes("RIFF".getBytes(StandardCharsets.US_ASCII));
    out.writeBytes(littleEndian(payload.length));
    out.writeBytes(payload);
    return out.toByteArray();
  }

  private static byte[] exifChunk(final byte[] tiff) {
    final ByteArrayOutputStream chunk = new ByteArrayOutputStream();
    chunk.writeBytes(EXIF.getBytes(StandardCharsets.US_ASCII));
    chunk.writeBytes(littleEndian(tiff.length));
    chunk.writeBytes(tiff);
    chunk.writeBytes(new byte[tiff.length & 1]); // chunks are padded to an even length
    return chunk.toByteArray();
  }

  private static byte[] littleEndian(final int value) {
    return new byte[] {
      (byte) value, (byte) (value >> 8), (byte) (value >> 16), (byte) (value >> 24)
    };
  }
}
