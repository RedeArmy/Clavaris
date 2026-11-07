package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.requiresecondfactor.RequiresSecondFactorUseCase;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.SocialProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Optional;
import java.util.UUID;

/**
 * ADR-0040: "must this sign-in also show the authenticator code?" — asked after the primary factor
 * and every other pause, immediately before the session would be established. Same static-gate
 * shape as {@link DeviceTrustGate} and {@link SessionTaskGate}.
 *
 * <p>A passkey is exempt: it is a possession proof that already needs the device's own screen lock
 * or biometric, so asking an authenticator code on top would add friction without a second kind of
 * evidence. Every other primary factor, including an emailed code or link and a social login, is
 * followed by the code, because none of them proves anything the authenticator app does.
 */
public final class TotpGate {

  private TotpGate() {
    // Static utility.
  }

  // Same suppression and reason as DeviceTrustGate.intercept: one parameter per collaborating
  // value.
  @SuppressWarnings("java:S107")
  public static Optional<String> intercept(
      final RequiresSecondFactorUseCase requiresSecondFactor,
      final HttpServletRequest request,
      final UUID organizationId,
      final AccountId accountId,
      final PendingAuthenticationFactor factor,
      final String clientId,
      final String redirectUrl,
      final SocialProvider provider,
      final boolean deviceTrustVerified) {
    if (factor == PendingAuthenticationFactor.PASSKEY || !requiresSecondFactor.handle(accountId)) {
      return Optional.empty();
    }

    final HttpSession session = request.getSession(true);
    session.setAttribute(TotpPendingState.ACCOUNT_ID_ATTRIBUTE, accountId.value().toString());
    session.setAttribute(TotpPendingState.FACTOR_ATTRIBUTE, factor.name());
    session.setAttribute(TotpPendingState.CHALLENGE_ID_ATTRIBUTE, UUID.randomUUID().toString());
    session.setAttribute(TotpPendingState.ORGANIZATION_ID_ATTRIBUTE, organizationId.toString());
    session.setAttribute(
        TotpPendingState.DEVICE_TRUST_VERIFIED_ATTRIBUTE, String.valueOf(deviceTrustVerified));
    setOrClear(session, TotpPendingState.CLIENT_ID_ATTRIBUTE, clientId);
    setOrClear(session, TotpPendingState.REDIRECT_URL_ATTRIBUTE, redirectUrl);
    setOrClear(
        session, TotpPendingState.PROVIDER_ATTRIBUTE, provider == null ? null : provider.name());
    return Optional.of("/o/" + organizationId + "/login/totp");
  }

  /**
   * The same pause for a sign-in that is being resumed after another one (a device-trust challenge
   * or a session task): its values are read from, and cleared out of, that pause's own attributes,
   * so nothing of the earlier pause is left to be replayed once this one takes over.
   *
   * @return the path of the code page, or empty when no second factor is needed, in which case the
   *     earlier pause's attributes are left for the caller to consume as usual
   */
  @SuppressWarnings({"java:S107", "PMD.ExcessiveParameterList"})
  /* package */ static Optional<String> interceptResumed(
      final RequiresSecondFactorUseCase requiresSecondFactor,
      final HttpServletRequest request,
      final HttpSession session,
      final UUID organizationId,
      final AccountId accountId,
      final String factorAttribute,
      final String clientIdAttribute,
      final String redirectUrlAttribute,
      final String providerAttribute,
      final boolean deviceTrustVerified) {
    // TD-ARCH-045: without the earlier pause's factor there is nothing to carry over. Empty here
    // means "not this gate's business"; the caller then sees the missing factor itself and sends
    // the person back to the sign-in page instead of completing it.
    if (!(session.getAttribute(factorAttribute) instanceof String)
        || !requiresSecondFactor.handle(accountId)) {
      return Optional.empty();
    }
    final String providerName = (String) session.getAttribute(providerAttribute);
    final Optional<String> pause =
        intercept(
            requiresSecondFactor,
            request,
            organizationId,
            accountId,
            PendingAuthenticationFactor.valueOf((String) session.getAttribute(factorAttribute)),
            (String) session.getAttribute(clientIdAttribute),
            (String) session.getAttribute(redirectUrlAttribute),
            providerName == null ? null : SocialProvider.valueOf(providerName),
            deviceTrustVerified);
    if (pause.isPresent()) {
      session.removeAttribute(factorAttribute);
      session.removeAttribute(clientIdAttribute);
      session.removeAttribute(redirectUrlAttribute);
      session.removeAttribute(providerAttribute);
    }
    return pause;
  }

  // A stale value from an earlier, abandoned sign-in must not ride into this one.
  private static void setOrClear(final HttpSession session, final String name, final String value) {
    if (value == null) {
      session.removeAttribute(name);
    } else {
      session.setAttribute(name, value);
    }
  }
}
