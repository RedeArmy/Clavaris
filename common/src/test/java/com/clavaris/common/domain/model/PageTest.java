package com.clavaris.common.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PageTest {

  @Test
  void totalPagesRoundsUpForARemainder() {
    Page<String> page = new Page<>(List.of("a", "b"), 0, 20, 21);

    assertThat(page.totalPages()).isEqualTo(2);
  }

  @Test
  void totalPagesIsExactWhenTotalElementsDividesEvenly() {
    Page<String> page = new Page<>(List.of(), 0, 20, 40);

    assertThat(page.totalPages()).isEqualTo(2);
  }

  @Test
  void hasNextIsTrueWhenAnotherPageExistsPastThisOne() {
    Page<String> firstOfTwo = new Page<>(List.of("a"), 0, 20, 21);
    Page<String> secondOfTwo = new Page<>(List.of("b"), 1, 20, 21);

    assertThat(firstOfTwo.hasNext()).isTrue();
    assertThat(secondOfTwo.hasNext()).isFalse();
  }

  @Test
  void hasPreviousIsFalseOnlyOnTheFirstPage() {
    Page<String> first = new Page<>(List.of("a"), 0, 20, 21);
    Page<String> second = new Page<>(List.of("b"), 1, 20, 21);

    assertThat(first.hasPrevious()).isFalse();
    assertThat(second.hasPrevious()).isTrue();
  }

  @Test
  void isEmptyReflectsTheContentListAlone() {
    Page<String> empty = new Page<>(List.of(), 0, 20, 0);
    Page<String> nonEmpty = new Page<>(List.of("a"), 0, 20, 1);

    assertThat(empty.isEmpty()).isTrue();
    assertThat(nonEmpty.isEmpty()).isFalse();
  }

  @Test
  void totalPagesIsZeroForNoElementsAtAll() {
    Page<String> page = new Page<>(List.of(), 0, 20, 0);

    assertThat(page.totalPages()).isZero();
    assertThat(page.hasNext()).isFalse();
  }
}
