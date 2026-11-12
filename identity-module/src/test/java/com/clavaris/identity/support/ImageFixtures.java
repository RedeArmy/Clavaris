package com.clavaris.identity.support;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Smallest byte sequences whose header says what a real image's would, for tests of code that
 * judges an upload by its bytes (TD-SEC-096). They are not decodable pictures and need not be: only
 * the signature and the declared size are ever read.
 */
public final class ImageFixtures {

  private ImageFixtures() {}

  public static byte[] png(final int width, final int height) {
    final ByteBuffer b = ByteBuffer.allocate(45).order(ByteOrder.BIG_ENDIAN);
    b.put(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
    b.putInt(13).put("IHDR".getBytes(StandardCharsets.US_ASCII));
    b.putInt(width).putInt(height).put(new byte[] {8, 6, 0, 0, 0, 0, 0, 0, 0});
    // then an empty IEND chunk (its checksum is not read by anything here)
    b.putInt(0).put("IEND".getBytes(StandardCharsets.US_ASCII)).putInt(0);
    return b.array();
  }

  public static byte[] png() {
    return png(1, 1);
  }

  public static byte[] jpeg(final int width, final int height) {
    // SOI, an APP0 segment, then SOF0 carrying the size.
    final ByteBuffer b =
        ByteBuffer.allocate(2 + 2 + 2 + 14 + 2 + 2 + 6 + 4 + 8 + 2).order(ByteOrder.BIG_ENDIAN);
    b.put(new byte[] {(byte) 0xFF, (byte) 0xD8});
    b.put(new byte[] {(byte) 0xFF, (byte) 0xE0}).putShort((short) 16).put(new byte[14]);
    b.put(new byte[] {(byte) 0xFF, (byte) 0xC0}).putShort((short) 11);
    b.put((byte) 8).putShort((short) height).putShort((short) width);
    b.put(new byte[] {1, 1, 0x11, 0}); // one component
    b.put(new byte[] {(byte) 0xFF, (byte) 0xDA, 0, 4, 0, 0, 1, 2});
    b.put(new byte[] {(byte) 0xFF, (byte) 0xD9});
    return b.array();
  }

  public static byte[] jpeg() {
    return jpeg(1, 1);
  }

  public static byte[] gif(final int width, final int height) {
    final ByteBuffer b = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN);
    b.put("GIF89a".getBytes(StandardCharsets.US_ASCII));
    b.putShort((short) width).putShort((short) height).put(new byte[] {0, 0, 0});
    return b.array();
  }

  /** A lossless WebP (VP8L) header. */
  public static byte[] webp(final int width, final int height) {
    final ByteBuffer b = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
    b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(22);
    b.put("WEBP".getBytes(StandardCharsets.US_ASCII));
    b.put("VP8L".getBytes(StandardCharsets.US_ASCII)).putInt(10);
    b.put((byte) 0x2F);
    b.putInt((width - 1) | ((height - 1) << 14));
    b.put(new byte[] {0});
    return b.array();
  }

  public static byte[] webp() {
    return webp(1, 1);
  }

  /** A JPEG that also carries an EXIF block holding {@code secret}, like a phone photo's. */
  public static byte[] jpegWithExif(final String secret) {
    final byte[] plain = jpeg();
    final byte[] text = (secret + "\0").getBytes(StandardCharsets.US_ASCII);
    final byte[] exif = "Exif\0\0MM\0*".getBytes(StandardCharsets.ISO_8859_1);
    final ByteBuffer segment = ByteBuffer.allocate(4 + exif.length + text.length);
    segment.put(new byte[] {(byte) 0xFF, (byte) 0xE1});
    segment.putShort((short) (2 + exif.length + text.length)).put(exif).put(text);
    final ByteBuffer out = ByteBuffer.allocate(plain.length + segment.capacity());
    out.put(plain, 0, 2).put(segment.array()).put(plain, 2, plain.length - 2);
    return out.array();
  }
}
