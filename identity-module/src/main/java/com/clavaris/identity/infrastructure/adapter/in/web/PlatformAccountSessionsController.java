package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.listactivesessionsforplatformaccount.ActivePlatformAccountSession;
import com.clavaris.identity.application.usecase.listactivesessionsforplatformaccount.ListActiveSessionsForPlatformAccountQuery;
import com.clavaris.identity.application.usecase.listactivesessionsforplatformaccount.ListActiveSessionsForPlatformAccountUseCase;
import com.clavaris.identity.application.usecase.revokeplatformaccountsession.PlatformAccountSessionNotFoundException;
import com.clavaris.identity.application.usecase.revokeplatformaccountsession.RevokePlatformAccountSessionCommand;
import com.clavaris.identity.application.usecase.revokeplatformaccountsession.RevokePlatformAccountSessionUseCase;
import com.clavaris.identity.domain.model.PlatformAccountId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * TD-FUT-026 (closed 2026-09-02): the self-service "your devices" page for a {@code
 * PlatformAccount} — same-shaped, mechanical mirror of {@link AccountSessionsController}, against
 * {@link PlatformAccountId} rather than {@code AccountId}. No {@code organizationId} path segment
 * at all, unlike its tenant-tier mirror's cosmetic one — the platform tier has no Organization to
 * prefix with (ADR-0012).
 *
 * <p>PMD.LongVariable: {@code currentPlatformAccount} (field/constructor param) names exactly what
 * it is — same "deliberate, descriptive name over an arbitrary shortening" convention this codebase
 * applies everywhere else this rule fires.
 */
@SuppressWarnings("PMD.LongVariable")
@Controller
@RequestMapping("/platform/account/sessions")
public class PlatformAccountSessionsController {

  private static final String SESSIONS_VIEW = "identity/platform/account-sessions";

  private final ListActiveSessionsForPlatformAccountUseCase listSessions;
  private final RevokePlatformAccountSessionUseCase revokeSession;
  private final CurrentPlatformAccountResolver currentPlatformAccount;

  public PlatformAccountSessionsController(
      final ListActiveSessionsForPlatformAccountUseCase listSessions,
      final RevokePlatformAccountSessionUseCase revokeSession,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.listSessions = listSessions;
    this.revokeSession = revokeSession;
    this.currentPlatformAccount = currentPlatformAccount;
  }

  @GetMapping
  public String showSessions(final HttpServletRequest request, final Model model) {
    final PlatformAccountId platformAccountId = requireCurrentPlatformAccount(request);
    final List<ActivePlatformAccountSession> sessions =
        listSessions.handle(new ListActiveSessionsForPlatformAccountQuery(platformAccountId));
    model.addAttribute("sessions", sessions);
    // TD-FUT-024: same friendly-device-label treatment as AccountSessionsController's own
    // identical map — see UserAgentLabel's own Javadoc for why this class is shared, not
    // duplicated, across both tiers' controllers.
    model.addAttribute(
        "friendlyDeviceLabels",
        sessions.stream()
            .collect(
                Collectors.toMap(
                    ActivePlatformAccountSession::sessionId,
                    session -> UserAgentLabel.friendly(session.userAgent()))));
    final HttpSession currentSession = request.getSession(false);
    model.addAttribute("currentSessionId", currentSession == null ? null : currentSession.getId());
    return SESSIONS_VIEW;
  }

  // Deliberately empty — same benign-race rationale as AccountSessionsController's own identical
  // suppression.
  @SuppressWarnings("PMD.EmptyCatchBlock")
  @PostMapping("/{sessionId}/revoke")
  public String revoke(@PathVariable final String sessionId, final HttpServletRequest request) {
    final PlatformAccountId platformAccountId = requireCurrentPlatformAccount(request);
    try {
      revokeSession.handle(new RevokePlatformAccountSessionCommand(platformAccountId, sessionId));
    } catch (final PlatformAccountSessionNotFoundException _) {
      // Benign race — same reasoning as AccountSessionsController's own identical catch block.
    }
    return "redirect:/platform/account/sessions";
  }

  private PlatformAccountId requireCurrentPlatformAccount(final HttpServletRequest request) {
    return currentPlatformAccount
        .resolve(request)
        .orElseThrow(
            () -> new IllegalStateException("No authenticated PlatformAccount on this request"));
  }
}
