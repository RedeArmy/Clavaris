package com.clavaris.common.domain.image;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;

/** Real pictures with metadata injected, for tests of {@link ImageMetadata} (TD-SEC-096). */
final class MetadataFixtures {

  /** A string that stands for a GPS position; the tests look for it in the output. */
  static final String SECRET = "SECRET-GPS-4.8N-9.4W";

  private MetadataFixtures() {}

  static byte[] bytes(final int... values) {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (final int value : values) {
      out.write(value);
    }
    return out.toByteArray();
  }

  static byte[] concat(final byte[]... parts) {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (final byte[] part : parts) {
      out.writeBytes(part);
    }
    return out.toByteArray();
  }

  static boolean contains(final byte[] haystack, final String needle) {
    return new String(haystack, StandardCharsets.ISO_8859_1).contains(needle);
  }

  /** A little-endian TIFF with a Make tag holding {@link #SECRET} and the given Orientation. */
  static byte[] tiff(final int orientation) {
    final byte[] secret = (SECRET + "\0").getBytes(StandardCharsets.US_ASCII);
    final ByteBuffer b = ByteBuffer.allocate(38 + secret.length).order(ByteOrder.LITTLE_ENDIAN);
    b.put(new byte[] {'I', 'I', 42, 0}).putInt(8);
    b.putShort((short) 2);
    b.putShort((short) 0x010F).putShort((short) 2).putInt(secret.length).putInt(38);
    b.putShort((short) 0x0112)
        .putShort((short) 3)
        .putInt(1)
        .putShort((short) orientation)
        .putShort((short) 0);
    b.putInt(0);
    b.put(secret);
    return b.array();
  }

  static byte[] pixels(final String format, final int type) throws IOException {
    final BufferedImage image = new BufferedImage(8, 8, type);
    image.setRGB(3, 3, 0xFFAA5500);
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(image, format, out);
    return out.toByteArray();
  }

  static byte[] jpegSegment(final int marker, final byte[] payload) {
    final int length = payload.length + 2;
    return concat(bytes(0xFF, marker, length >> 8, length & 0xFF), payload);
  }

  static byte[] exifSegment(final int orientation) {
    return jpegSegment(
        0xE1, concat("Exif\0\0".getBytes(StandardCharsets.US_ASCII), tiff(orientation)));
  }

  static byte[] pngChunk(final String type, final byte[] data) {
    final ByteBuffer b = ByteBuffer.allocate(12 + data.length);
    b.putInt(data.length).put(type.getBytes(StandardCharsets.US_ASCII)).put(data).putInt(0);
    return b.array();
  }
}
