package com.clavaris.common.domain.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class ImageMetadataTest {

  @Test
  void dispatchesEachTypeToItsOwnFormat() throws IOException {
    assertThat(
            ImageMetadata.strip(
                ImageSignature.PNG, MetadataFixtures.pixels("png", BufferedImage.TYPE_INT_ARGB)))
        .isPresent();
    assertThat(
            ImageMetadata.strip(
                ImageSignature.JPEG, MetadataFixtures.pixels("jpeg", BufferedImage.TYPE_INT_RGB)))
        .isPresent();
    assertThat(
            ImageMetadata.strip(
                ImageSignature.GIF, MetadataFixtures.pixels("gif", BufferedImage.TYPE_INT_RGB)))
        .isPresent();
  }

  @Test
  void refusesWhatItCannotCleanInsteadOfPassingItThrough() throws IOException {
    final byte[] png = MetadataFixtures.pixels("png", BufferedImage.TYPE_INT_ARGB);

    assertThat(ImageMetadata.strip(ImageSignature.JPEG, png)).isEmpty(); // right bytes, wrong type
    assertThat(ImageMetadata.strip("image/svg+xml", png)).isEmpty();
    assertThat(ImageMetadata.strip(null, png)).isEmpty();
    assertThat(ImageMetadata.strip(ImageSignature.PNG, null)).isEmpty();
  }
}
