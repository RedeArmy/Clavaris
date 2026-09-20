package com.clavaris.common.domain.model;

import java.util.Arrays;

/**
 * SonarCloud "Duplicated Lines on New Code": the {@code byte[] content}-aware {@code equals}/{@code
 * hashCode}/{@code toString} override — needed because a record's auto-generated versions use the
 * array's own identity, not its bytes — had been hand-rolled identically across {@code
 * identity-module}'s {@code AccountAvatarResult.Content}, {@code
 * PlatformAccountAvatarResult.Content}, {@code StoredProfilePicture}, {@code
 * UpdateAccountProfilePictureCommand}, and {@code UpdatePlatformAccountProfilePictureCommand} — a
 * PMD CPD {@code CPD-OFF}/{@code CPD-ON} marker pair silenced the local PMD check on the first two,
 * but SonarCloud's own duplication engine doesn't honor that marker at all, so the finding kept
 * resurfacing on every new copy. Same "no single module owns this rule, extract to the shared
 * kernel" reasoning {@link AbsoluteHttpsUrlValidator} already established for this package.
 *
 * <p>Deliberately <b>not</b> a shared {@code Content} value type: each call site keeps its own
 * distinct record (some bounded to {@code Account}, some to {@code PlatformAccount}, ADR-0010; some
 * carrying extra fields like an actor) — this class only factors out the technical byte-array
 * comparison/formatting logic every one of them independently needed, never the record's own domain
 * shape.
 */
public final class BinaryContentEquality {

  private BinaryContentEquality() {
    // utility class, never instantiated
  }

  /**
   * @return whether two (content, contentType) pairs represent the same binary content — bytes
   *     compared by value, not array identity
   */
  public static boolean contentEquals(
      final byte[] content,
      final String contentType,
      final byte[] otherContent,
      final String otherContentType) {
    return Arrays.equals(content, otherContent) && contentType.equals(otherContentType);
  }

  /**
   * @return a hash code consistent with {@link #contentEquals}, combining both fields.
   */
  public static int contentHashCode(final byte[] content, final String contentType) {
    return Arrays.hashCode(content) * 31 + contentType.hashCode();
  }

  /**
   * @return {@code content}'s length rendered as {@code "byte[<n>]"} — never the raw bytes
   *     themselves, which have no debugging value spelled out as a list of numbers and could be
   *     sizable (e.g. an uploaded profile picture).
   */
  public static String describeContentLength(final byte[] content) {
    return "byte[" + content.length + "]";
  }
}
