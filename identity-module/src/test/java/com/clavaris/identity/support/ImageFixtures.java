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
    final ByteBuffer b = ByteBuffer.allocate(33).order(ByteOrder.BIG_ENDIAN);
    b.put(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
    b.putInt(13).put("IHDR".getBytes(StandardCharsets.US_ASCII));
    b.putInt(width).putInt(height).put(new byte[] {8, 6, 0, 0, 0});
    return b.array();
  }

  public static byte[] png() {
    return png(1, 1);
  }

  public static byte[] jpeg(final int width, final int height) {
    // SOI, an APP0 segment, then SOF0 carrying the size.
    final ByteBuffer b =
        ByteBuffer.allocate(2 + 2 + 2 + 14 + 2 + 2 + 6).order(ByteOrder.BIG_ENDIAN);
    b.put(new byte[] {(byte) 0xFF, (byte) 0xD8});
    b.put(new byte[] {(byte) 0xFF, (byte) 0xE0}).putShort((short) 16).put(new byte[14]);
    b.put(new byte[] {(byte) 0xFF, (byte) 0xC0}).putShort((short) 8);
    b.put((byte) 8).putShort((short) height).putShort((short) width);
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
}
