package com.clavaris.common.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Direct coverage for the shared helper extracted out of {@code AccountAvatarResult.Content},
 * {@code PlatformAccountAvatarResult.Content}, {@code StoredProfilePicture}, {@code
 * UpdateAccountProfilePictureCommand}, and {@code UpdatePlatformAccountProfilePictureCommand} —
 * those classes' own tests still cover the behaviour through their own public API, this locks in
 * the shared helper's own contract directly instead of only indirectly through five different
 * callers.
 */
class BinaryContentEqualityTest {

  @Test
  void contentEqualsIsTrueForTheSameBytesAndContentType() {
    boolean result =
        BinaryContentEquality.contentEquals(
            new byte[] {1, 2, 3}, "image/png", new byte[] {1, 2, 3}, "image/png");

    assertThat(result).isTrue();
  }

  @Test
  void contentEqualsComparesBytesByValueNotArrayIdentity() {
    byte[] first = {1, 2, 3};
    byte[] second = new byte[] {1, 2, 3};

    assertThat(first).isNotSameAs(second);
    assertThat(BinaryContentEquality.contentEquals(first, "image/png", second, "image/png"))
        .isTrue();
  }

  @Test
  void contentEqualsIsFalseWhenBytesDiffer() {
    boolean result =
        BinaryContentEquality.contentEquals(
            new byte[] {1, 2, 3}, "image/png", new byte[] {9, 9, 9}, "image/png");

    assertThat(result).isFalse();
  }

  @Test
  void contentEqualsIsFalseWhenContentTypeDiffers() {
    boolean result =
        BinaryContentEquality.contentEquals(
            new byte[] {1, 2, 3}, "image/png", new byte[] {1, 2, 3}, "image/webp");

    assertThat(result).isFalse();
  }

  @Test
  void contentHashCodeIsConsistentForEqualContent() {
    int first = BinaryContentEquality.contentHashCode(new byte[] {1, 2, 3}, "image/png");
    int second = BinaryContentEquality.contentHashCode(new byte[] {1, 2, 3}, "image/png");

    assertThat(first).isEqualTo(second);
  }

  @Test
  void describeContentLengthPrintsTheLengthNotTheRawBytes() {
    String result = BinaryContentEquality.describeContentLength(new byte[] {1, 2, 3});

    assertThat(result).isEqualTo("byte[3]").doesNotContain("1, 2, 3");
  }

  @Test
  void describeContentLengthHandlesAnEmptyArray() {
    String result = BinaryContentEquality.describeContentLength(new byte[0]);

    assertThat(result).isEqualTo("byte[0]");
  }
}
