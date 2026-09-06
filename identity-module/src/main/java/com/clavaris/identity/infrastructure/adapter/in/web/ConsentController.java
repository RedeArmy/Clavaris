package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.resolveclientbranding.ClientBrandingProvider;
import com.clavaris.identity.application.usecase.resolveorganizationforclient.OrganizationForClientResolver;
import com.clavaris.identity.domain.model.OrganizationId;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * TD-SEC-011 (2026-09-06, SDE-III implementation pass): the project-owned replacement for Spring
 * Authorization Server's own default, unbranded consent page — see this class's own header comment
 * trail in {@code technical-debt-register.md} for the full investigation this closes.
 *
 * <p><b>Deliberately a flat {@code /oauth2/consent} path, never {@code
 * /o/{organizationId}/oauth2/consent}</b> — decompiling {@code
 * OAuth2AuthorizationEndpointConfigurer}/{@code OAuth2ConfigurerUtils#withMultipleIssuersPattern}
 * confirmed {@code consentPage(...)} is wired verbatim into {@code
 * OAuth2AuthorizationEndpointFilter#setConsentPage} with zero per-request templating — it is one
 * single literal shared by every Organization on {@code OrganizationAuthorizationServerConfig}'s
 * one chain, unlike {@code tokenEndpoint}/{@code jwkSetEndpoint}, whose own endpoint URIs SAS
 * itself wraps in a real {@code /**} wildcard pattern before matching (multi-issuer mode). This
 * page therefore resolves the Organization the other way around — from {@code client_id}, via
 * {@link OrganizationForClientResolver} — rather than reading it off the path like every sibling
 * controller in this package does.
 *
 * <p><b>Never handles the consent decision itself.</b> The rendered form's own {@code action} posts
 * directly to the real, tenant-scoped {@code /o/{organizationId}/oauth2/authorize} — SAS's own
 * consent-approval {@code RequestMatcher} is built from {@code authorizationEndpointUri} (wrapped
 * in multi-issuer mode into the wildcard pattern {@code /**&#47;oauth2/authorize}), not from {@code
 * consentPage}, so that URL is already exactly what SAS itself recognizes as a consent POST for
 * that Organization's own issuer — confirmed by reading {@code
 * OAuth2AuthorizationEndpointFilter#createAuthorizationConsentMatcher} directly. This controller's
 * only job is the GET render.
 *
 * <p>{@code openid} is deliberately excluded from the rendered scope checkboxes, matching SAS's own
 * {@code DefaultConsentPage} (decompiled: {@code OAuth2AuthorizationConsentAuthenticationProvider}
 * auto-grants it once anything else is approved) — a client requesting only {@code openid} has
 * nothing to actually show here.
 */
// PMD.LongVariable: organizationForClient/clientBrandingProvider (field + constructor param each)
// are long by design, not accidentally — same class-level-suppression precedent as
// LoginController's own identical rationale.
@SuppressWarnings("PMD.LongVariable")
@Controller
@RequestMapping("/oauth2/consent")
public class ConsentController {

  private static final String CONSENT_VIEW = "identity/consent";
  private static final String ERROR_VIEW = "identity/consent-error";

  // The one scope SAS itself never asks the resource owner to explicitly approve — see this
  // class's own Javadoc.
  private static final String OPENID_SCOPE = "openid";

  private final OrganizationForClientResolver organizationForClient;
  private final ClientBrandingProvider clientBrandingProvider;

  /* package */ ConsentController(
      final OrganizationForClientResolver organizationForClient,
      final ClientBrandingProvider clientBrandingProvider) {
    this.organizationForClient = organizationForClient;
    this.clientBrandingProvider = clientBrandingProvider;
  }

  // Two independent exits (unresolvable client_id / a resolved render) — same rationale as every
  // other early-return chain in this codebase (e.g. RedirectUrlResolverBridge's own suppression).
  @SuppressWarnings("PMD.OnlyOneReturn")
  @GetMapping
  public String showConsent(
      // OAuth2's own spec parameter name (snake_case) — SAS's own sendAuthorizationConsent()
      // appends exactly this, client_id/state/scope, nothing else; never this project's own
      // camelCase "clientId" convention used on the login page's own URL.
      @RequestParam("client_id") final String clientId,
      @RequestParam final String state,
      @RequestParam(required = false) final String scope,
      final Model model,
      final HttpServletResponse response) {
    final Optional<OrganizationId> organizationId = organizationForClient.resolve(clientId);
    if (organizationId.isEmpty()) {
      // Not a real consent flow — SAS itself never reaches this page without first validating
      // client_id against a real RegisteredClient. A generic 400 here, no detail on which check
      // failed, same anti-enumeration posture as every other rejection in this codebase.
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      return ERROR_VIEW;
    }

    model.addAttribute("clientId", clientId);
    model.addAttribute("state", state);
    model.addAttribute("authorizeUri", "/o/" + organizationId.get().value() + "/oauth2/authorize");
    model.addAttribute("scopes", requestedScopesExcludingOpenid(scope));
    model.addAttribute(
        "branding", clientBrandingProvider.brandingFor(organizationId.get(), clientId));
    return CONSENT_VIEW;
  }

  // "Blank scope"/"resolved list" are two independent, equally valid exits — same rationale as
  // every other early-return chain in this codebase.
  @SuppressWarnings("PMD.OnlyOneReturn")
  private static List<String> requestedScopesExcludingOpenid(final String scope) {
    if (scope == null || scope.isBlank()) {
      return List.of();
    }
    return Arrays.stream(scope.split(" ")).filter(s -> !OPENID_SCOPE.equals(s)).toList();
  }
}
