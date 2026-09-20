package com.clavaris.identity.application.usecase.getplatformaccountavatar;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PlatformAccountAvatarResultTest {

  @Test
  void contentIsEqualToAnotherInstanceWithTheSameBytesAndContentType() {
    PlatformAccountAvatarResult.Content first =
        new PlatformAccountAvatarResult.Content(new byte[] {1, 2, 3}, "image/png");
    PlatformAccountAvatarResult.Content second =
        new PlatformAccountAvatarResult.Content(new byte[] {1, 2, 3}, "image/png");

    assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
  }

  @Test
  void contentIsNotEqualWhenBytesDiffer() {
    PlatformAccountAvatarResult.Content first =
        new PlatformAccountAvatarResult.Content(new byte[] {1, 2, 3}, "image/png");
    PlatformAccountAvatarResult.Content second =
        new PlatformAccountAvatarResult.Content(new byte[] {1, 2, 4}, "image/png");

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  void contentIsNotEqualWhenContentTypeDiffers() {
    PlatformAccountAvatarResult.Content first =
        new PlatformAccountAvatarResult.Content(new byte[] {1, 2, 3}, "image/png");
    PlatformAccountAvatarResult.Content second =
        new PlatformAccountAvatarResult.Content(new byte[] {1, 2, 3}, "image/webp");

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  void contentIsNotEqualToNullOrADifferentType() {
    PlatformAccountAvatarResult.Content content =
        new PlatformAccountAvatarResult.Content(new byte[] {1}, "image/png");

    assertThat(content).isNotEqualTo(null).isNotEqualTo("not a Content").isEqualTo(content);
  }

  @Test
  void toStringPrintsTheByteLengthNotTheRawBytes() {
    PlatformAccountAvatarResult.Content content =
        new PlatformAccountAvatarResult.Content(new byte[] {1, 2, 3}, "image/png");

    assertThat(content.toString())
        .contains("byte[3]")
        .contains("image/png")
        .doesNotContain("1, 2, 3");
  }

  @Test
  void redirectIsARealRecordEqualsCase() {
    PlatformAccountAvatarResult.Redirect first =
        new PlatformAccountAvatarResult.Redirect("https://example.com/a.png");
    PlatformAccountAvatarResult.Redirect second =
        new PlatformAccountAvatarResult.Redirect("https://example.com/a.png");

    assertThat(first).isEqualTo(second);
  }
}
