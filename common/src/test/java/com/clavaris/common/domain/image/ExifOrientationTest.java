package com.clavaris.common.domain.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class ExifOrientationTest {

  @Test
  void readsTheOrientationInEitherByteOrder() {
    assertThat(ExifOrientation.read(MetadataFixtures.tiff(6))).isEqualTo(6);
    assertThat(ExifOrientation.read(ExifOrientation.onlyOrientation(8))).isEqualTo(8);
  }

  @Test
  void aBlockHoldingOnlyTheOrientationCarriesNothingElse() {
    final byte[] tiff = ExifOrientation.onlyOrientation(3);

    assertThat(tiff).hasSize(26);
    assertThat(MetadataFixtures.contains(tiff, MetadataFixtures.SECRET)).isFalse();
    assertThat(ExifOrientation.read(tiff)).isEqualTo(3);
  }

  @Test
  void anythingMalformedIsTakenAsUpright() {
    assertThat(ExifOrientation.read(new byte[4])).isEqualTo(1);
    assertThat(ExifOrientation.read(MetadataFixtures.bytes('X', 'X', 42, 0, 8, 0, 0, 0)))
        .isEqualTo(1);
    assertThat(ExifOrientation.read(MetadataFixtures.bytes('I', 'I', 43, 0, 8, 0, 0, 0)))
        .isEqualTo(1);
    assertThat(ExifOrientation.read(MetadataFixtures.bytes('I', 'I', 42, 0, 2, 0, 0, 0, 0, 0)))
        .isEqualTo(1);
    assertThat(
            ExifOrientation.read(MetadataFixtures.bytes('I', 'I', 42, 0, 0xFF, 0xFF, 0, 0, 0, 0)))
        .isEqualTo(1);
  }

  @Test
  void aValueOutsideOneToEightOrOfTheWrongTypeIsTakenAsUpright() {
    assertThat(ExifOrientation.read(MetadataFixtures.tiff(9))).isEqualTo(1);
    assertThat(ExifOrientation.read(MetadataFixtures.tiff(0))).isEqualTo(1);

    final byte[] wrongType = MetadataFixtures.tiff(6);
    ByteBuffer.wrap(wrongType).order(ByteOrder.LITTLE_ENDIAN).putShort(10 + 12 + 2, (short) 4);
    assertThat(ExifOrientation.read(wrongType)).isEqualTo(1);
  }

  @Test
  void aDirectoryCutOffMidEntryIsTakenAsUpright() {
    final byte[] whole = MetadataFixtures.tiff(6);
    final byte[] cut = java.util.Arrays.copyOf(whole, 10 + 12 + 6);

    assertThat(ExifOrientation.read(cut)).isEqualTo(1);
  }
}
