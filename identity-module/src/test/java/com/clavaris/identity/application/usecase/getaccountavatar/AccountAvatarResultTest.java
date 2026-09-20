package com.clavaris.identity.application.usecase.getaccountavatar;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AccountAvatarResultTest {

  @Test
  void contentIsEqualToAnotherInstanceWithTheSameBytesAndContentType() {
    AccountAvatarResult.Content first =
        new AccountAvatarResult.Content(new byte[] {1, 2, 3}, "image/png");
    AccountAvatarResult.Content second =
        new AccountAvatarResult.Content(new byte[] {1, 2, 3}, "image/png");

    assertThat(first).isEqualTo(second);
    assertThat(first).hasSameHashCodeAs(second);
  }

  @Test
  void contentIsNotEqualWhenBytesDiffer() {
    AccountAvatarResult.Content first =
        new AccountAvatarResult.Content(new byte[] {1, 2, 3}, "image/png");
    AccountAvatarResult.Content second =
        new AccountAvatarResult.Content(new byte[] {1, 2, 4}, "image/png");

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  void contentIsNotEqualWhenContentTypeDiffers() {
    AccountAvatarResult.Content first =
        new AccountAvatarResult.Content(new byte[] {1, 2, 3}, "image/png");
    AccountAvatarResult.Content second =
        new AccountAvatarResult.Content(new byte[] {1, 2, 3}, "image/webp");

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  void contentIsNotEqualToNullOrADifferentType() {
    AccountAvatarResult.Content content =
        new AccountAvatarResult.Content(new byte[] {1}, "image/png");

    assertThat(content).isNotEqualTo(null).isNotEqualTo("not a Content").isEqualTo(content);
  }

  @Test
  void toStringPrintsTheByteLengthNotTheRawBytes() {
    AccountAvatarResult.Content content =
        new AccountAvatarResult.Content(new byte[] {1, 2, 3}, "image/png");

    assertThat(content.toString())
        .contains("byte[3]")
        .contains("image/png")
        .doesNotContain("1, 2, 3");
  }

  @Test
  void redirectIsARealRecordEqualsCase() {
    AccountAvatarResult.Redirect first =
        new AccountAvatarResult.Redirect("https://example.com/a.png");
    AccountAvatarResult.Redirect second =
        new AccountAvatarResult.Redirect("https://example.com/a.png");

    assertThat(first).isEqualTo(second);
  }
}
