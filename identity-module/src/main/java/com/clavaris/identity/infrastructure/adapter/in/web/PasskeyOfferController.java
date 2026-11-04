package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.domain.model.AccountId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * ADR-0039: the page that offers a passkey after an emailed-code or link sign-in. It sits next to,
 * not under, {@code /account/passkeys} so that the shared {@code webauthn-register.js}, whose
 * endpoints are relative ({@code passkeys/registration/start}), reaches the existing registration
 * endpoints unchanged. It requires a signed-in session like the rest of {@code /account/**}.
 *
 * <p>Both ways out go to the URL parked in the session by {@link PasskeyOffer}; neither takes a
 * destination from the request.
 */
@Controller
@RequestMapping("/o/{organizationId}" + PasskeyOffer.OFFER_PATH)
public class PasskeyOfferController {

  private static final String VIEW = "identity/account/passkey-offer";
  private static final String REDIRECT_PREFIX = "redirect:";

  private final PasskeyOffer offer;
  private final CurrentAccountResolver currentAccount;

  public PasskeyOfferController(
      final PasskeyOffer offer, final CurrentAccountResolver currentAccount) {
    this.offer = offer;
    this.currentAccount = currentAccount;
  }

  @GetMapping
  public String show(
      @PathVariable final UUID organizationId,
      final HttpServletRequest request,
      final Model model) {
    final AccountId accountId =
        CurrentSessionSupport.requireResolved(currentAccount.resolve(request), "Account");
    // Registering a passkey reloads this page; having one is the signal to carry on.
    if (offer.hasPasskey(accountId)) {
      return REDIRECT_PREFIX + next(request, organizationId);
    }
    model.addAttribute("organizationId", organizationId);
    return VIEW;
  }

  @PostMapping("/skip")
  public String skip(@PathVariable final UUID organizationId, final HttpServletRequest request) {
    return REDIRECT_PREFIX + next(request, organizationId);
  }

  @PostMapping("/dismiss")
  public String dismiss(
      @PathVariable final UUID organizationId,
      final HttpServletRequest request,
      final HttpServletResponse response) {
    PasskeyOfferCookie.dismiss(request, response, organizationId);
    return REDIRECT_PREFIX + next(request, organizationId);
  }

  // Taken out of the session so a second visit cannot replay it; a visit with nothing parked
  // (opened by hand, or already used) lands on the passkeys page rather than nowhere.
  private static String next(final HttpServletRequest request, final UUID organizationId) {
    final HttpSession session = request.getSession(false);
    final String parked =
        session == null
            ? null
            : (String) session.getAttribute(PasskeyOfferPendingState.NEXT_ATTRIBUTE);
    if (session != null) {
      session.removeAttribute(PasskeyOfferPendingState.NEXT_ATTRIBUTE);
    }
    return parked == null ? "/o/" + organizationId + "/account/passkeys" : parked;
  }
}
