package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.KeysetPageRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindingResult;
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
 * dependencies, form types, and view names are genuinely unrelated beyond these shared fragments,
 * and forcing a common superclass just to share them would be the exact "bigger, riskier refactor
 * for a marginal gain" trade-off {@code CurrentSessionSupport}'s own Javadoc already rejects for a
 * structurally similar case. PMD.LongVariable: every parameter here names exactly what it is — same
 * "deliberate, descriptive name over an arbitrary shortening" convention this codebase applies
 * everywhere else this rule fires.
 *
 * <p><b>SDE-III review, 2026-09-15:</b> the anti-enumeration "does this clientId belong to this
 * Organization" check named above ({@code requireClientIdBelongsToOrganization}) is gone — it was a
 * web-layer-only workaround for {@code DeactivateOrganizationClientCommand}/{@code
 * RotateOrganizationClientSecretCommand}/their {@code OAuthClient} siblings carrying no {@code
 * organizationId} of their own. All four now do, and their own services verify ownership directly
 * (see {@code DeactivateOrganizationClientCommand}'s own Javadoc) — real defense-in-depth instead
 * of a check only this package's two controllers happened to remember to run first.
 *
 * <p><b>SonarCloud duplication finding (CI, TD-PERF-020's own pagination pass), 2026-09-13:</b>
 * {@link #requireOwnedOrganization} is the further step this class's own 2026-09-11 Javadoc above
 * declined to take at the time — {@code requireCurrentPlatformAccount} then {@code
 * requireOwnedOrganizationName}, called in that exact two-statement sequence, is itself what
 * SonarCloud's own duplication analysis (a real detector this class's local {@code pmd:cpd-check}
 * pass could not fully account for) matched at four call sites in each controller once
 * TD-PERF-020's own pagination code added a fifth. Collapsing the pair into one call and one
 * returned {@link OwnedOrganization} removes that duplicated token sequence at its source, not by
 * hiding it from one tool's own view.
 *
 * <p><b>SonarCloud CPD finding (CI, 2026-09-14):</b> {@code showList}'s own body — resolve
 * ownership, render the requested page into the model, branch HTMX-fragment-vs-full-view — matched
 * across both controllers closely enough to clear the cross-file duplication threshold, the moment
 * TD-PERF-020 added the method to both the same day. {@link #showPaginatedList} is the same
 * "extract the exact duplicated sequence into this class" response as every finding above,
 * parameterized by a {@link BiConsumer} for the one genuinely controller-specific step — populating
 * that controller's own model attributes via its own private {@code renderXList} method, which
 * {@link #showPaginatedList} itself has no business knowing about.
 *
 * <p><b>Local {@code pmd:cpd-check} finding (CI, 2026-09-15):</b> removing {@code
 * requireClientIdBelongsToOrganization} (see this class's own addendum above) left each
 * controller's own {@code renderXValidationErrors} as the last thing standing between two
 * previously-separated duplicate blocks. {@link #renderValidationErrorsIfAny} is the same {@link
 * Runnable}-parameterized extraction shape as {@link #showPaginatedList} — but unlike {@code
 * showPaginatedList} (three call sites total across both controllers), each {@code
 * renderXValidationErrors} it replaced had exactly one caller, {@code create()}; wrapping this call
 * in its own private method would have re-created the identical duplicate one layer up (both
 * wrappers took the same five parameters and did nothing else), so each controller's own {@code
 * create()} calls this method directly instead.
 */
@SuppressWarnings("PMD.LongVariable")
final class DashboardControllerSupport {

  // HTMX's own request header (https://htmx.org/reference/#request_headers).
  private static final String HX_REQUEST_HEADER = "HX-Request";

  private DashboardControllerSupport() {}

  /**
   * {@code ownerPlatformAccountId}/{@code organizationName} — see {@link
   * #requireOwnedOrganization}.
   */
  /* package */ record OwnedOrganization(UUID ownerPlatformAccountId, String organizationName) {}

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

  // The exact two-statement sequence every handler method in both controllers repeated at its own
  // call site — see this class's own 2026-09-13 Javadoc addendum above for why this one call now
  // replaces it.
  /* package */ static OwnedOrganization requireOwnedOrganization(
      final HttpServletRequest request,
      final UUID organizationId,
      final CurrentPlatformAccountResolver currentPlatformAccount,
      final OrganizationForPlatformAccountResolver organizationResolver) {
    final UUID ownerPlatformAccountId =
        requireCurrentPlatformAccount(request, currentPlatformAccount);
    final String organizationName =
        requireOwnedOrganizationName(organizationId, ownerPlatformAccountId, organizationResolver);
    return new OwnedOrganization(ownerPlatformAccountId, organizationName);
  }

  /**
   * The pair of view names every dashboard mutation in both controllers already branches on
   * (HTMX-fragment vs. plain full-page render) — SonarCloud (CI, 2026-09-14) flagged {@link
   * #showPaginatedList} at 8 parameters, one over its own 7-parameter ceiling, the moment this pair
   * joined the other 6. Bundled here rather than trimmed elsewhere: the same "a small ports-record
   * parameter object, not a flat parameter list" fix shape {@code PrimaryFactorLoginPorts} already
   * established for an identical finding (TD-ARCH-016, technical-debt-register.md §6) — {@code
   * fragmentView}/{@code fullView} are a genuine unit (neither means anything without the other),
   * unlike the method's other 6 parameters, which are each a distinct, unrelated collaborator.
   */
  /* package */ record PaginatedViewNames(String fragmentView, String fullView) {}

  // The exact showList body both controllers repeated — see this class's own 2026-09-14 Javadoc
  // addendum above. renderList is invoked with the OwnedOrganization this method already resolved
  // (so the caller never has to re-resolve organizationName itself) and the same pageRequest the
  // caller already built from its own ?after=/?before= params.
  /* package */ static String showPaginatedList(
      final HttpServletRequest request,
      final UUID organizationId,
      final CurrentPlatformAccountResolver currentPlatformAccount,
      final OrganizationForPlatformAccountResolver organizationResolver,
      final KeysetPageRequest pageRequest,
      final BiConsumer<OwnedOrganization, KeysetPageRequest> renderList,
      final PaginatedViewNames viewNames) {
    final OwnedOrganization owned =
        requireOwnedOrganization(
            request, organizationId, currentPlatformAccount, organizationResolver);
    renderList.accept(owned, pageRequest);
    return isHtmxRequest(request) ? viewNames.fragmentView() : viewNames.fullView();
  }

  // SDE-III review, 2026-09-15: removing this package's own former
  // requireClientIdBelongsToOrganization (see this class's own addendum above) left
  // PlatformOAuthClientController#renderOAuthClientsValidationErrors and
  // PlatformOrganizationClientController#renderSecretKeysValidationErrors as the two controllers'
  // only remaining near-identical bodies — provably duplicated (local pmd:cpd-check, CI) the
  // moment nothing else stood between them and this class's own prior showPaginatedList
  // extraction. Same "BiConsumer for the one genuinely controller-specific step" shape as that
  // extraction: populateModel is each caller's own populateHeaderModel+populateClientsModel pair
  // (its own private methods, genuinely different per controller — this method has no business
  // knowing about either), only ever run when bindingResult actually has errors.
  // Two genuinely distinct exits (no errors / errors) — same rationale every other multi-exit
  // handler in this module documents for this exact suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  /* package */ static Optional<String> renderValidationErrorsIfAny(
      final HttpServletRequest request,
      final BindingResult bindingResult,
      final Runnable populateModel,
      final PaginatedViewNames viewNames) {
    if (!bindingResult.hasErrors()) {
      return Optional.empty();
    }
    populateModel.run();
    return Optional.of(isHtmxRequest(request) ? viewNames.fragmentView() : viewNames.fullView());
  }

  /**
   * {@code owned} — always resolved, whether or not {@code validationErrorView} is present, since a
   * caller that gets past this call still needs it (e.g. for {@code ownerPlatformAccountId}) — see
   * {@link #requireOwnedOrganizationOrRenderValidationErrors}.
   */
  /* package */ record OwnershipOrValidationErrorView(
      OwnedOrganization owned, Optional<String> validationErrorView) {}

  // SDE-III review, 2026-09-15 (second pass): extracting renderValidationErrorsIfAny alone still
  // left each controller's own create() with an identical requireOwnedOrganization-then-
  // renderValidationErrorsIfAny preamble — the same class of duplication one call site smaller,
  // not gone. This combines both into the one call create() actually needs, Consumer<
  // OwnedOrganization> rather than Runnable since populateModelOnError only has organizationName
  // once this method itself has resolved owned — the caller can't close over it any earlier.
  @SuppressWarnings("java:S107") // one parameter per collaborating port/value this genuinely
  // needs — same rationale as every other multi-collaborator method in this codebase.
  /* package */ static OwnershipOrValidationErrorView
      requireOwnedOrganizationOrRenderValidationErrors(
          final HttpServletRequest request,
          final UUID organizationId,
          final CurrentPlatformAccountResolver currentPlatformAccount,
          final OrganizationForPlatformAccountResolver organizationResolver,
          final BindingResult bindingResult,
          final Consumer<OwnedOrganization> populateModelOnError,
          final PaginatedViewNames viewNames) {
    final OwnedOrganization owned =
        requireOwnedOrganization(
            request, organizationId, currentPlatformAccount, organizationResolver);
    final Optional<String> validationErrorView =
        renderValidationErrorsIfAny(
            request, bindingResult, () -> populateModelOnError.accept(owned), viewNames);
    return new OwnershipOrValidationErrorView(owned, validationErrorView);
  }
}
