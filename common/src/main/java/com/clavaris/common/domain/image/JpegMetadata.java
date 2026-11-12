package com.clavaris.common.domain.image;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * TD-SEC-096: removes from a JPEG everything that describes the person or the device rather than
 * the picture, without decoding or re-encoding a pixel. Dropped: the EXIF and XMP block (GPS
 * position, camera, time), IPTC and the other application segments, comments, and anything stored
 * after the end of the image (a motion photo's video, a second embedded picture). Kept: JFIF, the
 * colour profile, Adobe's colour-transform segment and every segment the picture needs. The EXIF
 * orientation is rewritten into a block of its own so a portrait photo still stands upright.
 */
final class JpegMetadata {

  private static final int SOI = 0xD8;
  private static final int PREFIX = JpegSegments.PREFIX;
  private static final int EOI = JpegSegments.EOI;
  private static final int SOS = JpegSegments.SOS;
  private static final int APP0 = 0xE0;
  private static final int APP1 = 0xE1;
  private static final int APP2 = 0xE2;
  private static final int APP14 = 0xEE;
  private static final int FIRST_APP = 0xE0;
  private static final int LAST_APP = 0xEF;
  private static final int COMMENT = 0xFE;
  private static final int HEADER_LENGTH = 4;
  private static final byte[] EXIF_ID = {'E', 'x', 'i', 'f', 0, 0};
  private static final byte[] ICC_ID = "ICC_PROFILE\0".getBytes(StandardCharsets.US_ASCII);

  private JpegMetadata() {
    // Static helper only — no instance state.
  }

  /** What the segments before the scan hold: the ones to keep and the photo's orientation. */
  private static final class Headers {
    private final List<byte[]> kept = new ArrayList<>();
    private int orientation = ExifOrientation.UPRIGHT;
    private int scanAt;
  }

  /** The cleaned file, or empty when the structure cannot be walked to an end-of-image marker. */
  /* default */ static Optional<byte[]> strip(final byte[] image) {
    if (image.length < HEADER_LENGTH || !ImageSignature.matches(ImageSignature.JPEG, image)) {
      return Optional.empty();
    }
    final Headers headers = readHeaders(image);
    if (headers == null) {
      return Optional.empty();
    }
    final int end = JpegSegments.imageEnd(image, headers.scanAt);
    return end < 0 ? Optional.empty() : Optional.of(assemble(image, headers, end));
  }

  /** Walks the segments up to the scan; null when one cannot be read or the file just ends. */
  private static Headers readHeaders(final byte[] image) {
    final Headers headers = new Headers();
    int position = 2;
    while (position + 1 < image.length) {
      final int marker = JpegSegments.markerAt(image, position);
      if (marker < 0) {
        return null;
      }
      if (marker == SOS || marker == EOI) {
        headers.scanAt = position;
        return headers;
      }
      position = consume(image, position, marker, headers);
      if (position < 0) {
        return null;
      }
    }
    return null;
  }

  /** Handles the marker at {@code position}; returns where the next one starts, or -1. */
  private static int consume(
      final byte[] image, final int position, final int marker, final Headers headers) {
    if (marker == PREFIX) {
      return position + 1;
    }
    if (JpegSegments.isStandalone(marker)) {
      return position + 2;
    }
    final int end = JpegSegments.segmentEnd(image, position);
    if (end < 0) {
      return -1;
    }
    final byte[] segment = Arrays.copyOfRange(image, position, end);
    if (marker == APP1 && hasPrefix(segment, EXIF_ID)) {
      headers.orientation =
          ExifOrientation.read(
              Arrays.copyOfRange(segment, HEADER_LENGTH + EXIF_ID.length, end - position));
    }
    if (keeps(marker, segment)) {
      headers.kept.add(segment);
    }
    return end;
  }

  private static byte[] assemble(final byte[] image, final Headers headers, final int end) {
    final ByteArrayOutputStream out = new ByteArrayOutputStream(image.length);
    out.write(PREFIX);
    out.write(SOI);
    int leading = 0;
    while (leading < headers.kept.size() && isApp0(headers.kept.get(leading))) {
      leading++;
    }
    headers.kept.subList(0, leading).forEach(out::writeBytes);
    if (headers.orientation > ExifOrientation.UPRIGHT) {
      out.writeBytes(orientationSegment(headers.orientation));
    }
    headers.kept.subList(leading, headers.kept.size()).forEach(out::writeBytes);
    out.write(image, headers.scanAt, end - headers.scanAt);
    return out.toByteArray();
  }

  private static boolean isApp0(final byte[] segment) {
    return ImageBytes.unsigned(segment, 1) == APP0;
  }

  private static byte[] orientationSegment(final int orientation) {
    final byte[] tiff = ExifOrientation.onlyOrientation(orientation);
    final int length = 2 + EXIF_ID.length + tiff.length;
    final ByteArrayOutputStream segment = new ByteArrayOutputStream();
    segment.write(PREFIX);
    segment.write(APP1);
    segment.write(length >> 8);
    segment.write(length);
    segment.writeBytes(EXIF_ID);
    segment.writeBytes(tiff);
    return segment.toByteArray();
  }

  // Everything the picture does not need to be drawn: EXIF/XMP and the other application segments
  // except JFIF, the colour profile and Adobe's transform, plus comments.
  private static boolean keeps(final int marker, final byte[] segment) {
    if (marker == COMMENT) {
      return false;
    }
    if (marker < FIRST_APP || marker > LAST_APP) {
      return true;
    }
    return marker == APP0 || marker == APP14 || marker == APP2 && hasPrefix(segment, ICC_ID);
  }

  private static boolean hasPrefix(final byte[] segment, final byte[] id) {
    return segment.length >= HEADER_LENGTH + id.length
        && Arrays.equals(Arrays.copyOfRange(segment, HEADER_LENGTH, HEADER_LENGTH + id.length), id);
  }
}
