package com.clavaris.common.domain.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class WebpMetadataTest {

  private static byte[] chunk(final String type, final byte[] data) {
    final int padded = data.length + (data.length & 1);
    final ByteBuffer b = ByteBuffer.allocate(8 + padded).order(ByteOrder.LITTLE_ENDIAN);
    b.put(type.getBytes(StandardCharsets.US_ASCII)).putInt(data.length).put(data);
    return b.array();
  }

  private static byte[] vp8x(final int flags) {
    return chunk("VP8X", MetadataFixtures.bytes(flags, 0, 0, 0, 7, 0, 0, 7, 0, 0));
  }

  private static byte[] riff(final byte[]... chunks) {
    final byte[] body = MetadataFixtures.concat(chunks);
    final ByteBuffer b = ByteBuffer.allocate(12 + body.length).order(ByteOrder.LITTLE_ENDIAN);
    b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(4 + body.length);
    b.put("WEBP".getBytes(StandardCharsets.US_ASCII)).put(body);
    return b.array();
  }

  private static byte[] image() {
    return chunk("VP8L", MetadataFixtures.bytes(0x2F, 7, 0xC0, 1, 0, 0, 0, 0, 0));
  }

  private static byte[] exif(final int orientation, final boolean withPrefix) {
    final byte[] tiff = MetadataFixtures.tiff(orientation);
    return chunk(
        "EXIF",
        withPrefix
            ? MetadataFixtures.concat("Exif\0\0".getBytes(StandardCharsets.US_ASCII), tiff)
            : tiff);
  }

  private static long riffSize(final byte[] webp) {
    return ByteBuffer.wrap(webp, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
  }

  @Test
  void removesExifAndXmpAndClearsTheirFlags() {
    final byte[] dirty =
        riff(
            vp8x(0x04 | 0x08 | 0x10),
            image(),
            exif(1, false),
            chunk("XMP ", MetadataFixtures.SECRET.getBytes(StandardCharsets.US_ASCII)));

    final byte[] stripped = WebpMetadata.strip(dirty).orElseThrow();

    assertThat(MetadataFixtures.contains(dirty, MetadataFixtures.SECRET)).isTrue();
    assertThat(MetadataFixtures.contains(stripped, MetadataFixtures.SECRET)).isFalse();
    assertThat(MetadataFixtures.contains(stripped, "EXIF")).isFalse();
    assertThat(MetadataFixtures.contains(stripped, "XMP ")).isFalse();
    assertThat(stripped[20]).isEqualTo((byte) 0x10); // only the alpha flag is left
    assertThat(riffSize(stripped)).isEqualTo(stripped.length - 8);
  }

  @Test
  void keepsOnlyTheOrientationOfAPhotoThatIsNotUpright() {
    for (final boolean prefixed : new boolean[] {false, true}) {
      final byte[] dirty = riff(vp8x(0x08), image(), exif(6, prefixed));

      final byte[] stripped = WebpMetadata.strip(dirty).orElseThrow();

      assertThat(MetadataFixtures.contains(stripped, MetadataFixtures.SECRET)).isFalse();
      assertThat(stripped[20] & 0x08).isEqualTo(0x08);
      final int at = new String(stripped, StandardCharsets.ISO_8859_1).indexOf("EXIF");
      assertThat(ExifOrientation.read(Arrays.copyOfRange(stripped, at + 8, at + 8 + 26)))
          .isEqualTo(6);
      assertThat(riffSize(stripped)).isEqualTo(stripped.length - 8);
    }
  }

  @Test
  void aFileWithoutTheExtendedHeaderIsKeptAsItIs() {
    final byte[] simple = riff(image());

    assertThat(WebpMetadata.strip(simple).orElseThrow()).isEqualTo(simple);
  }

  @Test
  void anExifChunkInAFileWithoutTheExtendedHeaderIsDroppedWithoutRewritingTheOrientation() {
    final byte[] odd = riff(image(), exif(6, false));

    final byte[] stripped = WebpMetadata.strip(odd).orElseThrow();

    assertThat(MetadataFixtures.contains(stripped, MetadataFixtures.SECRET)).isFalse();
    assertThat(MetadataFixtures.contains(stripped, "EXIF")).isFalse();
  }

  @Test
  void refusesAFileWhoseChunksOverrun() {
    final byte[] overrun = riff(chunk("VP8L", new byte[8]));
    overrun[16] = (byte) 0x7F; // the chunk now claims far more than the file holds
    final byte[] notWebp = "RIFF....WAVEfmt ".getBytes(StandardCharsets.US_ASCII);

    assertThat(WebpMetadata.strip(overrun)).isEmpty();
    assertThat(WebpMetadata.strip(notWebp)).isEmpty();
  }

  @Test
  void ignoresBytesBeyondTheDeclaredSize() {
    final byte[] clean = riff(image());
    final byte[] padded =
        MetadataFixtures.concat(clean, "TRAILING".getBytes(StandardCharsets.US_ASCII));

    final byte[] stripped = WebpMetadata.strip(padded).orElseThrow();

    assertThat(stripped).isEqualTo(clean);
  }
}
