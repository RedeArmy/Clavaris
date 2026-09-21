package com.clavaris.identity.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProfilePictureReferenceTest {

  @Test
  void recognizesAnHttpsUrlAsExternal() {
    assertThat(ProfilePictureReference.isExternalUrl("https://provider.example.com/avatar.png"))
        .isTrue();
  }

  @Test
  void recognizesAnHttpUrlAsExternal() {
    assertThat(ProfilePictureReference.isExternalUrl("http://provider.example.com/avatar.png"))
        .isTrue();
  }

  @Test
  void aStorageKeyIsNotExternal() {
    assertThat(ProfilePictureReference.isExternalUrl("avatars/" + java.util.UUID.randomUUID()))
        .isFalse();
  }
}
