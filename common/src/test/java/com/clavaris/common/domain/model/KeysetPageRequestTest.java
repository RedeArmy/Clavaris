package com.clavaris.common.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KeysetPageRequestTest {

  private static final KeysetCursor A_CURSOR = new KeysetCursor(Instant.now(), UUID.randomUUID());

  @Test
  void firstHasNoCursorAtAll() {
    final KeysetPageRequest request = KeysetPageRequest.first();

    assertThat(request.after()).isNull();
    assertThat(request.before()).isNull();
    assertThat(request.isFirst()).isTrue();
    assertThat(request.size()).isEqualTo(KeysetPageRequest.DEFAULT_SIZE);
  }

  @Test
  void afterCarriesOnlyTheAfterCursor() {
    final KeysetPageRequest request = KeysetPageRequest.after(A_CURSOR);

    assertThat(request.after()).isEqualTo(A_CURSOR);
    assertThat(request.before()).isNull();
    assertThat(request.isFirst()).isFalse();
  }

  @Test
  void beforeCarriesOnlyTheBeforeCursor() {
    final KeysetPageRequest request = KeysetPageRequest.before(A_CURSOR);

    assertThat(request.before()).isEqualTo(A_CURSOR);
    assertThat(request.after()).isNull();
    assertThat(request.isFirst()).isFalse();
  }

  @Test
  void rejectsBothAfterAndBeforeSetAtOnce() {
    assertThatThrownBy(
            () -> new KeysetPageRequest(A_CURSOR, A_CURSOR, KeysetPageRequest.DEFAULT_SIZE))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsASizeBelowOne() {
    assertThatThrownBy(() -> new KeysetPageRequest(null, null, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsASizeAboveTheHardCeiling() {
    assertThatThrownBy(() -> new KeysetPageRequest(null, null, KeysetPageRequest.MAX_SIZE + 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void fromCursorsIsFirstWhenBothParamsAreNull() {
    assertThat(KeysetPageRequest.fromCursors(null, null).isFirst()).isTrue();
  }

  @Test
  void fromCursorsIsFirstWhenBothParamsAreBlank() {
    assertThat(KeysetPageRequest.fromCursors("", "  ").isFirst()).isTrue();
  }

  @Test
  void fromCursorsDecodesAnAfterParam() {
    final KeysetPageRequest request = KeysetPageRequest.fromCursors(A_CURSOR.encode(), null);

    assertThat(request.after()).isEqualTo(A_CURSOR);
    assertThat(request.before()).isNull();
  }

  @Test
  void fromCursorsDecodesABeforeParamWhenAfterIsAbsent() {
    final KeysetPageRequest request = KeysetPageRequest.fromCursors(null, A_CURSOR.encode());

    assertThat(request.before()).isEqualTo(A_CURSOR);
    assertThat(request.after()).isNull();
  }

  @Test
  void fromCursorsPrefersAfterWhenBothAreSomehowPresent() {
    final KeysetPageRequest request =
        KeysetPageRequest.fromCursors(A_CURSOR.encode(), A_CURSOR.encode());

    assertThat(request.after()).isEqualTo(A_CURSOR);
    assertThat(request.before()).isNull();
  }
}
