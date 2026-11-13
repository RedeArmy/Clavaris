package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.listwebauthncredentialsforaccount.ListWebAuthnCredentialsForAccountQuery;
import com.clavaris.identity.application.usecase.listwebauthncredentialsforaccount.ListWebAuthnCredentialsForAccountUseCase;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicyProvider;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicySnapshot;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * ADR-0039 (TD-FUT-058): after a sign-in that needed an emailed code or link, offers the person a
 * passkey for this device, so the next sign-in here is one tap and no email. The passkey is the
 * trusted-browser mechanism: it is a real credential tied to the device, where a cookie that
 * skipped the code would itself be a bearer credential standing in for authentication.
 *
 * <p>Offered once per sign-in, and only where the Organization has passkeys and the offer switched
 * on (ADR-0041), to an account with no passkey yet and a browser that has not said "don't ask
 * again". The session is already established when this runs; the offer only decides whether the
 * person sees one more page before the URL they were going to.
 */
// BR-ID-29: after an emailed code or link sign-in an account with no passkey is offered one,
// once per sign-in.
@Component
public class PasskeyOffer {

  /* package */ static final String OFFER_PATH = "/account/passkey-offer";

  private final ListWebAuthnCredentialsForAccountUseCase listCredentials;
  private final AccountAuthenticationPolicyProvider policyProvider;

  public PasskeyOffer(
      final ListWebAuthnCredentialsForAccountUseCase listCredentials,
      final AccountAuthenticationPolicyProvider policyProvider) {
    this.listCredentials = listCredentials;
    this.policyProvider = policyProvider;
  }

  /**
   * ADR-0041: whether the Organization lets its Accounts use passkeys at all and has the offer on.
   * Either switch off means the page is never shown and a visit to it carries straight on.
   */
  /* package */ boolean isEnabledFor(final UUID organizationId) {
    final AccountAuthenticationPolicySnapshot policy =
        policyProvider.policyFor(new OrganizationId(organizationId));
    return policy.passkeysEnabled() && policy.passkeyOfferEnabled();
  }

  /**
   * @return the path of the offer page when it applies, with {@code next} parked in the session;
   *     empty when the person should go straight to {@code next}
   */
  /* package */ Optional<String> interceptAfterEmailedProof(
      final HttpServletRequest request,
      final UUID organizationId,
      final AccountId accountId,
      final String next) {
    if (!isEnabledFor(organizationId)
        || PasskeyOfferCookie.isDismissed(request, organizationId)
        || hasPasskey(accountId)) {
      return Optional.empty();
    }
    final HttpSession session = request.getSession();
    session.setAttribute(PasskeyOfferPendingState.NEXT_ATTRIBUTE, next);
    return Optional.of("/o/" + organizationId + OFFER_PATH);
  }

  /* package */ boolean hasPasskey(final AccountId accountId) {
    return !listCredentials.handle(new ListWebAuthnCredentialsForAccountQuery(accountId)).isEmpty();
  }
}
