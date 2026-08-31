package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.listactivesessionsforaccount.ListActiveSessionsForAccountQuery;
import com.clavaris.identity.application.usecase.listactivesessionsforaccount.ListActiveSessionsForAccountUseCase;
import com.clavaris.identity.application.usecase.revokeaccountsession.RevokeAccountSessionCommand;
import com.clavaris.identity.application.usecase.revokeaccountsession.RevokeAccountSessionUseCase;
import com.clavaris.identity.application.usecase.revokeaccountsession.SessionNotFoundException;
import com.clavaris.identity.domain.model.AccountId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The self-service "your devices" page a tenant {@code Account} reaches once authenticated — {@code
 * Session.java}'s own long-standing Javadoc named this exact feature as the reason it doesn't (yet)
 * track device info on the domain {@code Session} aggregate; this page is built on a different,
 * already-live mechanism instead (the hosted-login {@code HttpSession} store, via {@link
 * ListActiveSessionsForAccountUseCase}/{@link RevokeAccountSessionUseCase} — see those ports' own
 * Javadoc).
 *
 * <p>{@code organizationId} in the path is cosmetic only, kept for URL consistency with the rest of
 * this chain's {@code /o/{organizationId}/...} prefix — every query and mutation here is scoped by
 * the resolved {@link AccountId} from the security context, never by this path segment, so there is
 * no cross-Organization IDOR surface even if it's hand-edited. {@code app}'s own {@code
 * OrganizationAuthorizationServerConfig} enforces the authentication requirement; this controller
 * only ever runs once a request already carries an authenticated tenant {@code Account} session.
 */
@Controller
@RequestMapping("/o/{organizationId}/account/sessions")
public class AccountSessionsController {

  private static final String SESSIONS_VIEW = "identity/account/sessions";

  private final ListActiveSessionsForAccountUseCase listSessions;
  private final RevokeAccountSessionUseCase revokeSession;
  private final CurrentAccountResolver currentAccount;

  public AccountSessionsController(
      final ListActiveSessionsForAccountUseCase listSessions,
      final RevokeAccountSessionUseCase revokeSession,
      final CurrentAccountResolver currentAccount) {
    this.listSessions = listSessions;
    this.revokeSession = revokeSession;
    this.currentAccount = currentAccount;
  }

  @GetMapping
  public String showSessions(
      @PathVariable final UUID organizationId,
      final HttpServletRequest request,
      final Model model) {
    final AccountId accountId = requireCurrentAccount(request);
    model.addAttribute(
        "sessions", listSessions.handle(new ListActiveSessionsForAccountQuery(accountId)));
    // Lets the template label/mark the row for the browser making this very request, without this
    // controller needing to duplicate any of ActiveAccountSession's own fields to identify it.
    final HttpSession currentSession = request.getSession(false);
    model.addAttribute("currentSessionId", currentSession == null ? null : currentSession.getId());
    // So the per-row revoke form can build its own action URL without reaching into the request
    // for a path variable Thymeleaf doesn't otherwise expose to it.
    model.addAttribute("organizationId", organizationId);
    return SESSIONS_VIEW;
  }

  // Deliberately empty: SessionNotFoundException here is a benign race (see the catch block's own
  // comment below), not a bug to log or handle further — first documented use of this suppression
  // in the codebase.
  @SuppressWarnings("PMD.EmptyCatchBlock")
  @PostMapping("/{sessionId}/revoke")
  public String revoke(
      @PathVariable final UUID organizationId,
      @PathVariable final String sessionId,
      final HttpServletRequest request) {
    final AccountId accountId = requireCurrentAccount(request);
    try {
      revokeSession.handle(new RevokeAccountSessionCommand(accountId, sessionId));
    } catch (final SessionNotFoundException _) {
      // Benign race (double-submit, or the row already expired between page render and this
      // click) — redirecting back to a freshly re-rendered list is the correct outcome either
      // way, not a scary error page for a low-stakes, already-reversible action.
    }
    return "redirect:/o/" + organizationId + "/account/sessions";
  }

  // Not expected to ever actually be empty — app's own security chain guarantees an authenticated
  // tenant Account before this controller runs — but a checked, explicit failure here is still
  // safer than an unchecked NoSuchElementException, same rationale as
  // PlatformOrganizationDashboardController's own identical helper.
  private AccountId requireCurrentAccount(final HttpServletRequest request) {
    return currentAccount
        .resolve(request)
        .orElseThrow(() -> new IllegalStateException("No authenticated Account on this request"));
  }
}
