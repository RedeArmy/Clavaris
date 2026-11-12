package com.clavaris.common.domain.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ImageDimensionsTest {

  private static byte[] png(final int w, final int h) {
    final ByteBuffer b = ByteBuffer.allocate(33).order(ByteOrder.BIG_ENDIAN);
    b.put(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
    b.putInt(13).put("IHDR".getBytes(StandardCharsets.US_ASCII)).putInt(w).putInt(h);
    return b.array();
  }

  @Test
  void readsAPngHeader() {
    assertThat(ImageDimensions.read(ImageSignature.PNG, png(640, 480)))
        .contains(new ImageDimensions.Size(640, 480));
  }

  @Test
  void readsAPngWiderThanTwoToTheThirtyFirst() {
    // 0xFFFFFFFF would be -1 if the unsigned width were read into an int.
    assertThat(ImageDimensions.read(ImageSignature.PNG, png(-1, 1)))
        .contains(new ImageDimensions.Size(4_294_967_295L, 1));
  }

  @Test
  void readsAGifScreen() {
    final ByteBuffer b = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN);
    b.put("GIF89a".getBytes(StandardCharsets.US_ASCII)).putShort((short) 300).putShort((short) 200);
    assertThat(ImageDimensions.read(ImageSignature.GIF, b.array()))
        .contains(new ImageDimensions.Size(300, 200));
  }

  @Test
  void readsAJpegPastItsLeadingSegments() {
    final ByteBuffer b = ByteBuffer.allocate(40).order(ByteOrder.BIG_ENDIAN);
    b.put(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1}).putShort((short) 6);
    b.put(new byte[4]); // an EXIF-like APP1 the reader must skip
    b.put(new byte[] {(byte) 0xFF, (byte) 0xC2}).putShort((short) 8); // progressive SOF2
    b.put((byte) 8).putShort((short) 1200).putShort((short) 1600);
    assertThat(ImageDimensions.read(ImageSignature.JPEG, b.array()))
        .contains(new ImageDimensions.Size(1600, 1200));
  }

  @Test
  void readsEachWebpVariant() {
    final ByteBuffer lossless = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
    lossless.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(22);
    lossless.put("WEBPVP8L".getBytes(StandardCharsets.US_ASCII)).putInt(10).put((byte) 0x2F);
    lossless.putInt((800 - 1) | ((600 - 1) << 14));
    assertThat(ImageDimensions.read(ImageSignature.WEBP, lossless.array()))
        .contains(new ImageDimensions.Size(800, 600));

    final ByteBuffer extended = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
    extended.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(22);
    extended.put("WEBPVP8X".getBytes(StandardCharsets.US_ASCII)).putInt(10).putInt(0);
    extended.put(new byte[] {(byte) 0x1F, 0x03, 0x00}); // width - 1 = 799
    extended.put(new byte[] {0x57, 0x02, 0x00}); // height - 1 = 599
    assertThat(ImageDimensions.read(ImageSignature.WEBP, extended.array()))
        .contains(new ImageDimensions.Size(800, 600));

    final ByteBuffer lossy = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
    lossy.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(22);
    lossy.put("WEBPVP8 ".getBytes(StandardCharsets.US_ASCII)).putInt(10);
    lossy.put(new byte[] {0, 0, 0, (byte) 0x9D, 0x01, 0x2A});
    lossy.putShort((short) 320).putShort((short) 240);
    assertThat(ImageDimensions.read(ImageSignature.WEBP, lossy.array()))
        .contains(new ImageDimensions.Size(320, 240));
  }

  @Test
  void yieldsNothingForAnythingItCannotRead() {
    assertThat(ImageDimensions.read(ImageSignature.PNG, new byte[] {1, 2, 3})).isEmpty();
    assertThat(
            ImageDimensions.read(
                ImageSignature.JPEG, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}))
        .isEmpty();
    assertThat(ImageDimensions.read("image/svg+xml", png(1, 1))).isEmpty();
    assertThat(ImageDimensions.read(ImageSignature.PNG, null)).isEmpty();
    assertThat(ImageDimensions.read(null, png(1, 1))).isEmpty();
    assertThat(ImageDimensions.read(ImageSignature.PNG, png(0, 5))).isEmpty();
  }
}
