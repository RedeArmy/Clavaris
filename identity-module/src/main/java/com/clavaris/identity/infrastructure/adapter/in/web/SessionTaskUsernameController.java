package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.chooseaccountusername.ChooseAccountUsernameCommand;
import com.clavaris.identity.application.usecase.chooseaccountusername.ChooseAccountUsernameUseCase;
import com.clavaris.identity.application.usecase.recordaccountlogindevice.RecordAccountLoginDeviceUseCase;
import com.clavaris.identity.application.usecase.recordloginevent.RecordLoginEventUseCase;
import com.clavaris.identity.application.usecase.registeraccount.UsernameAlreadyRegisteredException;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicyProvider;
import com.clavaris.identity.application.usecase.resolveredirecturl.RedirectUrlResolver;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.Optional;
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
 * ADR-0031: the "choose a username" session task — what a brand-new social sign-up lands on when it
 * ended without one (Google offers none, a GitHub login can be unusable). Reached only through a
 * redirect from {@link SessionTaskGate#intercept}, which pauses the login before any session
 * exists, so nothing is signed in until the person has chosen or skipped. Mirrors {@link
 * SessionTaskChallengeController}: no direct entry point, and a visit without matching pending
 * state — or with the pending state of another task — bounces to the login page.
 *
 * <p>Skipping is offered only when the Organization does not require a username ({@code
 * usernameRequired}); a skipped account can still choose one later in its profile.
 */
@Controller
@RequestMapping("/o/{organizationId}/login/session-task/username")
public class SessionTaskUsernameController {

  private static final String FORM_VIEW = "identity/session-task-username";
  private static final String FORM_ATTRIBUTE = "form";
  private static final String USERNAME_REQUIRED_ATTRIBUTE = "usernameRequired";

  private final ChooseAccountUsernameUseCase chooseUsername;
  private final AccountAuthenticationPolicyProvider policyProvider;
  private final AuthenticatedSessionEstablisher sessions;
  private final RecordAccountLoginDeviceUseCase recordLoginDevice;
  private final RedirectUrlResolver redirectUrlResolver;
  private final RecordLoginEventUseCase recordLoginEvent;

  // java:S107: one parameter per collaborating port, as SessionTaskChallengeController's.
  @SuppressWarnings("java:S107")
  public SessionTaskUsernameController(
      final ChooseAccountUsernameUseCase chooseUsername,
      final AccountAuthenticationPolicyProvider policyProvider,
      final AuthenticatedSessionEstablisher sessions,
      final RecordAccountLoginDeviceUseCase recordLoginDevice,
      final RedirectUrlResolver redirectUrlResolver,
      final RecordLoginEventUseCase recordLoginEvent) {
    this.chooseUsername = chooseUsername;
    this.policyProvider = policyProvider;
    this.sessions = sessions;
    this.recordLoginDevice = recordLoginDevice;
    this.redirectUrlResolver = redirectUrlResolver;
    this.recordLoginEvent = recordLoginEvent;
  }

  @GetMapping
  public String showForm(
      @PathVariable final UUID organizationId,
      final HttpServletRequest request,
      final Model model) {
    if (pendingAccountId(request, organizationId).isEmpty()) {
      return backToLogin(organizationId);
    }
    model.addAttribute(FORM_ATTRIBUTE, new SessionTaskUsernameForm());
    model.addAttribute(USERNAME_REQUIRED_ATTRIBUTE, usernameRequired(organizationId));
    return FORM_VIEW;
  }

  // Four exits (no pending task / validation error / name rejected / done) — each outcome needs
  // its own, as SessionTaskChallengeController's own identical suppression explains.
  @PostMapping
  public String choose(
      @PathVariable final UUID organizationId,
      @Valid @ModelAttribute(FORM_ATTRIBUTE) final SessionTaskUsernameForm form,
      final BindingResult bindingResult,
      final HttpServletRequest request,
      final HttpServletResponse response,
      final Model model) {
    final Optional<AccountId> pending = pendingAccountId(request, organizationId);
    if (pending.isEmpty()) {
      return backToLogin(organizationId);
    }
    model.addAttribute(USERNAME_REQUIRED_ATTRIBUTE, usernameRequired(organizationId));
    if (bindingResult.hasErrors()) {
      return FORM_VIEW;
    }
    final AccountId accountId = pending.get();
    try {
      chooseUsername.handle(
          new ChooseAccountUsernameCommand(
              accountId, form.getUsername(), AuditActor.account(accountId.value())));
    } catch (final UsernameAlreadyRegisteredException _) {
      bindingResult.rejectValue(
          "username", "username.alreadyRegistered", "This username is already taken");
      return FORM_VIEW;
    } catch (final IllegalArgumentException _) {
      bindingResult.rejectValue(
          "username",
          "username.invalid",
          "Username must be 3-32 characters (letters, digits, underscore, hyphen only)");
      return FORM_VIEW;
    }
    return finish(organizationId, accountId, request, response);
  }

  /**
   * Continues without a username; refused (back to the form) when the Organization requires one.
   */
  @PostMapping("/skip")
  public String skip(
      @PathVariable final UUID organizationId,
      final HttpServletRequest request,
      final HttpServletResponse response) {
    final Optional<AccountId> pending = pendingAccountId(request, organizationId);
    if (pending.isEmpty()) {
      return backToLogin(organizationId);
    }
    if (usernameRequired(organizationId)) {
      return "redirect:/o/" + organizationId + "/login/session-task/username";
    }
    return finish(organizationId, pending.get(), request, response);
  }

  // The task's own completion is the moment the session finally gets established, exactly as the
  // forced password reset's.
  private String finish(
      final UUID organizationId,
      final AccountId accountId,
      final HttpServletRequest request,
      final HttpServletResponse response) {
    final HttpSession session = request.getSession(true);
    session.removeAttribute(SessionTaskPendingState.TASK_ATTRIBUTE);
    final String redirectTarget =
        AuthenticatedSessionCompletion.completeFromPendingChallenge(
            sessions,
            recordLoginDevice,
            redirectUrlResolver,
            recordLoginEvent,
            request,
            response,
            session,
            organizationId,
            accountId,
            SessionTaskPendingState.ACCOUNT_ID_ATTRIBUTE,
            SessionTaskPendingState.FACTOR_ATTRIBUTE,
            SessionTaskPendingState.ORGANIZATION_ID_ATTRIBUTE,
            SessionTaskPendingState.CLIENT_ID_ATTRIBUTE,
            SessionTaskPendingState.REDIRECT_URL_ATTRIBUTE,
            SessionTaskPendingState.PROVIDER_ATTRIBUTE,
            // Choosing a name adds no authentication factor, so no "mfa" amr value.
            false);
    return "redirect:" + redirectTarget;
  }

  private boolean usernameRequired(final UUID organizationId) {
    return policyProvider.policyFor(new OrganizationId(organizationId)).usernameRequired();
  }

  private static String backToLogin(final UUID organizationId) {
    return "redirect:/o/" + organizationId + "/login";
  }

  // The pending state of this exact Organization AND of this task: BR-ORG-02's cross-tenant
  // check, plus ADR-0031's — another task's pause (the forced password reset) must not open this
  // page.
  private Optional<AccountId> pendingAccountId(
      final HttpServletRequest request, final UUID organizationId) {
    final HttpSession session = request.getSession(false);
    if (session == null) {
      return Optional.empty();
    }
    if (!organizationId
        .toString()
        .equals(session.getAttribute(SessionTaskPendingState.ORGANIZATION_ID_ATTRIBUTE))) {
      return Optional.empty();
    }
    if (!SessionTaskPendingState.TASK_USERNAME.equals(
        session.getAttribute(SessionTaskPendingState.TASK_ATTRIBUTE))) {
      return Optional.empty();
    }
    final Object rawAccountId = session.getAttribute(SessionTaskPendingState.ACCOUNT_ID_ATTRIBUTE);
    if (rawAccountId == null) {
      return Optional.empty();
    }
    return Optional.of(new AccountId(UUID.fromString((String) rawAccountId)));
  }
}
