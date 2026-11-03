package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.requestpasswordreset.RequestPasswordResetCommand;
import com.clavaris.identity.application.usecase.requestpasswordreset.RequestPasswordResetUseCase;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import jakarta.validation.Valid;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The "forgot your password?" entry point — {@code organizationId} from the path, never a form
 * field, same BR-ORG-02 rationale as every other controller in this package.
 *
 * <p>Always redirects to the same pending page on a valid submission, whether or not the email
 * resolves to a real account — {@link RequestPasswordResetUseCase}'s own Javadoc explains why a
 * caller-observable difference here would be a user-enumeration oracle.
 */
@Controller
@RequestMapping("/o/{organizationId}/forgot-password")
public class ForgotPasswordController {

  private static final String FORM_VIEW = "identity/forgot-password";
  private static final String CLIENT_ID = "clientId";

  private static final Logger LOG = LoggerFactory.getLogger(ForgotPasswordController.class);

  private final RequestPasswordResetUseCase useCase;
  private final ReturnToApplicationLink returnLink;
  private final Executor emailRequestExecutor;

  public ForgotPasswordController(
      final RequestPasswordResetUseCase useCase,
      final ReturnToApplicationLink returnLink,
      final Executor emailRequestExecutor) {
    this.useCase = useCase;
    this.returnLink = returnLink;
    this.emailRequestExecutor = emailRequestExecutor;
  }

  @GetMapping
  public String showForm(
      @PathVariable final UUID organizationId,
      // Carried from the sign-in page so the confirmation that follows can offer a way back to the
      // application; the form posts to a URL that keeps it.
      @RequestParam(required = false) final String clientId,
      final Model model) {
    model.addAttribute("form", new RequestPasswordResetForm());
    model.addAttribute(CLIENT_ID, clientId);
    // Live UX bug fix, 2026-09-22: forgot-password-form.html's own shared "card" fragment now
    // builds its own explicit submit URL instead of self-submitting via an empty th:action — see
    // that fragment's own Javadoc for why. This page never runs inside any dialog (unlike the
    // platform tier's own equivalent), so organizationId is the only new thing this page needs to
    // expose.
    model.addAttribute("organizationId", organizationId);
    return FORM_VIEW;
  }

  // Same "validation failure needs its own early exit" rationale as RegisterAccountController's
  // own suppression.
  @PostMapping
  public String requestReset(
      @PathVariable final UUID organizationId,
      @Valid @ModelAttribute("form") final RequestPasswordResetForm form,
      final BindingResult bindingResult,
      @RequestParam(required = false) final String clientId,
      final Model model) {
    if (bindingResult.hasErrors()) {
      model.addAttribute("organizationId", organizationId);
      model.addAttribute(CLIENT_ID, clientId);
      return FORM_VIEW;
    }

    // Off the request thread, so how long this takes cannot say whether the address has an account
    // (an unknown one returned right after the lookup, a known one after the token insert, the
    // outbox write and a call to the mail provider). Done here, not inside the use case, because
    // the workspace member provisioning also calls that use case and needs its mail sent, and any
    // failure raised, before it carries on. A task the bounded executor rejects is logged and
    // dropped the same for every address; the person asks again.
    final RequestPasswordResetCommand command =
        new RequestPasswordResetCommand(
            new OrganizationId(organizationId), new Email(form.getEmail()));
    try {
      emailRequestExecutor.execute(() -> sendResetFor(command));
    } catch (final RejectedExecutionException _) {
      LOG.warn(
          "event=password_reset_dropped organizationId={} reason=executor_saturated",
          organizationId);
    }

    return RedirectQueryParams.appendIfPresent(
        "redirect:/o/" + organizationId + "/forgot-password/pending", CLIENT_ID, clientId);
  }

  // Runs on the executor's thread; nothing thrown here can reach, or differ in, the response.
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private void sendResetFor(final RequestPasswordResetCommand command) {
    try {
      useCase.handle(command);
    } catch (final RuntimeException failure) {
      LOG.warn(
          "event=password_reset_failed organizationId={} exceptionType={}",
          command.organizationId(),
          failure.getClass().getName());
    }
  }

  @GetMapping("/pending")
  public String pending(
      @PathVariable final UUID organizationId,
      @RequestParam(required = false) final String clientId,
      final Model model) {
    returnLink.addTo(model, organizationId, clientId);
    return "identity/forgot-password-pending";
  }
}
