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
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.stereotype.Component;

/**
 * Makes Google or GitHub show their account chooser every time, when the Organization asked for it
 * on its Connections page ("Always show account selector prompt"). It adds {@code
 * prompt=select_account} to the redirect to the provider, and only to that.
 *
 * <p>Which Organization the login is for is read the way {@code
 * TenantAwareClientRegistrationResolver} reads it: from the session attribute {@code
 * SocialLoginRedirectController} set just before handing off. A platform login has none, so it is
 * never touched, and neither is an Organization that did not turn this on.
 */
// PMD.OnlyOneReturn: several guard exits (no request, no session, no organization, not enabled).
@SuppressWarnings({"PMD.OnlyOneReturn", "PMD.LongVariable"})
@Component
class AccountSelectorAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

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
    if (authorizationRequest == null || !wantsAccountSelector(authorizationRequest, request)) {
      return authorizationRequest;
    }
    return OAuth2AuthorizationRequest.from(authorizationRequest)
        .additionalParameters(parameters -> parameters.put(PROMPT, SELECT_ACCOUNT))
        .build();
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
