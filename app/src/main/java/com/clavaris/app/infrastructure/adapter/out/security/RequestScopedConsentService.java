package com.clavaris.app.infrastructure.adapter.out.security;

import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * OIDC Core 3.1.2.1: {@code prompt=consent} asks the End-User for consent again even for scopes
 * already approved. Deleting the stored consent when the request arrives would let any page that
 * can make the browser issue that request wipe a visitor's consents, so instead the stored consent
 * is ignored for that one request only: the authorization endpoint sees none, shows the consent
 * page, and the approval it posts replaces the stored one. If the End-User abandons the page, the
 * consent they had is still there.
 *
 * <p>The authorization filter marks the request ({@link #IGNORE_STORED_CONSENT_ATTRIBUTE}); a
 * request without the mark, such as the consent form's own submission, reads the stored consent as
 * usual. Saving and removing always go straight to the store.
 */
// PMD.LongVariable: the attribute name says what it marks.
@SuppressWarnings("PMD.LongVariable")
public final class RequestScopedConsentService implements OAuth2AuthorizationConsentService {

  /** Request attribute set by the authorization filter for a {@code prompt=consent} request. */
  public static final String IGNORE_STORED_CONSENT_ATTRIBUTE =
      "clavaris.oidc.ignore-stored-consent";

  private final OAuth2AuthorizationConsentService delegate;

  public RequestScopedConsentService(final OAuth2AuthorizationConsentService delegate) {
    this.delegate = delegate;
  }

  @Override
  public void save(final OAuth2AuthorizationConsent authorizationConsent) {
    delegate.save(authorizationConsent);
  }

  @Override
  public void remove(final OAuth2AuthorizationConsent authorizationConsent) {
    delegate.remove(authorizationConsent);
  }

  @Override
  public OAuth2AuthorizationConsent findById(
      final String registeredClientId, final String principalName) {
    return ignoredForThisRequest() ? null : delegate.findById(registeredClientId, principalName);
  }

  private static boolean ignoredForThisRequest() {
    final RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
    return attributes != null
        && Boolean.TRUE.equals(
            attributes.getAttribute(
                IGNORE_STORED_CONSENT_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST));
  }
}
