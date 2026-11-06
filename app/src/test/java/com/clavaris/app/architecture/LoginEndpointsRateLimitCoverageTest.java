package com.clavaris.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.app.infrastructure.adapter.in.web.filter.HostedPageRateLimitRules;
import com.clavaris.app.infrastructure.adapter.in.web.filter.OrganizationRateLimitRules;
import com.clavaris.app.infrastructure.adapter.in.web.filter.RateLimitRule;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * TD-SEC-085: every endpoint that takes a code or a credential under {@code
 * /o/{organizationId}/login/} needs an anti-abuse rate-limit rule. The authenticator-app challenge
 * shipped without one, the only code-entry endpoint of the set that did, because nothing asked.
 *
 * <p>The test finds the POST endpoints in the controllers, so a new one is checked without anyone
 * remembering to add it here, and matches each against the rules the chains are built from. An
 * endpoint may be left out only by listing it in {@link #COVERED_BY_ANOTHER_LAYER} with the reason,
 * which turns "we forgot" into a decision someone wrote down and a reviewer can read.
 */
class LoginEndpointsRateLimitCoverageTest {

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("com.clavaris");

  private static final String LOGIN_PREFIX = "/o/*/login";

  private static final int ANY = 10;

  /** A POST endpoint under /login that no rule matches, and why that is acceptable. */
  private static final Map<String, String> COVERED_BY_ANOTHER_LAYER = Map.of();

  @Test
  void everyPostEndpointUnderLoginHasARateLimitRule() {
    final List<RateLimitRule> rules = new ArrayList<>();
    rules.addAll(HostedPageRateLimitRules.all(ANY, ANY, ANY, ANY, ANY));
    rules.addAll(
        OrganizationRateLimitRules.all(LOGIN_PREFIX, ANY, ANY, ANY, ANY, ANY, ANY, ANY, ANY));
    final AntPathMatcher matcher = new AntPathMatcher();

    final TreeSet<String> endpoints = postEndpointsUnderLogin();
    assertThat(endpoints)
        .as("the scan must find the sign-in endpoints, or this test proves nothing")
        .contains("/o/*/login/totp", "/o/*/login/device-trust", "/o/*/login/email-code");

    final List<String> unprotected =
        endpoints.stream()
            .filter(path -> !COVERED_BY_ANOTHER_LAYER.containsKey(path))
            .filter(
                path ->
                    rules.stream()
                        .noneMatch(
                            rule ->
                                HttpMethod.POST.equals(rule.method())
                                    && matcher.match(rule.pathPattern(), path)))
            .toList();

    assertThat(unprotected)
        .as(
            "POST endpoints under %s with no rate-limit rule: add a rule, or list the path in"
                + " COVERED_BY_ANOTHER_LAYER with the layer that limits it",
            LOGIN_PREFIX)
        .isEmpty();
  }

  @Test
  void anExemptionIsNotKeptForAnEndpointThatNoLongerExists() {
    final TreeSet<String> endpoints = postEndpointsUnderLogin();

    assertThat(COVERED_BY_ANOTHER_LAYER.keySet())
        .as("a stale exemption hides a path that may come back without a rule")
        .allMatch(endpoints::contains);
  }

  private static TreeSet<String> postEndpointsUnderLogin() {
    final TreeSet<String> found = new TreeSet<>();
    for (final JavaClass controller : CLASSES) {
      final RequestMapping base =
          controller.tryGetAnnotationOfType(RequestMapping.class).orElse(null);
      if (base == null) {
        continue;
      }
      for (final String basePath : pathsOf(base.value(), base.path())) {
        for (final JavaMethod method : controller.getMethods()) {
          method
              .tryGetAnnotationOfType(PostMapping.class)
              .ifPresent(
                  post -> {
                    for (final String sub : pathsOf(post.value(), post.path())) {
                      final String full = normalise(basePath + sub);
                      if (full.equals(LOGIN_PREFIX) || full.startsWith(LOGIN_PREFIX + "/")) {
                        found.add(full);
                      }
                    }
                  });
        }
      }
    }
    return found;
  }

  /** The declared paths, or one empty path when the mapping is the base itself. */
  private static List<String> pathsOf(final String[] value, final String[] path) {
    final List<String> all = new ArrayList<>(List.of(value));
    all.addAll(List.of(path));
    return all.isEmpty() ? List.of("") : all;
  }

  /** {@code {organizationId}} and every other path variable become {@code *}. */
  private static String normalise(final String raw) {
    return raw.replaceAll("\\{[^/}]+}", "*");
  }
}
