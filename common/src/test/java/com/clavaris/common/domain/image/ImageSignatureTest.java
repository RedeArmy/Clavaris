package com.clavaris.common.domain.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** TD-SEC-096: the first bytes of a file must be those of the type it claims. */
class ImageSignatureTest {

  private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0};
  private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
  private static final byte[] GIF = "GIF89a".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] WEBP = "RIFF\0\0\0\0WEBPVP8L".getBytes(StandardCharsets.US_ASCII);

  @Test
  void acceptsEachTypeWithItsOwnSignature() {
    assertThat(ImageSignature.matches(ImageSignature.PNG, PNG)).isTrue();
    assertThat(ImageSignature.matches(ImageSignature.JPEG, JPEG)).isTrue();
    assertThat(ImageSignature.matches(ImageSignature.GIF, GIF)).isTrue();
    assertThat(ImageSignature.matches(ImageSignature.WEBP, WEBP)).isTrue();
  }

  @Test
  void refusesAFileOfOneTypeDeclaredAsAnother() {
    assertThat(ImageSignature.matches(ImageSignature.PNG, JPEG)).isFalse();
    assertThat(ImageSignature.matches(ImageSignature.JPEG, GIF)).isFalse();
    assertThat(ImageSignature.matches(ImageSignature.GIF, PNG)).isFalse();
    assertThat(ImageSignature.matches(ImageSignature.WEBP, PNG)).isFalse();
  }

  @Test
  void refusesARiffFileThatIsNotWebp() {
    final byte[] wave = "RIFF\0\0\0\0WAVEfmt ".getBytes(StandardCharsets.US_ASCII);

    assertThat(ImageSignature.matches(ImageSignature.WEBP, wave)).isFalse();
  }

  @Test
  void refusesAFileTooShortToHoldTheSignature() {
    assertThat(ImageSignature.matches(ImageSignature.PNG, new byte[] {(byte) 0x89, 'P'})).isFalse();
    assertThat(
            ImageSignature.matches(ImageSignature.WEBP, "RIFF".getBytes(StandardCharsets.US_ASCII)))
        .isFalse();
    assertThat(ImageSignature.matches(ImageSignature.PNG, new byte[0])).isFalse();
  }

  @Test
  void refusesNullAndUnsupportedTypes() {
    assertThat(ImageSignature.matches(ImageSignature.PNG, null)).isFalse();
    assertThat(ImageSignature.matches("image/svg+xml", PNG)).isFalse();
    assertThat(ImageSignature.matches(null, PNG)).isFalse();
  }

  @Test
  void knowsWhichTypesItCanCheck() {
    assertThat(ImageSignature.isSupported(ImageSignature.PNG)).isTrue();
    assertThat(ImageSignature.isSupported(ImageSignature.JPEG)).isTrue();
    assertThat(ImageSignature.isSupported(ImageSignature.GIF)).isTrue();
    assertThat(ImageSignature.isSupported(ImageSignature.WEBP)).isTrue();
    assertThat(ImageSignature.isSupported("image/svg+xml")).isFalse();
    assertThat(ImageSignature.isSupported(null)).isFalse();
  }
}
