package com.clavaris.organization.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The logo is shown to the public from the platform's own origin, so what is accepted is narrow: a
 * file that really is one of four image formats, whatever it says it is, and no SVG.
 */
class OrganizationLogoTest {

  private final UUID organizationId = UUID.randomUUID();

  private static byte[] png() {
    return withBody(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
  }

  private static byte[] jpeg() {
    return withBody(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0});
  }

  private static byte[] gif() {
    return withBody("GIF89a".getBytes(StandardCharsets.US_ASCII));
  }

  private static byte[] webp() {
    return withBody("RIFF\0\0\0\0WEBP".getBytes(StandardCharsets.ISO_8859_1));
  }

  private static byte[] withBody(final byte[] signature) {
    final byte[] bytes = Arrays.copyOf(signature, signature.length + 16);
    Arrays.fill(bytes, signature.length, bytes.length, (byte) 7);
    return bytes;
  }

  @Test
  void aRealImageOfEachAcceptedTypeIsAccepted() {
    assertThat(OrganizationLogo.of(organizationId, "image/png", png()).contentType())
        .isEqualTo("image/png");
    assertThat(OrganizationLogo.of(organizationId, "image/jpeg", jpeg()).contentType())
        .isEqualTo("image/jpeg");
    assertThat(OrganizationLogo.of(organizationId, "image/gif", gif()).contentType())
        .isEqualTo("image/gif");
    assertThat(OrganizationLogo.of(organizationId, "image/webp", webp()).contentType())
        .isEqualTo("image/webp");
  }

  @Test
  void theDeclaredTypeIsNormalised() {
    assertThat(
            OrganizationLogo.of(organizationId, " IMAGE/PNG; charset=binary ", png()).contentType())
        .isEqualTo("image/png");
  }

  // The declared type is not trusted: the bytes have to be of the type they claim.
  @Test
  void aFileThatIsNotWhatItClaimsToBeIsRefused() {
    assertThatThrownBy(() -> OrganizationLogo.of(organizationId, "image/png", jpeg()))
        .hasMessageContaining("not a valid image/png");
    assertThatThrownBy(
            () ->
                OrganizationLogo.of(
                    organizationId,
                    "image/png",
                    "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> OrganizationLogo.of(organizationId, "image/webp", png()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // An SVG can carry script and this is served to the public: never accepted, even when it is a
  // perfectly good image.
  @Test
  void anSvgIsNeverAccepted() {
    final byte[] svg =
        "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() -> OrganizationLogo.of(organizationId, "image/svg+xml", svg))
        .hasMessageContaining("PNG, JPEG, WebP or GIF");
  }

  @Test
  void otherTypesAreRefused() {
    assertThatThrownBy(() -> OrganizationLogo.of(organizationId, "text/html", png()))
        .hasMessageContaining("PNG, JPEG, WebP or GIF");
    assertThatThrownBy(() -> OrganizationLogo.of(organizationId, null, png()))
        .hasMessageContaining("PNG, JPEG, WebP or GIF");
    assertThatThrownBy(() -> OrganizationLogo.of(organizationId, "", png()))
        .hasMessageContaining("PNG, JPEG, WebP or GIF");
  }

  @Test
  void anEmptyOrOversizedFileIsRefused() {
    assertThatThrownBy(() -> OrganizationLogo.of(organizationId, "image/png", new byte[0]))
        .hasMessageContaining("empty");
    assertThatThrownBy(() -> OrganizationLogo.of(organizationId, "image/png", null))
        .hasMessageContaining("empty");

    final byte[] atTheLimit = Arrays.copyOf(png(), OrganizationLogo.MAX_BYTES);
    assertThat(OrganizationLogo.of(organizationId, "image/png", atTheLimit).content())
        .hasSize(OrganizationLogo.MAX_BYTES);
    assertThatThrownBy(
            () ->
                OrganizationLogo.of(
                    organizationId,
                    "image/png",
                    Arrays.copyOf(png(), OrganizationLogo.MAX_BYTES + 1)))
        .hasMessageContaining("1 MB");
  }

  @Test
  void aTruncatedFileIsRefusedRatherThanFailingOnTheSignature() {
    assertThatThrownBy(
            () -> OrganizationLogo.of(organizationId, "image/png", new byte[] {(byte) 0x89, 'P'}))
        .hasMessageContaining("not a valid");
    assertThatThrownBy(
            () ->
                OrganizationLogo.of(
                    organizationId, "image/webp", "RIFF".getBytes(StandardCharsets.US_ASCII)))
        .hasMessageContaining("not a valid");
  }

  @Test
  void theBytesAreCopiedSoTheCallerCannotChangeAStoredLogo() {
    final byte[] original = png();
    final OrganizationLogo logo = OrganizationLogo.of(organizationId, "image/png", original);

    original[0] = 0;
    logo.content()[1] = 0;

    assertThat(logo.content()[0]).isEqualTo((byte) 0x89);
    assertThat(logo.content()[1]).isEqualTo((byte) 'P');
  }
}
