package com.clavaris.common.domain.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class GifMetadataTest {

  private static byte[] ascii(final String text) {
    return text.getBytes(StandardCharsets.US_ASCII);
  }

  private static byte[] header() {
    // 1x1 screen, a 2-colour global table
    return MetadataFixtures.concat(
        ascii("GIF89a"),
        MetadataFixtures.bytes(1, 0, 1, 0, 0x80, 0, 0),
        MetadataFixtures.bytes(0, 0, 0, 255, 255, 255));
  }

  private static byte[] frame() {
    return MetadataFixtures.bytes(0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x44, 0x01, 0);
  }

  private static byte[] extension(final int label, final byte[]... subBlocks) {
    final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    out.writeBytes(MetadataFixtures.bytes(0x21, label));
    for (final byte[] block : subBlocks) {
      out.write(block.length);
      out.writeBytes(block);
    }
    out.write(0);
    return out.toByteArray();
  }

  @Test
  void removesCommentsAndForeignApplicationExtensionsButKeepsTheLoopAndTiming() throws IOException {
    final byte[] gce = extension(0xF9, MetadataFixtures.bytes(0, 10, 0, 0));
    final byte[] loop = extension(0xFF, ascii("NETSCAPE2.0"), MetadataFixtures.bytes(1, 0, 0));
    final byte[] gif =
        MetadataFixtures.concat(
            header(),
            extension(0xFE, ascii(MetadataFixtures.SECRET)),
            extension(0xFF, ascii("XMP DataXMP"), ascii(MetadataFixtures.SECRET)),
            loop,
            gce,
            frame(),
            MetadataFixtures.bytes(0x3B),
            ascii("TRAILING"));

    final byte[] stripped = GifMetadata.strip(gif).orElseThrow();

    assertThat(MetadataFixtures.contains(stripped, MetadataFixtures.SECRET)).isFalse();
    assertThat(MetadataFixtures.contains(stripped, "XMP Data")).isFalse();
    assertThat(MetadataFixtures.contains(stripped, "NETSCAPE2.0")).isTrue();
    assertThat(stripped).containsSequence(gce);
    assertThat(stripped).endsWith((byte) 0x3B);
    assertThat(MetadataFixtures.contains(stripped, "TRAILING")).isFalse();
    assertThat(ImageIO.read(new ByteArrayInputStream(stripped))).isNotNull();
  }

  @Test
  void keepsTheAnimationExtensionOfOtherEncodersAndPlainText() {
    final byte[] gif =
        MetadataFixtures.concat(
            header(),
            extension(0xFF, ascii("ANIMEXTS1.0"), MetadataFixtures.bytes(1, 0, 0)),
            extension(0x01, MetadataFixtures.bytes(1, 2, 3)),
            frame(),
            MetadataFixtures.bytes(0x3B));

    final byte[] stripped = GifMetadata.strip(gif).orElseThrow();

    assertThat(stripped).isEqualTo(gif);
  }

  @Test
  void readsALocalColourTableAndDropsAnUnknownExtension() {
    // image descriptor with a local table of 2 colours (packed 0x80)
    final byte[] localFrame =
        MetadataFixtures.concat(
            MetadataFixtures.bytes(0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0x80),
            MetadataFixtures.bytes(0, 0, 0, 255, 255, 255),
            MetadataFixtures.bytes(2, 2, 0x44, 0x01, 0));
    final byte[] gif =
        MetadataFixtures.concat(
            header(), extension(0x42, ascii("unknown")), localFrame, MetadataFixtures.bytes(0x3B));

    final byte[] stripped = GifMetadata.strip(gif).orElseThrow();

    assertThat(MetadataFixtures.contains(stripped, "unknown")).isFalse();
    assertThat(stripped).containsSequence(localFrame);
  }

  @Test
  void addsTheTrailerWhenTheFileEndsOnABlockBoundary() {
    final byte[] gif = MetadataFixtures.concat(header(), frame());

    final byte[] stripped = GifMetadata.strip(gif).orElseThrow();

    assertThat(stripped).endsWith((byte) 0x3B);
  }

  @Test
  void refusesAFileItCannotWalk() {
    final byte[] unknownBlock =
        MetadataFixtures.concat(header(), MetadataFixtures.bytes(0x55, 0x00));
    final byte[] cutInFrame =
        MetadataFixtures.concat(header(), MetadataFixtures.bytes(0x2C, 0, 0, 0));
    final byte[] cutInExtension =
        MetadataFixtures.concat(header(), MetadataFixtures.bytes(0x21, 0xFE, 5, 'a'));
    final byte[] cutInTable =
        MetadataFixtures.concat(ascii("GIF89a"), MetadataFixtures.bytes(1, 0, 1, 0, 0x80, 0, 0, 1));
    final byte[] tooShort = ascii("GIF89a");
    final byte[] notGif = ascii("not a gif at all");
    final byte[] frameWithoutData =
        MetadataFixtures.concat(header(), MetadataFixtures.bytes(0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0));

    assertThat(GifMetadata.strip(unknownBlock)).isEmpty();
    assertThat(GifMetadata.strip(cutInFrame)).isEmpty();
    assertThat(GifMetadata.strip(cutInExtension)).isEmpty();
    assertThat(GifMetadata.strip(cutInTable)).isEmpty();
    assertThat(GifMetadata.strip(tooShort)).isEmpty();
    assertThat(GifMetadata.strip(notGif)).isEmpty();
    assertThat(GifMetadata.strip(frameWithoutData)).isEmpty();
  }
}
