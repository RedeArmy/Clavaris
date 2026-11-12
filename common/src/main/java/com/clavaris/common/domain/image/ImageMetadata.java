package com.clavaris.common.domain.image;

import java.util.Optional;

/**
 * TD-SEC-096: an uploaded picture with the metadata that identifies a person removed, by editing
 * the file's container and never decoding its pixels. A phone photo carries the GPS position, the
 * device and the time it was taken; a picture shown beside someone's name should carry none of it.
 * Each format has its own structure, so each has its own class; the EXIF orientation is the one
 * thing kept (JPEG and WebP), because without it a portrait photo shows sideways.
 *
 * <p>Fails closed: a file whose structure cannot be walked yields empty, and the caller refuses it,
 * rather than storing something that might still hold the metadata.
 */
public final class ImageMetadata {

  private ImageMetadata() {
    // Static helper only — no instance state.
  }

  /** The cleaned bytes, or empty if the type is unsupported or the file is malformed. */
  public static Optional<byte[]> strip(final String contentType, final byte[] image) {
    if (contentType == null || image == null) {
      return Optional.empty();
    }
    return switch (contentType) {
      case ImageSignature.JPEG -> JpegMetadata.strip(image);
      case ImageSignature.PNG -> PngMetadata.strip(image);
      case ImageSignature.GIF -> GifMetadata.strip(image);
      case ImageSignature.WEBP -> WebpMetadata.strip(image);
      default -> Optional.empty();
    };
  }
}
