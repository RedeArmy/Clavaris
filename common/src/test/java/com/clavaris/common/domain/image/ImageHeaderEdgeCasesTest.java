package com.clavaris.common.domain.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * TD-SEC-096: the header parsers are fed whatever a client uploads, so every way a header can be
 * short, wrong or hostile has to end in "no size", never in an exception or a wrong size.
 */
class ImageHeaderEdgeCasesTest {

  private static byte[] bytes(final int... values) {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (final int value : values) {
      out.write(value);
    }
    return out.toByteArray();
  }

  private static byte[] webpPrefix(final String chunk, final int length) {
    final byte[] result = new byte[length];
    final byte[] head = ("RIFF\0\0\0\0WEBP" + chunk).getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(head, 0, result, 0, head.length);
    return result;
  }

  // ---- PNG and GIF ----------------------------------------------------------------------------

  @Test
  void aPngWithoutIhdrFirstHasNoSize() {
    final byte[] notIhdr = new byte[33];
    System.arraycopy(
        "\u0089PNG\r\n\u001a\n".getBytes(StandardCharsets.ISO_8859_1), 0, notIhdr, 0, 8);
    System.arraycopy("IDAT".getBytes(StandardCharsets.US_ASCII), 0, notIhdr, 12, 4);

    assertThat(ImageDimensions.read(ImageSignature.PNG, notIhdr)).isEmpty();
  }

  @Test
  void aTruncatedGifHasNoSize() {
    assertThat(
            ImageDimensions.read(ImageSignature.GIF, "GIF89a".getBytes(StandardCharsets.US_ASCII)))
        .isEmpty();
  }

  @Test
  void anUnsupportedTypeHasNoSize() {
    assertThat(ImageDimensions.read("image/svg+xml", new byte[40])).isEmpty();
  }

  // ---- JPEG -----------------------------------------------------------------------------------

  @Test
  void aJpegSkipsAHuffmanTableAndFillBytesBeforeTheFrame() {
    final byte[] jpeg =
        bytes(
            0xFF, 0xD8, // SOI
            0xFF, 0xFF, // a fill byte
            0xFF, 0xC4, 0x00, 0x04, 0x00, 0x00, // DHT: carries a length but is not a frame
            0xFF, 0xC0, 0x00, 0x08, 0x08, 0x00, 0x20, 0x00, 0x40); // SOF0: 64 x 32

    assertThat(ImageDimensions.read(ImageSignature.JPEG, jpeg))
        .contains(new ImageDimensions.Size(64, 32));
  }

  @Test
  void aJpegSkipsMarkersThatCarryNoPayload() {
    final byte[] jpeg =
        bytes(
            0xFF, 0xD8, //
            0xFF, 0x01, // TEM
            0xFF, 0xD0, // RST0
            0xFF, 0xC0, 0x00, 0x08, 0x08, 0x00, 0x0A, 0x00, 0x14); // 20 x 10

    assertThat(ImageDimensions.read(ImageSignature.JPEG, jpeg))
        .contains(new ImageDimensions.Size(20, 10));
  }

  @Test
  void aJpegThatLosesItsMarkerPrefixHasNoSize() {
    assertThat(ImageDimensions.read(ImageSignature.JPEG, bytes(0xFF, 0xD8, 0x00, 0x00, 0x00, 0x00)))
        .isEmpty();
  }

  @Test
  void aJpegSegmentWithAnImpossibleLengthHasNoSize() {
    assertThat(
            ImageDimensions.read(
                ImageSignature.JPEG, bytes(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x01, 0x00, 0x00)))
        .isEmpty();
  }

  @Test
  void aJpegCutOffInsideItsFrameHeaderHasNoSize() {
    assertThat(
            ImageDimensions.read(
                ImageSignature.JPEG, bytes(0xFF, 0xD8, 0xFF, 0xC0, 0x00, 0x08, 0x08, 0x00)))
        .isEmpty();
  }

  @Test
  void aJpegThatEndsBeforeAnyFrameHasNoSize() {
    assertThat(
            ImageDimensions.read(
                ImageSignature.JPEG, bytes(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x04, 0x00, 0x00)))
        .isEmpty();
  }

