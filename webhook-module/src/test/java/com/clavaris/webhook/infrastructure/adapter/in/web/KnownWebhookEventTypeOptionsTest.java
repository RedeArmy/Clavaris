package com.clavaris.webhook.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.webhook.infrastructure.adapter.in.web.KnownWebhookEventTypeOptions.CategoryGroup;
import com.clavaris.webhook.infrastructure.adapter.in.web.KnownWebhookEventTypeOptions.EventTypeOption;
import java.util.List;
import org.junit.jupiter.api.Test;

class KnownWebhookEventTypeOptionsTest {

  @Test
  void categoryDerivesFromTheValuesOwnLeadingSegment() {
    EventTypeOption option = new EventTypeOption("account.created", "desc");

    assertThat(option.category()).isEqualTo("account");
  }

  @Test
  void groupedByCategoryPutsEveryEventUnderItsOwnCategoryOnly() {
    List<CategoryGroup> groups = KnownWebhookEventTypeOptions.groupedByCategory();

    CategoryGroup account =
        groups.stream()
            .filter(group -> group.category().equals("account"))
            .findFirst()
            .orElseThrow();
    assertThat(account.events())
        .extracting(EventTypeOption::value)
        .allMatch(v -> v.startsWith("account."));
    // TD-FUT-044 added account.profile_updated as a 9th account.* event.
    assertThat(account.events()).hasSize(9);
  }

  @Test
  void groupedByCategoryCoversEveryEntryInCatalogExactlyOnce() {
    List<CategoryGroup> groups = KnownWebhookEventTypeOptions.groupedByCategory();

    long totalGrouped = groups.stream().mapToLong(group -> group.events().size()).sum();

    assertThat(totalGrouped).isEqualTo(KnownWebhookEventTypeOptions.CATALOG.size());
  }

  // groupedByCategory() must preserve CATALOG's own category order (already contiguous by
  // construction) — a LinkedHashMap-backed grouping, not an unrelated hash order.
  @Test
  void groupedByCategoryPreservesCatalogsOwnCategoryOrder() {
    List<String> expectedOrder =
        KnownWebhookEventTypeOptions.CATALOG.stream()
            .map(EventTypeOption::category)
            .distinct()
            .toList();

    List<String> actualOrder =
        KnownWebhookEventTypeOptions.groupedByCategory().stream()
            .map(CategoryGroup::category)
            .toList();

    assertThat(actualOrder).containsExactlyElementsOf(expectedOrder);
  }
}
