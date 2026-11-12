package com.clavaris.common.domain.image;

import java.util.Optional;

/**
 * TD-SEC-096: the pixel size an image declares in its own header, read without decoding it. A small
 * file can declare a canvas of billions of pixels (a decompression bomb) that costs whoever draws
 * it memory and time, so an upload is judged by what it says it is, not only by its byte count.
 * Headers are parsed by hand rather than through {@code ImageIO}: no pixel is ever decoded, nothing
 * from {@code java.desktop} is loaded, and a truncated or hostile header simply yields empty.
 */
public final class ImageDimensions {

  private static final int PNG_MIN_LENGTH = 24;
  private static final int GIF_MIN_LENGTH = 10;

  private ImageDimensions() {
    // Static helper only — no instance state.
  }

  public static Optional<Size> read(final String contentType, final byte[] bytes) {
    if (bytes == null || contentType == null) {
      return Optional.empty();
    }
    final Optional<Size> size =
        switch (contentType) {
          case ImageSignature.PNG -> png(bytes);
          case ImageSignature.GIF -> gif(bytes);
          case ImageSignature.JPEG -> JpegHeader.size(bytes);
          case ImageSignature.WEBP -> WebpHeader.size(bytes);
          default -> Optional.empty();
        };
    return size.filter(found -> found.width() > 0 && found.height() > 0);
  }

  // IHDR is always the first chunk: 8-byte signature, 4 length, "IHDR", then width and height.
  private static Optional<Size> png(final byte[] bytes) {
    final boolean ihdr =
        bytes.length >= PNG_MIN_LENGTH
            && bytes[12] == 'I'
            && bytes[13] == 'H'
            && bytes[14] == 'D'
            && bytes[15] == 'R';
    return ihdr
        ? Optional.of(new Size(ImageBytes.be32(bytes, 16), ImageBytes.be32(bytes, 20)))
        : Optional.empty();
  }

  // "GIF89a", then the logical screen's width and height as little-endian 16-bit values.
  private static Optional<Size> gif(final byte[] bytes) {
    return bytes.length < GIF_MIN_LENGTH
        ? Optional.empty()
        : Optional.of(new Size(ImageBytes.le16(bytes, 6), ImageBytes.le16(bytes, 8)));
  }

  /** Width and height as declared by the header. */
  public record Size(long width, long height) {
    public long pixels() {
      return width * height;
    }
  }
}
