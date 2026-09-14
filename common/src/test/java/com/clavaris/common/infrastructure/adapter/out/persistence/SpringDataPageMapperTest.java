package com.clavaris.common.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.common.domain.model.Page;
import com.clavaris.common.domain.model.PageRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

class SpringDataPageMapperTest {

  @Test
  void mapsContentPageAndTotalElementsFromTheSpringPage() {
    org.springframework.data.domain.Page<String> springPage =
        new PageImpl<>(List.of("a", "b"), org.springframework.data.domain.PageRequest.of(1, 2), 5);

    Page<Integer> mapped =
        SpringDataPageMapper.toPage(springPage, new PageRequest(1, 2), String::length);

    assertThat(mapped.content()).containsExactly(1, 1);
    assertThat(mapped.page()).isEqualTo(1);
    assertThat(mapped.size()).isEqualTo(2);
    assertThat(mapped.totalElements()).isEqualTo(5);
  }
}
