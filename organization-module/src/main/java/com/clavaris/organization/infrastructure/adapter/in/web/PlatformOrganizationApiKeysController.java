package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.organization.application.usecase.getorganizationapikeys.GetOrganizationApiKeysUseCase;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountQuery;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountUseCase;
import com.clavaris.organization.domain.model.Organization;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

/**
 * The dashboard's own read-only counterpart to {@code GetOrganizationApiKeysController} (the {@code
 * GET /api/v1/admin/organizations/{organizationId}/api-keys} REST endpoint, {@code
 * PlatformClient}-gated) — same {@link GetOrganizationApiKeysUseCase}, a second,
 * session-authenticated caller, not a second implementation. Mirrors
 * https://clerk.com/docs/guides/development/clerk-environment-variables, same as that use case's
 * own Javadoc documents.
 *
 * <p>Deliberately GET-only: every field {@link GetOrganizationApiKeysUseCase} returns is either
 * derived (the publishable key, the Frontend/Backend API URLs) or a read of infrastructure another
 * page already owns and mutates (the JWKS public key — see the "Signing Keys" page for rotation).
 * There is nothing on this page for an Organization owner to change, so unlike every sibling
 * Configure page in this codebase, there is no {@code POST} and no HTMX fragment target — adding
 * either would be plumbing this page has no use for yet.
 *
 * <p>{@code organizationId} resolves through {@link GetOrganizationForPlatformAccountUseCase}
 * first, same anti-enumeration posture as every other dashboard controller: an organizationId
 * belonging to someone else's Organization resolves identically to "doesn't exist," a 404.
 */
@SuppressWarnings("PMD.LongVariable") // same precedent as PlatformRateLimitPolicyController.
@Controller
@RequestMapping("/platform/dashboard/organizations/{organizationId}/api-keys")
public class PlatformOrganizationApiKeysController {

  private static final String VIEW = "organization/platform/organization-api-keys";
  private static final String ORGANIZATION_ID_ATTRIBUTE = "organizationId";
  private static final String ORGANIZATION_NAME_ATTRIBUTE = "organizationName";
  private static final String API_KEYS_ATTRIBUTE = "apiKeys";

  private final GetOrganizationForPlatformAccountUseCase getOrganization;
  private final GetOrganizationApiKeysUseCase getApiKeys;
  private final CurrentPlatformAccountResolver currentPlatformAccount;

  public PlatformOrganizationApiKeysController(
      final GetOrganizationForPlatformAccountUseCase getOrganization,
      final GetOrganizationApiKeysUseCase getApiKeys,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.getOrganization = getOrganization;
    this.getApiKeys = getApiKeys;
    this.currentPlatformAccount = currentPlatformAccount;
  }

  @GetMapping
  public String show(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      final Model model) {
    final UUID ownerPlatformAccountId = requireCurrentPlatformAccount(request);
    final Organization organization =
        requireOwnedOrganization(organizationId, ownerPlatformAccountId);

    model.addAttribute(ORGANIZATION_ID_ATTRIBUTE, organizationId);
    model.addAttribute(ORGANIZATION_NAME_ATTRIBUTE, organization.name());
    model.addAttribute(
        API_KEYS_ATTRIBUTE,
        getApiKeys
            .handle(organizationId)
            // Not expected on this path — requireOwnedOrganization above already confirmed
            // organizationId exists — but a loud 404 is still safer than assuming that guarantee
            // can never race with a concurrent deletion, same defensive posture every other
            // dashboard controller in this codebase already applies to its own "not expected"
            // fallback.
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
    return VIEW;
  }

  private Organization requireOwnedOrganization(
      final UUID organizationId, final UUID ownerPlatformAccountId) {
    return getOrganization
        .handle(new GetOrganizationForPlatformAccountQuery(organizationId, ownerPlatformAccountId))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  private UUID requireCurrentPlatformAccount(final HttpServletRequest request) {
    return currentPlatformAccount
        .resolve(request)
        .orElseThrow(
            () -> new IllegalStateException("No authenticated PlatformAccount on this request"));
  }
}
