package com.clavaris.common.domain.model;

import java.util.List;

/**
 * TD-PERF-020: the result half of {@link PageRequest} — one page of {@code content} plus enough
 * metadata for a dashboard template to render Previous/Next controls without a second query of its
 * own. Mirrors {@code org.springframework.data.domain.Page}'s own shape deliberately (same field
 * meanings, same derived values) so a JPA adapter mapping one onto the other is a direct,
 * unsurprising translation — but this type itself carries no Spring/JPA dependency, staying usable
 * from the {@code application}/{@code domain} layers per this codebase's own hexagonal dependency
 * rule (only {@code infrastructure} may depend on Spring Data).
 */
public record Page<T>(List<T> content, int page, int size, long totalElements) {

  public Page {
    content = List.copyOf(content);
  }

  public int totalPages() {
    return size == 0 ? 0 : (int) Math.ceil((double) totalElements / size);
  }

  public boolean hasNext() {
    return page + 1 < totalPages();
  }

  public boolean hasPrevious() {
    return page > 0;
  }

  public boolean isEmpty() {
    return content.isEmpty();
  }
}