  // ---- WebP -----------------------------------------------------------------------------------

  @Test
  void aTruncatedWebpHasNoSize() {
    assertThat(ImageDimensions.read(ImageSignature.WEBP, webpPrefix("VP8L", 20))).isEmpty();
  }

  @Test
  void aWebpWithAnUnknownFirstChunkHasNoSize() {
    assertThat(ImageDimensions.read(ImageSignature.WEBP, webpPrefix("ABCD", 40))).isEmpty();
  }

  @Test
  void aLossyWebpNeedsItsStartCode() {
    final byte[] wrong = webpPrefix("VP8 ", 40);

    assertThat(ImageDimensions.read(ImageSignature.WEBP, wrong)).isEmpty();
  }

  @Test
  void aLossyWebpWithItsStartCodeYieldsItsSize() {
    final byte[] lossy = webpPrefix("VP8 ", 40);
    lossy[23] = (byte) 0x9D;
    lossy[24] = 0x01;
    lossy[25] = 0x2A;
    lossy[26] = 0x40; // 64
    lossy[28] = 0x20; // 32

    assertThat(ImageDimensions.read(ImageSignature.WEBP, lossy))
        .contains(new ImageDimensions.Size(64, 32));
  }

  @Test
  void aLosslessWebpNeedsItsSignatureByte() {
    final byte[] wrong = webpPrefix("VP8L", 40);

    assertThat(ImageDimensions.read(ImageSignature.WEBP, wrong)).isEmpty();
  }

  @Test
  void anExtendedWebpYieldsItsCanvas() {
    final byte[] extended = webpPrefix("VP8X", 40);
    extended[24] = 0x3F; // width - 1 = 63
    extended[27] = 0x1F; // height - 1 = 31

    assertThat(ImageDimensions.read(ImageSignature.WEBP, extended))
        .contains(new ImageDimensions.Size(64, 32));
  }

  // ---- the remaining branches -----------------------------------------------------------------

  @Test
  void aSizeIsEmptyWhenEitherSideIsZeroAndMultipliesToItsArea() {
    final byte[] noWidth =
        bytes(
            0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R', 0, 0,
            0, 0, 0, 0, 0, 5);
    final byte[] noHeight =
        bytes(
            0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R', 0, 0,
            0, 5, 0, 0, 0, 0);

    assertThat(ImageDimensions.read(ImageSignature.PNG, noWidth)).isEmpty();
    assertThat(ImageDimensions.read(ImageSignature.PNG, noHeight)).isEmpty();
    assertThat(new ImageDimensions.Size(6, 7).pixels()).isEqualTo(42);
  }

  @Test
  void aJpegSkipsAMarkerBelowTheFrameRange() {
    final byte[] jpeg =
        bytes(
            0xFF, 0xD8, 0xFF, 0xBB, 0x00, 0x04, 0x00, 0x00, 0xFF, 0xC0, 0x00, 0x08, 0x08, 0x00,
            0x0A, 0x00, 0x14);

    assertThat(ImageDimensions.read(ImageSignature.JPEG, jpeg))
        .contains(new ImageDimensions.Size(20, 10));
  }

  @Test
  void aLossyWebpIsRefusedWhenOnlyPartOfItsStartCodeMatches() {
    final byte[] secondByteWrong = webpPrefix("VP8 ", 40);
    secondByteWrong[23] = (byte) 0x9D;
    final byte[] thirdByteWrong = webpPrefix("VP8 ", 40);
    thirdByteWrong[23] = (byte) 0x9D;
    thirdByteWrong[24] = 0x01;

    assertThat(ImageDimensions.read(ImageSignature.WEBP, secondByteWrong)).isEmpty();
    assertThat(ImageDimensions.read(ImageSignature.WEBP, thirdByteWrong)).isEmpty();
  }

  @Test
  void aRiffFileWithWebpButNotRiffAtTheStartIsNotWebp() {
    final byte[] notRiff = "XXXX\0\0\0\0WEBPVP8L".getBytes(StandardCharsets.US_ASCII);

    assertThat(ImageSignature.matches(ImageSignature.WEBP, notRiff)).isFalse();
  }
}
