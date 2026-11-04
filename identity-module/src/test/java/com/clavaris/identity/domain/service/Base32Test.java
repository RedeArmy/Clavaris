package com.clavaris.identity.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class Base32Test {

  // RFC 4648 §10 test vectors, without padding.
  @Test
  void encodesTheRfcVectors() {
    assertThat(Base32.encode("".getBytes(StandardCharsets.US_ASCII))).isEmpty();
    assertThat(Base32.encode("f".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MY");
    assertThat(Base32.encode("fo".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXQ");
    assertThat(Base32.encode("foobar".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YTBOI");
  }

  @Test
  void decodesWhatItEncodes() {
    final byte[] original = {1, 2, 3, (byte) 0xFF, 0, 42, 7};
    assertThat(Base32.decode(Base32.encode(original))).isEqualTo(original);
  }

  @Test
  void decodingIgnoresCaseSpacesAndHyphens() {
    assertThat(Base32.decode("mzxw 6ytb-oi"))
        .isEqualTo("foobar".getBytes(StandardCharsets.US_ASCII));
  }

  @Test
  void decodingRefusesACharacterOutsideTheAlphabet() {
    assertThatThrownBy(() -> Base32.decode("MZXW1")).isInstanceOf(IllegalArgumentException.class);
  }
}
