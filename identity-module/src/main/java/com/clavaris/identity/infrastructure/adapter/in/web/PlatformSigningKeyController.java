package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.listsigningkeysfororganization.ListSigningKeysForOrganizationUseCase;
import com.clavaris.identity.application.usecase.rotatesigningkeyfororganization.NoActiveSigningKeyException;
import com.clavaris.identity.application.usecase.rotatesigningkeyfororganization.RotateSigningKeyForOrganizationCommand;
import com.clavaris.identity.application.usecase.rotatesigningkeyfororganization.RotateSigningKeyForOrganizationUseCase;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.PlatformAccountId;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

/**
 * ADR-0025, ADR-0010 §5.2: the dashboard's own signing-key rotation page. Every write here goes
 * through the exact same {@link RotateSigningKeyForOrganizationUseCase} the REST admin API already
 * exposes ({@code POST /api/v1/admin/organizations/{organizationId}/signing-keys/rotate}) — this
 * controller adds a second, session-authenticated {@link AuditActor#platformAccount} caller, same
 * widening {@code RotateSigningKeyForOrganizationCommand}'s own Javadoc documents (and the same
 * reasoning explains why {@code PurgeSigningKeyForOrganizationUseCase} is deliberately NOT wired
 * here at all — see this class's own Javadoc below). {@link ListSigningKeysForOrganizationUseCase}
 * is new — see that use case's own Javadoc.
 *
 * <p>There is no independent "activate" action on this page: {@code
 * ActivateSigningKeyForOrganizationUseCase} has no REST endpoint of its own either — it is purely
 * an internal orchestration primitive {@code CreateOrganization} and rotate/purge already reuse
 * (see that use case's own Javadoc). What "activation" means is visible here instead, as data: the
 * list below marks exactly one key {@code Active} (the one {@code findActiveAndRetiredSince}
 * returns with no {@code retiredAt}) and every other returned key {@code Retired (in overlap)} —
 * rotating is what changes which one that is, same as the REST API's own only way to trigger it.
 *
 * <p><b>Emergency purge (TD-SEC-029) is deliberately NOT reachable from this page</b>, unlike every
 * other write this controller exposes. It is reserved for a <em>confirmed</em> compromise, breaks
 * every currently-valid token signed under the purged key immediately (zero overlap, by design —
 * see {@code SigningKey#purgeImmediately()}'s own Javadoc), and its own command stays explicitly
 * operator-only (`PurgeSigningKeyForOrganizationCommand`'s Javadoc, unchanged by this increment).
 * An Organization owner who needs it is directed to contact support rather than given a
 * self-service button whose accidental use could lock out every one of their own
 * currently-signed-in users at once — the same "destructive action, no
 * confirmation-flow/CSRF-replay/audit-trail UI verification done yet" bar
 * `technical-debt-register.md` TD-FUT-032 already holds promote-to-production/ delete-Organization
 * to.
 *
 * <p>{@code organizationId} resolves through {@link OrganizationForPlatformAccountResolver}
 * (identity-module's own copy, bridged in {@code app}) — same anti-enumeration posture as every
 * other dashboard controller in this codebase. Rotation carries no secret to protect (the private
 * key material never leaves {@code OrganizationSigningKeyMaterialFactory}; {@code kid} is published
 * in JWKS regardless) — unlike Secret Keys/OAuth Client secrets, a successful rotation follows this
 * codebase's normal "success redirects, HTMX gets a fragment" convention, no exception needed.
 */
@SuppressWarnings("PMD.LongVariable")
@Controller
@RequestMapping("/platform/dashboard/organizations/{organizationId}/signing-keys")
public class PlatformSigningKeyController {

  private static final String LIST_VIEW = "identity/platform/organization-signing-keys";
  private static final String KEYS_FRAGMENT = LIST_VIEW + " :: keys";
  private static final String ORGANIZATION_ID_ATTRIBUTE = "organizationId";
  private static final String ORGANIZATION_NAME_ATTRIBUTE = "organizationName";

  // HTMX's own request header (https://htmx.org/reference/#request_headers) — same convention as
  // every other dashboard controller's own identical constant.
  private static final String HX_REQUEST_HEADER = "HX-Request";

  private final ListSigningKeysForOrganizationUseCase listKeys;
  private final RotateSigningKeyForOrganizationUseCase rotateKey;
  private final OrganizationForPlatformAccountResolver organizationResolver;
  private final CurrentPlatformAccountResolver currentPlatformAccount;

  public PlatformSigningKeyController(
      final ListSigningKeysForOrganizationUseCase listKeys,
      final RotateSigningKeyForOrganizationUseCase rotateKey,
      final OrganizationForPlatformAccountResolver organizationResolver,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.listKeys = listKeys;
    this.rotateKey = rotateKey;
    this.organizationResolver = organizationResolver;
    this.currentPlatformAccount = currentPlatformAccount;
  }

  @GetMapping
  public String showList(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      final Model model) {
    final PlatformAccountId ownerPlatformAccountId = requireCurrentPlatformAccount(request);
    final OrganizationId orgId = new OrganizationId(organizationId);
    final String organizationName = requireOwnedOrganizationName(orgId, ownerPlatformAccountId);
    populateHeaderModel(model, organizationId, organizationName);
    populateKeysModel(model, orgId);
    return LIST_VIEW;
  }

  // Two exits (HTMX fragment vs. plain redirect) — same rationale as every other dashboard
  // controller's own identical "after a mutation succeeds" suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping("/rotate")
  public String rotate(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      final Model model) {
    final PlatformAccountId ownerPlatformAccountId = requireCurrentPlatformAccount(request);
    final OrganizationId orgId = new OrganizationId(organizationId);
    final String organizationName = requireOwnedOrganizationName(orgId, ownerPlatformAccountId);

    try {
      rotateKey.handle(
          new RotateSigningKeyForOrganizationCommand(
              orgId, AuditActor.platformAccount(ownerPlatformAccountId.value())));
    } catch (final NoActiveSigningKeyException _) {
      // Not expected on this path — BR-ORG-06 guarantees a real Organization always has an active
      // key — but a loud 404 is still safer than assuming that invariant can never be violated.
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    if (isHtmxRequest(request)) {
      populateHeaderModel(model, organizationId, organizationName);
      populateKeysModel(model, orgId);
      return KEYS_FRAGMENT;
    }
    return "redirect:/platform/dashboard/organizations/" + organizationId + "/signing-keys";
  }

  private void populateHeaderModel(
      final Model model, final UUID organizationId, final String organizationName) {
    model.addAttribute(ORGANIZATION_ID_ATTRIBUTE, organizationId);
    model.addAttribute(ORGANIZATION_NAME_ATTRIBUTE, organizationName);
  }

  private void populateKeysModel(final Model model, final OrganizationId organizationId) {
    model.addAttribute("keys", listKeys.handle(organizationId));
  }

  private String requireOwnedOrganizationName(
      final OrganizationId organizationId, final PlatformAccountId ownerPlatformAccountId) {
    return organizationResolver
        .resolveName(organizationId, ownerPlatformAccountId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  private static boolean isHtmxRequest(final HttpServletRequest request) {
    return "true".equals(request.getHeader(HX_REQUEST_HEADER));
  }

  private PlatformAccountId requireCurrentPlatformAccount(final HttpServletRequest request) {
    return CurrentSessionSupport.requireResolved(
        currentPlatformAccount.resolve(request), "PlatformAccount");
  }
}
