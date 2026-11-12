package com.clavaris.common.domain.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class PngMetadataTest {

  private static byte[] insertBeforeIdat(final byte[] png, final byte[]... chunks) {
    final int idat = new String(png, StandardCharsets.ISO_8859_1).indexOf("IDAT") - 4;
    return MetadataFixtures.concat(
        Arrays.copyOfRange(png, 0, idat),
        MetadataFixtures.concat(chunks),
        Arrays.copyOfRange(png, idat, png.length));
  }

  @Test
  void removesTextExifAndTimeChunksAndWhatFollowsTheEnd() throws IOException {
    final byte[] clean = MetadataFixtures.pixels("png", BufferedImage.TYPE_INT_ARGB);
    final byte[] secret = MetadataFixtures.SECRET.getBytes(StandardCharsets.US_ASCII);
    final byte[] dirty =
        MetadataFixtures.concat(
            insertBeforeIdat(
                clean,
                MetadataFixtures.pngChunk("tEXt", secret),
                MetadataFixtures.pngChunk("zTXt", secret),
                MetadataFixtures.pngChunk("iTXt", secret),
                MetadataFixtures.pngChunk("eXIf", MetadataFixtures.tiff(6)),
                MetadataFixtures.pngChunk("tIME", new byte[7])),
            "TRAILING".getBytes(StandardCharsets.US_ASCII));

    final byte[] stripped = PngMetadata.strip(dirty).orElseThrow();

    assertThat(MetadataFixtures.contains(dirty, MetadataFixtures.SECRET)).isTrue();
    assertThat(MetadataFixtures.contains(stripped, MetadataFixtures.SECRET)).isFalse();
    assertThat(stripped).isEqualTo(clean);
    final BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(stripped));
    assertThat(decoded.getRGB(3, 3)).isEqualTo(0xFFAA5500);
  }

  @Test
  void keepsTheChunksThatDescribeThePictureByteForByte() {
    final byte[] gamma =
        MetadataFixtures.pngChunk("gAMA", new byte[] {0, 1, (byte) 0x86, (byte) 0xA0});
    final byte[] png =
        MetadataFixtures.concat(
            MetadataFixtures.bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A),
            MetadataFixtures.pngChunk("IHDR", new byte[13]),
            gamma,
            MetadataFixtures.pngChunk("IEND", new byte[0]));

    final byte[] stripped = PngMetadata.strip(png).orElseThrow();

    assertThat(stripped).isEqualTo(png);
  }

  @Test
  void refusesAFileWithoutAnEndChunkOrWithAChunkThatOverruns() {
    final byte[] header = MetadataFixtures.bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A);
    final byte[] noEnd =
        MetadataFixtures.concat(header, MetadataFixtures.pngChunk("IHDR", new byte[13]));
    final byte[] overrun =
        MetadataFixtures.concat(
            header, MetadataFixtures.bytes(0x7F, 0xFF, 0xFF, 0xFF, 'I', 'H', 'D', 'R', 0, 0, 0, 0));
    final byte[] notPng = "GIF89a".getBytes(StandardCharsets.US_ASCII);

    assertThat(PngMetadata.strip(noEnd)).isEmpty();
    assertThat(PngMetadata.strip(overrun)).isEmpty();
    assertThat(PngMetadata.strip(notPng)).isEmpty();
    assertThat(PngMetadata.strip(header)).isEmpty();
  }
}
