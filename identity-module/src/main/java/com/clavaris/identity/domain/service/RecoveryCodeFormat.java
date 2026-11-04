package com.clavaris.identity.domain.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * What a recovery code looks like and how it is kept. Sixteen characters of base32 are 80 bits,
 * shown in groups of four. That much entropy is the reason a plain SHA-256 of the code is enough to
 * store: a leaked table cannot be searched offline, so no salt or slow hash is needed, and a code
 * can be looked up by its hash instead of compared one by one.
 */
public final class RecoveryCodeFormat {

  /** How many codes a person is given at once. */
  public static final int CODES_PER_SET = 10;

  private static final int CODE_CHARS = 16;
  private static final int GROUP = 4;
  private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  private static final SecureRandom RANDOM = new SecureRandom();

  private RecoveryCodeFormat() {
    // Static utility.
  }

  /** A fresh set: the codes to show once, and the hashes that are kept. */
  public record Set(List<String> codes, List<String> hashes) {}

  public static Set newSet() {
    final List<String> codes = new ArrayList<>(CODES_PER_SET);
    final List<String> hashes = new ArrayList<>(CODES_PER_SET);
    for (int i = 0; i < CODES_PER_SET; i++) {
      final String code = generate();
      codes.add(code);
      hashes.add(hash(normalize(code)));
    }
    return new Set(List.copyOf(codes), List.copyOf(hashes));
  }

  /** A new code in its display form, {@code ABCD-EFGH-IJKL-MNOP}. */
  public static String generate() {
    final StringBuilder code = new StringBuilder(CODE_CHARS + CODE_CHARS / GROUP);
    for (int i = 0; i < CODE_CHARS; i++) {
      if (i > 0 && i % GROUP == 0) {
        code.append('-');
      }
      code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
    }
    return code.toString();
  }

  /** The text without hyphens, spaces or case, or {@code null} when it cannot be a code. */
  public static String normalize(final String typed) {
    if (typed == null) {
      return null;
    }
    final String cleaned = typed.replace("-", "").replace(" ", "").toUpperCase(Locale.ROOT);
    if (cleaned.length() != CODE_CHARS) {
      return null;
    }
    for (int i = 0; i < cleaned.length(); i++) {
      if (ALPHABET.indexOf(cleaned.charAt(i)) < 0) {
        return null;
      }
    }
    return cleaned;
  }

  /** The stored form of a normalised code. */
  public static String hash(final String normalized) {
    try {
      final byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }
}
