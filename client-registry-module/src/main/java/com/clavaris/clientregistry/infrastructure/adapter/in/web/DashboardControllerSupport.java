package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * CPD finding (CI, 2026-09-11): {@link PlatformOrganizationClientController} and {@link
 * PlatformOAuthClientController} had independently grown four provably-identical private methods
 * (the HTMX-header check, the "resolve the current PlatformAccount or fail loudly" guard, the
 * ownership-check-or-404, and the anti-enumeration "does this clientId belong to this Organization"
 * check) plus one identical constant — genuine same-module duplication, not the deliberate
 * cross-module kind {@code identity/platform/fragments/dashboard-nav.html}'s own comment documents
 * (both controllers live in this module, this package even; no module- independence rule is in
 * tension here). Same "small shared utility class" precedent {@code
 * identity.infrastructure.adapter.in.web.CurrentSessionSupport} already establishes for an
 * identical same-package duplication in a different module.
 *
 * <p>Deliberately NOT a shared base controller class: the two controllers' own use-case
 * dependencies, form types, and view names are genuinely unrelated beyond these four fragments, and
 * forcing a common superclass just to share them would be the exact "bigger, riskier refactor for a
 * marginal gain" trade-off {@code CurrentSessionSupport}'s own Javadoc already rejects for a
 * structurally similar case. {@link #requireClientIdBelongsToOrganization} takes an already-mapped
 * {@code List<String>} rather than being generic over {@code OrganizationClient}/{@code
 * OAuthClient} — the one-line {@code .stream().map(X::clientId).toList()} each caller still does
 * itself is a fair, small price for not needing a shared supertype or a functional-interface
 * parameter for a single call site. PMD.LongVariable: every parameter here names exactly what it is
 * — same "deliberate, descriptive name over an arbitrary shortening" convention this codebase
 * applies everywhere else this rule fires.
 */
@SuppressWarnings("PMD.LongVariable")
final class DashboardControllerSupport {

  // HTMX's own request header (https://htmx.org/reference/#request_headers).
  private static final String HX_REQUEST_HEADER = "HX-Request";

  private DashboardControllerSupport() {}

  /* package */ static boolean isHtmxRequest(final HttpServletRequest request) {
    return "true".equals(request.getHeader(HX_REQUEST_HEADER));
  }

  /* package */ static UUID requireCurrentPlatformAccount(
      final HttpServletRequest request,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    return currentPlatformAccount
        .resolve(request)
        .orElseThrow(
            () -> new IllegalStateException("No authenticated PlatformAccount on this request"));
  }

  /* package */ static String requireOwnedOrganizationName(
      final UUID organizationId,
      final UUID ownerPlatformAccountId,
      final OrganizationForPlatformAccountResolver organizationResolver) {
    return organizationResolver
        .resolveName(organizationId, ownerPlatformAccountId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  // The anti-enumeration check neither DeactivateOrganizationClientCommand/
  // RotateOrganizationClientSecretCommand nor their OAuthClient siblings can do themselves —
  // none of the four carries an organizationId, all key off clientId alone. Callers pass the
  // already-organizationId-scoped client ids (via their own ListXClientsUseCase), so a clientId
  // belonging to a different Organization 404s before the mutating use case ever runs.
  /* package */ static void requireClientIdBelongsToOrganization(
      final List<String> organizationClientIds, final String clientId) {
    if (organizationClientIds.stream().noneMatch(clientId::equals)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
  }
}
