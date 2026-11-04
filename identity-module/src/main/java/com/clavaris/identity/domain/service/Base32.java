package com.clavaris.identity.domain.service;

import java.io.ByteArrayOutputStream;
import java.util.Locale;

/**
 * RFC 4648 base32 without padding, the alphabet every authenticator app reads a shared secret in.
 * Written out because the JDK has none and this is twenty lines; decoding is lenient about case,
 * spaces and hyphens (a person types the secret in groups) and strict about everything else.
 */
public final class Base32 {

  private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  private static final int BITS_PER_CHAR = 5;
  private static final int BITS_PER_BYTE = 8;
  private static final int BYTE_MASK = 0xFF;
  private static final int CHAR_MASK = 0x1F;

  private Base32() {
    // Static utility.
  }

  public static String encode(final byte[] data) {
    final StringBuilder out = new StringBuilder((data.length * BITS_PER_BYTE + 4) / BITS_PER_CHAR);
    int buffer = 0;
    int bits = 0;
    for (final byte value : data) {
      buffer = (buffer << BITS_PER_BYTE) | (value & BYTE_MASK);
      bits += BITS_PER_BYTE;
      while (bits >= BITS_PER_CHAR) {
        out.append(ALPHABET.charAt((buffer >> (bits - BITS_PER_CHAR)) & CHAR_MASK));
        bits -= BITS_PER_CHAR;
      }
    }
    if (bits > 0) {
      out.append(ALPHABET.charAt((buffer << (BITS_PER_CHAR - bits)) & CHAR_MASK));
    }
    return out.toString();
  }

  /**
   * @throws IllegalArgumentException if the text holds a character outside the alphabet
   */
  public static byte[] decode(final String text) {
    final String cleaned = text.replace(" ", "").replace("-", "").toUpperCase(Locale.ROOT);
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    int buffer = 0;
    int bits = 0;
    for (int i = 0; i < cleaned.length(); i++) {
      final int value = ALPHABET.indexOf(cleaned.charAt(i));
      if (value < 0) {
        throw new IllegalArgumentException("not a base32 character");
      }
      buffer = (buffer << BITS_PER_CHAR) | value;
      bits += BITS_PER_CHAR;
      if (bits >= BITS_PER_BYTE) {
        out.write((buffer >> (bits - BITS_PER_BYTE)) & BYTE_MASK);
        bits -= BITS_PER_BYTE;
      }
    }
    return out.toByteArray();
  }
}
