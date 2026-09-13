package com.clavaris.common.infrastructure.adapter.out.persistence;

import com.clavaris.common.domain.model.Page;
import com.clavaris.common.domain.model.PageRequest;
import java.util.function.Function;

/**
 * TD-PERF-020: the one place every JPA adapter's own {@code findPageByX} method maps a real Spring
 * Data {@code org.springframework.data.domain.Page<Entity>} onto this codebase's own framework-free
 * {@link Page} — found live by {@code pmd:cpd-check} the first time this exact five-line mapping
 * got copy-pasted into a fourth/fifth repository (Secret Keys and OAuth Clients,
 * client-registry-module) after already appearing in organization-module's own {@code
 * JpaOrganizationRepository}/{@code JpaWorkspaceRepository}/{@code
 * JpaWorkspaceMembershipRepository}. Every one of those, plus webhook-module's own {@code
 * JpaWebhookEndpointRepository}, now calls this instead of repeating it a sixth time.
 */
public final class SpringDataPageMapper {

  private SpringDataPageMapper() {}

  public static <E, D> Page<D> toPage(
      final org.springframework.data.domain.Page<E> springPage,
      final PageRequest pageRequest,
      final Function<E, D> toDomain) {
    return new Page<>(
        springPage.getContent().stream().map(toDomain).toList(),
        pageRequest.page(),
        pageRequest.size(),
        springPage.getTotalElements());
  }
}
