package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.authenticateplatformaccountwithpassword.AuthenticatePlatformAccountWithPasswordCommand;
import com.clavaris.identity.application.usecase.authenticateplatformaccountwithpassword.AuthenticatePlatformAccountWithPasswordUseCase;
import com.clavaris.identity.application.usecase.authenticateplatformaccountwithpassword.InvalidPlatformCredentialsException;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.PlatformAccountId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * ADR-0012: the hosted login page for a {@code PlatformAccount} — a plain authenticated {@code
 * HttpSession}, not an OAuth token issuance (see {@link PlatformAuthenticatedSessionEstablisher}'s
 * own Javadoc for why). Same "doesn't touch Spring Security's own types directly" split as {@link
 * LoginController}.
 */
@Controller
@RequestMapping("/platform/login")
public class PlatformLoginController {

  private static final String FORM_VIEW = "identity/platform/login";

  private final AuthenticatePlatformAccountWithPasswordUseCase useCase;
  private final PlatformAuthenticatedSessionEstablisher sessions;

  public PlatformLoginController(
      final AuthenticatePlatformAccountWithPasswordUseCase useCase,
      final PlatformAuthenticatedSessionEstablisher sessions) {
    this.useCase = useCase;
    this.sessions = sessions;
  }

  @GetMapping
  public String showForm(final Model model) {
    model.addAttribute("form", new PlatformLoginForm());
    return FORM_VIEW;
  }

  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping
  public String login(
      @Valid @ModelAttribute("form") final PlatformLoginForm form,
      final BindingResult bindingResult,
      final HttpServletRequest request,
      final HttpServletResponse response,
      final Model model) {
    if (bindingResult.hasErrors()) {
      return FORM_VIEW;
    }

    final PlatformAccountId accountId;
    try {
      accountId =
          useCase.handle(
              new AuthenticatePlatformAccountWithPasswordCommand(
                  new Email(form.getEmail()), form.getPassword()));
    } catch (final InvalidPlatformCredentialsException _) {
      model.addAttribute("loginError", true);
      return FORM_VIEW;
    }

    final String redirectTarget =
        sessions.establish(request, response, accountId.value(), "/platform/dashboard");
    return "redirect:" + redirectTarget;
  }
}
