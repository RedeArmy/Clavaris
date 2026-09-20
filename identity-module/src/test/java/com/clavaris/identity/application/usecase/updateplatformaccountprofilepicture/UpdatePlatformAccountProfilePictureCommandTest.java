package com.clavaris.identity.application.usecase.updateplatformaccountprofilepicture;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.identity.domain.model.PlatformAccountId;
import org.junit.jupiter.api.Test;

class UpdatePlatformAccountProfilePictureCommandTest {

  private static final PlatformAccountId PLATFORM_ACCOUNT_ID = PlatformAccountId.newId();

  @Test
  void isEqualToAnotherInstanceWithTheSameFieldsIncludingBytes() {
    UpdatePlatformAccountProfilePictureCommand first =
        new UpdatePlatformAccountProfilePictureCommand(
            PLATFORM_ACCOUNT_ID, new byte[] {1, 2, 3}, "image/png");
    UpdatePlatformAccountProfilePictureCommand second =
        new UpdatePlatformAccountProfilePictureCommand(
            PLATFORM_ACCOUNT_ID, new byte[] {1, 2, 3}, "image/png");

    assertThat(first).isEqualTo(second);
    assertThat(first).hasSameHashCodeAs(second);
  }

  @Test
  void isNotEqualWhenBytesDiffer() {
    UpdatePlatformAccountProfilePictureCommand first =
        new UpdatePlatformAccountProfilePictureCommand(
            PLATFORM_ACCOUNT_ID, new byte[] {1, 2, 3}, "image/png");
    UpdatePlatformAccountProfilePictureCommand second =
        new UpdatePlatformAccountProfilePictureCommand(
            PLATFORM_ACCOUNT_ID, new byte[] {9, 9, 9}, "image/png");

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  void isNotEqualWhenPlatformAccountIdOrContentTypeDiffer() {
    UpdatePlatformAccountProfilePictureCommand base =
        new UpdatePlatformAccountProfilePictureCommand(
            PLATFORM_ACCOUNT_ID, new byte[] {1}, "image/png");

    assertThat(base)
        .isNotEqualTo(
            new UpdatePlatformAccountProfilePictureCommand(
                PlatformAccountId.newId(), new byte[] {1}, "image/png"))
        .isNotEqualTo(
            new UpdatePlatformAccountProfilePictureCommand(
                PLATFORM_ACCOUNT_ID, new byte[] {1}, "image/webp"));
  }

  @Test
  void isNotEqualToNullOrADifferentType() {
    UpdatePlatformAccountProfilePictureCommand command =
        new UpdatePlatformAccountProfilePictureCommand(
            PLATFORM_ACCOUNT_ID, new byte[] {1}, "image/png");

    assertThat(command).isNotEqualTo(null).isNotEqualTo("not a command").isEqualTo(command);
  }

  @Test
  void toStringPrintsTheByteLengthNotTheRawBytes() {
    UpdatePlatformAccountProfilePictureCommand command =
        new UpdatePlatformAccountProfilePictureCommand(
            PLATFORM_ACCOUNT_ID, new byte[] {1, 2, 3}, "image/png");

    assertThat(command.toString())
        .contains("byte[3]")
        .contains("image/png")
        .doesNotContain("1, 2, 3");
  }
}
