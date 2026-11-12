package com.clavaris.common.domain.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class JpegMetadataTest {

  private static byte[] withInjected(final byte[] jpeg, final byte[]... segments) {
    // after SOI, before everything else
    return MetadataFixtures.concat(
        Arrays.copyOfRange(jpeg, 0, 2),
        MetadataFixtures.concat(segments),
        Arrays.copyOfRange(jpeg, 2, jpeg.length));
  }

  private static BufferedImage decode(final byte[] jpeg) throws IOException {
    return ImageIO.read(new ByteArrayInputStream(jpeg));
  }

  @Test
  void removesExifXmpIptcCommentsAndWhatIsAppendedAfterTheImage() throws IOException {
    final byte[] clean = MetadataFixtures.pixels("jpeg", BufferedImage.TYPE_INT_RGB);
    final byte[] dirty =
        MetadataFixtures.concat(
            withInjected(
                clean,
                MetadataFixtures.exifSegment(1),
                MetadataFixtures.jpegSegment(
                    0xE1,
                    "http://ns.adobe.com/xap/1.0/\0<x:xmpmeta>SECRET-GPS-4.8N-9.4W"
                        .getBytes(StandardCharsets.US_ASCII)),
                MetadataFixtures.jpegSegment(
                    0xED,
                    "Photoshop 3.0\0SECRET-GPS-4.8N-9.4W".getBytes(StandardCharsets.US_ASCII)),
                MetadataFixtures.jpegSegment(
                    0xFE, "SECRET-GPS-4.8N-9.4W".getBytes(StandardCharsets.US_ASCII))),
            "SECRET-GPS-4.8N-9.4W trailing motion-photo video".getBytes(StandardCharsets.US_ASCII));

    final byte[] stripped = JpegMetadata.strip(dirty).orElseThrow();

    assertThat(MetadataFixtures.contains(dirty, MetadataFixtures.SECRET)).isTrue();
    assertThat(MetadataFixtures.contains(stripped, MetadataFixtures.SECRET)).isFalse();
    assertThat(MetadataFixtures.contains(stripped, "Exif")).isFalse();
    assertThat(decode(stripped).getRGB(3, 3)).isEqualTo(decode(clean).getRGB(3, 3));
    assertThat(stripped).endsWith((byte) 0xFF, (byte) 0xD9);
  }

  @Test
  void keepsOnlyTheOrientationOfAPhotoThatIsNotUpright() throws IOException {
    final byte[] clean = MetadataFixtures.pixels("jpeg", BufferedImage.TYPE_INT_RGB);
    final byte[] dirty = withInjected(clean, MetadataFixtures.exifSegment(6));

    final byte[] stripped = JpegMetadata.strip(dirty).orElseThrow();

    assertThat(MetadataFixtures.contains(stripped, MetadataFixtures.SECRET)).isFalse();
    assertThat(MetadataFixtures.contains(stripped, "Exif")).isTrue();
    final int exifAt = new String(stripped, StandardCharsets.ISO_8859_1).indexOf("Exif\0\0");
    assertThat(ExifOrientation.read(Arrays.copyOfRange(stripped, exifAt + 6, exifAt + 6 + 26)))
        .isEqualTo(6);
    assertThat(decode(stripped)).isNotNull();
  }

  @Test
  void writesNoExifBlockAtAllForAnUprightPhoto() throws IOException {
    final byte[] clean = MetadataFixtures.pixels("jpeg", BufferedImage.TYPE_INT_RGB);

    final byte[] stripped =
        JpegMetadata.strip(withInjected(clean, MetadataFixtures.exifSegment(1))).orElseThrow();

    assertThat(MetadataFixtures.contains(stripped, "Exif")).isFalse();
  }

  @Test
  void keepsJfifTheColourProfileAndAdobeButDropsTheMultiPictureIndex() {
    final byte[] jfif =
        MetadataFixtures.jpegSegment(
            0xE0, "JFIF\0\1\1\0\0\1\0\1\0\0".getBytes(StandardCharsets.ISO_8859_1));
    final byte[] icc =
        MetadataFixtures.jpegSegment(
            0xE2, "ICC_PROFILE\0profile-bytes".getBytes(StandardCharsets.US_ASCII));
    final byte[] mpf =
        MetadataFixtures.jpegSegment(
            0xE2, "MPF\0second-picture-index".getBytes(StandardCharsets.US_ASCII));
    final byte[] adobe =
        MetadataFixtures.jpegSegment(
            0xEE, "Adobe\0\144\0\0\0\0\1".getBytes(StandardCharsets.ISO_8859_1));
    final byte[] other =
        MetadataFixtures.jpegSegment(
            0xE5, "SECRET-GPS-4.8N-9.4W".getBytes(StandardCharsets.US_ASCII));
    final byte[] jpeg =
        MetadataFixtures.concat(
            MetadataFixtures.bytes(0xFF, 0xD8),
            jfif,
            icc,
            mpf,
            adobe,
            other,
            MetadataFixtures.bytes(0xFF, 0xDB, 0, 4, 0, 0),
            MetadataFixtures.bytes(0xFF, 0xDA, 0, 4, 0, 0, 1, 2, 3, 0xFF, 0x00, 4, 0xFF, 0xD0, 5),
            MetadataFixtures.bytes(0xFF, 0xD9));

    final byte[] stripped = JpegMetadata.strip(jpeg).orElseThrow();

    assertThat(MetadataFixtures.contains(stripped, "JFIF")).isTrue();
    assertThat(MetadataFixtures.contains(stripped, "ICC_PROFILE")).isTrue();
    assertThat(MetadataFixtures.contains(stripped, "Adobe")).isTrue();
    assertThat(MetadataFixtures.contains(stripped, "MPF")).isFalse();
    assertThat(MetadataFixtures.contains(stripped, MetadataFixtures.SECRET)).isFalse();
    assertThat(stripped).contains(MetadataFixtures.bytes(0xFF, 0xDB, 0, 4, 0, 0));
  }

  @Test
  void placesTheOrientationAfterJfif() {
    final byte[] jfif =
        MetadataFixtures.jpegSegment(
            0xE0, "JFIF\0\1\1\0\0\1\0\1\0\0".getBytes(StandardCharsets.ISO_8859_1));
    final byte[] jpeg =
        MetadataFixtures.concat(
            MetadataFixtures.bytes(0xFF, 0xD8),
            MetadataFixtures.exifSegment(8),
            jfif,
            MetadataFixtures.bytes(0xFF, 0xDA, 0, 4, 0, 0, 1, 0xFF, 0xD9));

    final byte[] stripped = JpegMetadata.strip(jpeg).orElseThrow();

    final String text = new String(stripped, StandardCharsets.ISO_8859_1);
    assertThat(text.indexOf("JFIF")).isLessThan(text.indexOf("Exif"));
  }

  @Test
  void aScanFollowedByMoreSegmentsIsKeptWholeUpToTheEnd() {
    final byte[] progressive =
        MetadataFixtures.concat(
            MetadataFixtures.bytes(0xFF, 0xD8),
            MetadataFixtures.bytes(0xFF, 0xDA, 0, 4, 0, 0, 1, 2),
            MetadataFixtures.bytes(
                0xFF, 0xC4, 0, 5, 0xFF, 0xD9, 0), // a table that happens to hold FF D9
            MetadataFixtures.bytes(0xFF, 0xDA, 0, 4, 0, 0, 3, 4),
            MetadataFixtures.bytes(0xFF, 0xD9),
            "TRAILING".getBytes(StandardCharsets.US_ASCII));

    final byte[] stripped = JpegMetadata.strip(progressive).orElseThrow();

    assertThat(stripped).hasSize(progressive.length - "TRAILING".length());
    assertThat(MetadataFixtures.contains(stripped, "TRAILING")).isFalse();
  }

  @Test
  void aJpegThatEndsImmediatelyAfterTheHeaderSegmentsIsKept() {
    final byte[] jpeg =
        MetadataFixtures.concat(
            MetadataFixtures.bytes(0xFF, 0xD8),
            MetadataFixtures.bytes(0xFF, 0xD9),
            MetadataFixtures.bytes(0, 0));

    assertThat(JpegMetadata.strip(jpeg).orElseThrow()).containsExactly(0xFF, 0xD8, 0xFF, 0xD9);
  }

  @Test
  void refusesAFileItCannotWalkToTheEnd() {
    final byte[] noEnd =
        MetadataFixtures.concat(
            MetadataFixtures.bytes(0xFF, 0xD8, 0xFF, 0xDA, 0, 4, 0, 0, 1, 2, 3));
    final byte[] badLength = MetadataFixtures.bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 1, 0, 0);
    final byte[] overrun = MetadataFixtures.bytes(0xFF, 0xD8, 0xFF, 0xE0, 0x7F, 0xFF, 0, 0);
    final byte[] lostPrefix = MetadataFixtures.bytes(0xFF, 0xD8, 0x00, 0x01, 0x02, 0x03);
    final byte[] onlyHeader = MetadataFixtures.bytes(0xFF, 0xD8, 0xFF);
    final byte[] notJpeg = "not a jpeg at all".getBytes(StandardCharsets.US_ASCII);

    assertThat(JpegMetadata.strip(noEnd)).isEmpty();
    assertThat(JpegMetadata.strip(badLength)).isEmpty();
    assertThat(JpegMetadata.strip(overrun)).isEmpty();
    assertThat(JpegMetadata.strip(lostPrefix)).isEmpty();
    assertThat(JpegMetadata.strip(onlyHeader)).isEmpty();
    assertThat(JpegMetadata.strip(notJpeg)).isEmpty();
  }

  @Test
  void skipsFillBytesAndStandaloneMarkersBeforeTheScan() {
    final byte[] jpeg =
        MetadataFixtures.bytes(
            0xFF, 0xD8, 0xFF, 0xFF, 0xFF, 0x01, 0xFF, 0xD0, 0xFF, 0xDA, 0, 4, 0, 0, 1, 0xFF, 0xD9);

    assertThat(JpegMetadata.strip(jpeg)).isPresent();
  }
}
