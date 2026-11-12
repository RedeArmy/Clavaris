package com.clavaris.identity.application.usecase.updateaccountprofilepicture;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.clavaris.identity.support.ImageFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * TD-SEC-096: a picture is judged by its bytes and by the size its header declares, not by the
 * {@code Content-Type} the client wrote.
 */
class ProfilePictureValidatorTest {

  @Test
  void acceptsARealPictureOfEachAllowedType() {
    assertThatCode(
            () -> {
              ProfilePictureValidator.validate(ImageFixtures.png(), "image/png");
              ProfilePictureValidator.validate(ImageFixtures.jpeg(), "image/jpeg");
              ProfilePictureValidator.validate(ImageFixtures.gif(1, 1), "image/gif");
              ProfilePictureValidator.validate(ImageFixtures.webp(), "image/webp");
            })
        .doesNotThrowAnyException();
  }

  @Test
  void refusesAFileWhoseBytesAreNotTheTypeItClaims() {
    final byte[] script =
        "<script>alert(1)</script>".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    assertThatExceptionOfType(InvalidProfilePictureException.class)
        .isThrownBy(() -> ProfilePictureValidator.validate(script, "image/png"));
  }

  @Test
  void refusesAJpegDeclaredAsPng() {
    assertThatExceptionOfType(InvalidProfilePictureException.class)
        .isThrownBy(() -> ProfilePictureValidator.validate(ImageFixtures.jpeg(), "image/png"));
  }

  @Test
  void refusesAnImageWhoseHeaderIsTruncated() {
    final byte[] truncated = java.util.Arrays.copyOf(ImageFixtures.png(), 12);

    assertThatExceptionOfType(InvalidProfilePictureException.class)
        .isThrownBy(() -> ProfilePictureValidator.validate(truncated, "image/png"));
  }

  @ParameterizedTest(name = "{0} x {1} is accepted: {2}")
  @CsvSource({
    "10000, 8000, true", // exactly at the pixel budget (80 MP) and the side limit
    "8000, 6000, true", // a 48 MP phone photo
    "10001, 100, false", // one pixel over the side limit
    "100, 10001, false",
    "9000, 9000, false", // 81 MP: each side fine, the area is not
    "65535, 65535, false" // a few KB that ask a decoder for four billion pixels
  })
  void capsTheDeclaredDimensions(final int width, final int height, final boolean accepted) {
    final byte[] png = ImageFixtures.png(width, height);

    if (accepted) {
      assertThatCode(() -> ProfilePictureValidator.validate(png, "image/png"))
          .doesNotThrowAnyException();
    } else {
      assertThatExceptionOfType(InvalidProfilePictureException.class)
          .isThrownBy(() -> ProfilePictureValidator.validate(png, "image/png"));
    }
  }

  @Test
  void refusesAnImageThatDeclaresNoSize() {
    assertThatExceptionOfType(InvalidProfilePictureException.class)
        .isThrownBy(() -> ProfilePictureValidator.validate(ImageFixtures.png(0, 0), "image/png"));
  }
}
