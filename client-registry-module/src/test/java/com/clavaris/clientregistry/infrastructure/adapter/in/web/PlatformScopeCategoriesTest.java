package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.clientregistry.domain.model.PlatformScopes;
import com.clavaris.clientregistry.infrastructure.adapter.in.web.PlatformScopeCategories.ScopeCategory;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlatformScopeCategoriesTest {

  @Test
  void groupsEveryAllowedScopeUnderExactlyOnePlatformCategoryToday() {
    List<ScopeCategory> categories = PlatformScopeCategories.groupedByCategory();

    assertThat(categories).hasSize(1);
    ScopeCategory platform = categories.get(0);
    assertThat(platform.name()).isEqualTo("platform");
    assertThat(platform.scopes())
        .containsExactlyElementsOf(PlatformScopes.ORGANIZATION_CLIENT_ALLOWED);
  }

  @Test
  void neverIncludesAnOperatorOnlyScope() {
    List<ScopeCategory> categories = PlatformScopeCategories.groupedByCategory();

    List<String> allGroupedScopes = categories.stream().flatMap(c -> c.scopes().stream()).toList();
    assertThat(allGroupedScopes).doesNotContainAnyElementsOf(PlatformScopes.OPERATOR_ONLY);
  }
}
