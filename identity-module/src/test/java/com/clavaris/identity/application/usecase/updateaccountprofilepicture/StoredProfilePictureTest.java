package com.clavaris.identity.application.usecase.updateaccountprofilepicture;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StoredProfilePictureTest {

  @Test
  void isEqualToAnotherInstanceWithTheSameBytesAndContentType() {
    StoredProfilePicture first = new StoredProfilePicture(new byte[] {1, 2, 3}, "image/png");
    StoredProfilePicture second = new StoredProfilePicture(new byte[] {1, 2, 3}, "image/png");

    assertThat(first).isEqualTo(second);
    assertThat(first.hashCode()).isEqualTo(second.hashCode());
  }

  @Test
  void isNotEqualWhenBytesDiffer() {
    StoredProfilePicture first = new StoredProfilePicture(new byte[] {1, 2, 3}, "image/png");
    StoredProfilePicture second = new StoredProfilePicture(new byte[] {1, 2, 4}, "image/png");

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  void isNotEqualWhenContentTypeDiffers() {
    StoredProfilePicture first = new StoredProfilePicture(new byte[] {1, 2, 3}, "image/png");
    StoredProfilePicture second = new StoredProfilePicture(new byte[] {1, 2, 3}, "image/webp");

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  void isNotEqualToNullOrADifferentType() {
    StoredProfilePicture picture = new StoredProfilePicture(new byte[] {1}, "image/png");

    assertThat(picture)
        .isNotEqualTo(null)
        .isNotEqualTo("not a StoredProfilePicture")
        .isEqualTo(picture);
  }

  @Test
  void toStringPrintsTheByteLengthNotTheRawBytes() {
    StoredProfilePicture picture = new StoredProfilePicture(new byte[] {1, 2, 3}, "image/png");

    assertThat(picture.toString())
        .contains("byte[3]")
        .contains("image/png")
        .doesNotContain("1, 2, 3");
  }
}
