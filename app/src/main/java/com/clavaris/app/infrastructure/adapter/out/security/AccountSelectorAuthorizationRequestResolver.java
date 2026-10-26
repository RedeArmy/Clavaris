package com.clavaris.app.infrastructure.adapter.out.security;

import com.clavaris.identity.infrastructure.adapter.in.web.SocialLoginRedirectController;
import com.clavaris.organization.application.usecase.savesocialconnection.SocialConnectionSettingsRepository;
import com.clavaris.organization.domain.model.SocialConnectionSettings;
import com.clavaris.organization.domain.model.SocialProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.stereotype.Component;

/**
 * Makes Google or GitHub show their account chooser every time, when the Organization asked for it
 * on its Connections page ("Always show account selector prompt"). It adds {@code
 * prompt=select_account} to the redirect to the provider.
 *
 * <p>It also prefixes the OAuth {@code state} with the tier that started the login ({@code
 * <tag>.<random>}, the tag coming from {@code SocialLoginRedirectController}'s {@code
 * clavaris_tenant} parameter), because the provider's callback goes to one flat URL that names no
 * tenant: the session cookie serializer reads the tag back from the callback's {@code state} to
 * pick that tier's cookie. Two logins pending in two Organizations at once therefore never share a
 * session.
 *
 * <p>Which Organization the login is for is read the way {@code
 * TenantAwareClientRegistrationResolver} reads it: from the session attribute {@code
 * SocialLoginRedirectController} set just before handing off. A platform login has none, so it is
 * never touched, and neither is an Organization that did not turn this on.
 */
@Component
class AccountSelectorAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

  private static final Pattern TENANT_TAG = Pattern.compile("^(?:platform|[0-9a-f]{32})$");
  private static final String PROMPT = "prompt";
  private static final String SELECT_ACCOUNT = "select_account";

  private final OAuth2AuthorizationRequestResolver delegate;
  private final SocialConnectionSettingsRepository settings;

  /* package */ AccountSelectorAuthorizationRequestResolver(
      final ClientRegistrationRepository clientRegistrations,
      final SocialConnectionSettingsRepository settings) {
    this.delegate =
        new DefaultOAuth2AuthorizationRequestResolver(clientRegistrations, "/oauth2/authorization");
    this.settings = settings;
  }

  @Override
  public OAuth2AuthorizationRequest resolve(final HttpServletRequest request) {
    return customize(delegate.resolve(request), request);
  }

  @Override
  public OAuth2AuthorizationRequest resolve(
      final HttpServletRequest request, final String clientRegistrationId) {
    return customize(delegate.resolve(request, clientRegistrationId), request);
  }

  private OAuth2AuthorizationRequest customize(
      final OAuth2AuthorizationRequest authorizationRequest, final HttpServletRequest request) {
    if (authorizationRequest == null) {
      return null;
    }
    final OAuth2AuthorizationRequest.Builder builder =
        OAuth2AuthorizationRequest.from(authorizationRequest);
    final Optional<String> tenantTag = tenantTagOf(request);
    tenantTag.ifPresent(tag -> builder.state(tag + "." + authorizationRequest.getState()));
    if (wantsAccountSelector(authorizationRequest, request)) {
      builder.additionalParameters(parameters -> parameters.put(PROMPT, SELECT_ACCOUNT));
    }
    return tenantTag.isPresent() || wantsAccountSelector(authorizationRequest, request)
        ? builder.build()
        : authorizationRequest;
  }

  // The provider echoes the state back untouched, and the callback URL carries nothing else of
  // this application's own, so the tier that started the login rides in front of the random part:
  // "<tag>.<random>". The random part, which is what Spring Security validates, is unchanged. Only
  // a well-formed tag is copied; anything else leaves the state exactly as Spring minted it.
  private static Optional<String> tenantTagOf(final HttpServletRequest request) {
    final String tag = request.getParameter(SocialLoginRedirectController.TENANT_TAG_PARAMETER);
    return tag != null && TENANT_TAG.matcher(tag).matches() ? Optional.of(tag) : Optional.empty();
  }

  private boolean wantsAccountSelector(
      final OAuth2AuthorizationRequest authorizationRequest, final HttpServletRequest request) {
    final Object registrationId =
        authorizationRequest.getAttributes().get(OAuth2ParameterNames.REGISTRATION_ID);
    final Optional<SocialProvider> provider = providerOf(registrationId);
    final Optional<UUID> organization = organizationOf(request);
    return provider.isPresent()
        && organization.isPresent()
        && settings
            .findByOrganizationIdAndProvider(organization.get(), provider.get())
            .map(SocialConnectionSettings::alwaysShowAccountSelector)
            .orElse(false);
  }

  private static Optional<SocialProvider> providerOf(final Object registrationId) {
    try {
      return registrationId == null
          ? Optional.empty()
          : Optional.of(SocialProvider.valueOf(registrationId.toString().toUpperCase(Locale.ROOT)));
    } catch (final IllegalArgumentException _) {
      return Optional.empty();
    }
  }

  private static Optional<UUID> organizationOf(final HttpServletRequest request) {
    final HttpSession session = request.getSession(false);
    final Object raw =
        session == null
            ? null
            : session.getAttribute(SocialLoginRedirectController.ORGANIZATION_ID_SESSION_ATTRIBUTE);
    try {
      return raw == null ? Optional.empty() : Optional.of(UUID.fromString(raw.toString()));
    } catch (final IllegalArgumentException _) {
      return Optional.empty();
    }
  }
}
