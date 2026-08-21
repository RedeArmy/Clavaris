package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.authenticatewithpassword.AuthenticateWithPasswordCommand;
import com.clavaris.identity.application.usecase.authenticatewithpassword.AuthenticateWithPasswordUseCase;
import com.clavaris.identity.application.usecase.authenticatewithpassword.InvalidCredentialsException;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The hosted login page for the interactive Authorization Code flow (ADR-0010 §5) — this is what
 * {@code app}'s {@code OrganizationAuthorizationServerConfig} redirects an unauthenticated {@code
 * /oauth2/authorize} request to. Same "{@code organizationId} from the path, never a form field"
 * rationale as {@link RegisterAccountController}: BR-ORG-02 requires this screen to only ever be
 * capable of authenticating against the one Organization its own URL already names.
 *
 * <p>Deliberately does not touch Spring Security's own {@code SecurityContext}/{@code
 * Authentication} types directly — see {@link AuthenticatedSessionEstablisher}'s own Javadoc for
 * why that's a bridge to {@code app}, not something this module does itself.
 */
@Controller
@RequestMapping("/o/{organizationId}/login")
public class LoginController {

  private static final String FORM_VIEW = "identity/login";

  private final AuthenticateWithPasswordUseCase useCase;
  private final AuthenticatedSessionEstablisher sessions;

  public LoginController(
      final AuthenticateWithPasswordUseCase useCase,
      final AuthenticatedSessionEstablisher sessions) {
    this.useCase = useCase;
    this.sessions = sessions;
  }

  @GetMapping
  public String showForm(@PathVariable final UUID organizationId, final Model model) {
    model.addAttribute("form", new LoginForm());
    return FORM_VIEW;
  }

  // Two independent rejection reasons (form validation, invalid credentials) each need their own
  // exit — same rationale as RegisterAccountController's own suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping
  public String login(
      @PathVariable final UUID organizationId,
      @Valid @ModelAttribute("form") final LoginForm form,
      final BindingResult bindingResult,
      final HttpServletRequest request,
      final HttpServletResponse response,
      final Model model) {
    if (bindingResult.hasErrors()) {
      return FORM_VIEW;
    }

    final AccountId accountId;
    try {
      accountId =
          useCase.handle(
              new AuthenticateWithPasswordCommand(
                  new OrganizationId(organizationId),
                  new Email(form.getEmail()),
                  form.getPassword()));
    } catch (final InvalidCredentialsException _) {
      // Deliberately one single, generic, form-level error — never field-scoped (that would
      // itself leak "the email field was fine, it was the password" or vice versa), matching
      // InvalidCredentialsException's own anti-enumeration design.
      model.addAttribute("loginError", true);
      return FORM_VIEW;
    }

    final String fallbackUrl = "/o/" + organizationId + "/login?authenticated";
    final String redirectTarget =
        sessions.establish(request, response, accountId.value(), fallbackUrl);
    return "redirect:" + redirectTarget;
  }
}
