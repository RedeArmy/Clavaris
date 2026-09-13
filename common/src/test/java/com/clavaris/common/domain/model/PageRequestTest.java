package com.clavaris.common.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PageRequestTest {

  @Test
  void rejectsANegativePage() {
    assertThatThrownBy(() -> new PageRequest(-1, 20))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("page");
  }

  @Test
  void rejectsASizeBelowOne() {
    assertThatThrownBy(() -> new PageRequest(0, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("size");
  }

  @Test
  void rejectsASizeAboveTheHardCeiling() {
    assertThatThrownBy(() -> new PageRequest(0, PageRequest.MAX_SIZE + 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("size");
  }

  @Test
  void acceptsTheHardCeilingItself() {
    PageRequest request = new PageRequest(0, PageRequest.MAX_SIZE);

    assertThat(request.size()).isEqualTo(PageRequest.MAX_SIZE);
  }

  @Test
  void firstIsPageZeroAtTheDashboardDefaultSize() {
    PageRequest request = PageRequest.first();

    assertThat(request.page()).isZero();
    assertThat(request.size()).isEqualTo(PageRequest.DEFAULT_SIZE);
  }
}
