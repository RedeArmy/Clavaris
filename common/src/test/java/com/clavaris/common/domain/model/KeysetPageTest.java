package com.clavaris.common.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KeysetPageTest {

  private static final KeysetCursor A_CURSOR = new KeysetCursor(Instant.now(), UUID.randomUUID());

  @Test
  void isEmptyReflectsAnEmptyContentList() {
    final KeysetPage<String> page = new KeysetPage<>(List.of(), null, null, false, false);

    assertThat(page.isEmpty()).isTrue();
  }

  @Test
  void isEmptyIsFalseWhenContentHasRows() {
    final KeysetPage<String> page =
        new KeysetPage<>(List.of("row"), A_CURSOR, A_CURSOR, true, true);

    assertThat(page.isEmpty()).isFalse();
  }

  @Test
  void contentIsDefensivelyCopied() {
    final java.util.ArrayList<String> mutable = new java.util.ArrayList<>(List.of("row"));
    final KeysetPage<String> page = new KeysetPage<>(mutable, A_CURSOR, A_CURSOR, false, false);

    mutable.add("mutated after construction");

    assertThat(page.content()).containsExactly("row");
  }
}
