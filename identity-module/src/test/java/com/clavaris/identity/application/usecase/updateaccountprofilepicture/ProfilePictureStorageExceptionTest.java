package com.clavaris.identity.application.usecase.updateaccountprofilepicture;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProfilePictureStorageExceptionTest {

  @Test
  void preservesTheMessageAndCause() {
    RuntimeException cause = new RuntimeException("S3 unreachable");

    ProfilePictureStorageException exception =
        new ProfilePictureStorageException("Failed to upload profile picture", cause);

    assertThat(exception.getMessage()).isEqualTo("Failed to upload profile picture");
    assertThat(exception.getCause()).isSameAs(cause);
  }
}
