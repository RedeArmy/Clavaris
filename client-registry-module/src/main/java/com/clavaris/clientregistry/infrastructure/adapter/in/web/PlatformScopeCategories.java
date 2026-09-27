package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import com.clavaris.clientregistry.domain.model.PlatformScopes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Web-layer only — groups {@link PlatformScopes#ORGANIZATION_CLIENT_ALLOWED} by category (the
 * scope's own leading {@code platform:x} segment) for the dashboard's "Create a new Secret Key"
 * form — same parent/child checkbox picker shape {@code KnownWebhookEventTypeOptions
 * #groupedByCategory} already establishes for webhook event types (webhook-module).
 *
 * <p>Every scope here is namespaced {@code platform:} today ({@link
 * PlatformScopes#NAMESPACE_PREFIX}), so this yields exactly one category, {@code "platform"} — one
 * parent checkbox that selects every scope beneath it, no "select all categories" toggle on top of
 * it (that would only make sense once a second category actually exists). This grouping stays ready
 * for that second category the moment one is added, without any template or script change — only
 * the category's own boundary detection in the template relies on the list already being contiguous
 * by category, same assumption {@code EventTypeOption}'s own Javadoc documents.
 */
public final class PlatformScopeCategories {

  private PlatformScopeCategories() {
    // Constants only.
  }

  public static List<ScopeCategory> groupedByCategory() {
    return PlatformScopes.ORGANIZATION_CLIENT_ALLOWED.stream()
        .collect(
            Collectors.groupingBy(
                PlatformScopeCategories::categoryOf, LinkedHashMap::new, Collectors.toList()))
        .entrySet()
        .stream()
        .map(entry -> new ScopeCategory(entry.getKey(), entry.getValue()))
        .toList();
  }

  private static String categoryOf(final String scope) {
    return scope.substring(0, scope.indexOf(':'));
  }

  public record ScopeCategory(String name, List<String> scopes) {}
}
