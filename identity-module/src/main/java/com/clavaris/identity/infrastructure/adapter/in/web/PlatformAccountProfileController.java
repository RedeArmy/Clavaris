package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.listactivesessionsforplatformaccount.ActivePlatformAccountSession;
import com.clavaris.identity.application.usecase.listactivesessionsforplatformaccount.ListActiveSessionsForPlatformAccountQuery;
import com.clavaris.identity.application.usecase.listactivesessionsforplatformaccount.ListActiveSessionsForPlatformAccountUseCase;
import com.clavaris.identity.application.usecase.listconnectedaccountsforplatformaccount.ListConnectedAccountsForPlatformAccountUseCase;
import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.application.usecase.removeplatformaccountprofilepicture.RemovePlatformAccountProfilePictureUseCase;
import com.clavaris.identity.application.usecase.updateaccountprofilepicture.InvalidProfilePictureException;
import com.clavaris.identity.application.usecase.updateplatformaccountprofilepicture.UpdatePlatformAccountProfilePictureCommand;
import com.clavaris.identity.application.usecase.updateplatformaccountprofilepicture.UpdatePlatformAccountProfilePictureUseCase;
import com.clavaris.identity.domain.model.PlatformAccount;
import com.clavaris.identity.domain.model.PlatformAccountId;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * ADR-0026: the "Manage account" self-service page for a {@code PlatformAccount} — {@link
 * AccountProfileController}'s platform-tier sibling, with a Profile tab (name, picture, email,
 * connected accounts) and a Security tab (password, active devices) matching the Clerk "Manage
 * account" reference screenshots this feature was built from. Reachable two ways with the exact
 * same content either way: a real, directly-navigable page ({@code GET /platform/account}) and an
 * HTMX-loaded fragment ({@code HX-Request} present) that {@code dashboard-nav.html}'s own "Manage
 * account" trigger lazy-loads into a {@code <dialog>} shell — same dual-mode shape {@code
 * PlatformRateLimitPolicyController}'s own Javadoc documents for an identical split.
 *
 * <p><b>Live UX bug, 2026-09-22:</b> every mutating form here (name update, picture upload/remove)
 * used to be a plain POST with a real redirect, deliberately not HTMX-intercepted, on the reasoning
 * that a submit from inside the dialog doing a normal full page navigation to this same
 * controller's own standalone page was a simple, safe fallback needing no extra "keep the dialog
 * open across a redirect" machinery. Confirmed live that this reasoning was wrong in practice: a
 * real user expects "Save" inside a dialog to stay inside that dialog, not silently kick them out
 * to a full-page view they never asked to navigate to. Each mutating handler below now checks
 * {@link #isHtmxRequest(HttpServletRequest)} the same way {@link #show} already did — an htmx
 * submit re-renders {@link #PROFILE_FRAGMENT} directly (success/error state carried as ordinary
 * model attributes, since there is no fresh GET/query-string to carry it on {@code ?updated} the
 * old redirect-based flow relied on); a real, direct POST to this controller's own standalone page
 * (no {@code HX-Request} header — reachable if a caller submits this form with JavaScript disabled,
 * or scripts it directly) keeps the exact old redirect-then-GET behavior, unchanged. "Active
 * devices" is read-only here; revoking a session stays on {@link
 * PlatformAccountSessionsController}'s own already-working standalone page, linked from the
 * Security tab, not duplicated.
 */
// PMD.LongVariable: currentPlatformAccount names exactly what it is — same convention every other
// controller in this codebase using CurrentPlatformAccountResolver already establishes.
// PMD.AvoidFieldNameMatchingMethodName: removePicture (the field) and removePicture() (the
// @PostMapping handler) name the same real concept — same rationale AccountProfileController's
// own identical suppression documents.
@SuppressWarnings({"PMD.LongVariable", "PMD.AvoidFieldNameMatchingMethodName"})
@Controller
@RequestMapping("/platform/account")
public class PlatformAccountProfileController {

  private static final String HX_REQUEST_HEADER = "HX-Request";
  private static final String PROFILE_VIEW = "identity/platform/manage-account";
  private static final String PROFILE_FRAGMENT = PROFILE_VIEW + " :: content";

  private final PlatformAccountRepository accounts;
  private final ListConnectedAccountsForPlatformAccountUseCase listConnectedAccounts;
  private final ListActiveSessionsForPlatformAccountUseCase listSessions;
  private final UpdatePlatformAccountProfilePictureUseCase updatePicture;
  private final RemovePlatformAccountProfilePictureUseCase removePicture;
  private final CurrentPlatformAccountResolver currentPlatformAccount;

  @SuppressWarnings("java:S107") // one parameter per collaborating port — same rationale as every
  // other multi-collaborator constructor in this codebase.
  public PlatformAccountProfileController(
      final PlatformAccountRepository accounts,
      final ListConnectedAccountsForPlatformAccountUseCase listConnectedAccounts,
      final ListActiveSessionsForPlatformAccountUseCase listSessions,
      final UpdatePlatformAccountProfilePictureUseCase updatePicture,
      final RemovePlatformAccountProfilePictureUseCase removePicture,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.accounts = accounts;
    this.listConnectedAccounts = listConnectedAccounts;
    this.listSessions = listSessions;
    this.updatePicture = updatePicture;
    this.removePicture = removePicture;
    this.currentPlatformAccount = currentPlatformAccount;
  }

  @GetMapping
  public String show(final HttpServletRequest request, final Model model) {
    populateModel(model, requireCurrentPlatformAccount(request));
    return isHtmxRequest(request) ? PROFILE_FRAGMENT : PROFILE_VIEW;
  }

  // PMD.OnlyOneReturn: two real, distinct exit paths (htmx re-render vs. classic redirect) — same
  // rationale uploadPicture's own identical suppression below documents.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping("/profile")
  public String updateProfile(
      @RequestParam(required = false) final String firstName,
      @RequestParam(required = false) final String lastName,
      final HttpServletRequest request,
      final Model model) {
    final PlatformAccountId platformAccountId = requireCurrentPlatformAccount(request);
    final PlatformAccount account = requireAccount(platformAccountId);
    account.updateProfile(blankToNull(firstName), blankToNull(lastName));
    accounts.save(account);
    if (isHtmxRequest(request)) {
      populateModel(model, platformAccountId);
      model.addAttribute("updated", true);
      return PROFILE_FRAGMENT;
    }
    return "redirect:/platform/account?updated";
  }

  // PMD.OnlyOneReturn: three real, distinct exit paths (validation error, htmx re-render, classic
  // redirect) — same rationale AccountProfileController's own identical suppression documents.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping("/picture")
  public String uploadPicture(
      @RequestParam("file") final MultipartFile file,
      final HttpServletRequest request,
      final Model model) {
    final PlatformAccountId platformAccountId = requireCurrentPlatformAccount(request);
    try {
      updatePicture.handle(
          new UpdatePlatformAccountProfilePictureCommand(
              platformAccountId, readBytes(file), file.getContentType()));
    } catch (final InvalidProfilePictureException e) {
      populateModel(model, platformAccountId);
      model.addAttribute("uploadError", e.getMessage());
      return isHtmxRequest(request) ? PROFILE_FRAGMENT : PROFILE_VIEW;
    }
    if (isHtmxRequest(request)) {
      populateModel(model, platformAccountId);
      model.addAttribute("updated", true);
      return PROFILE_FRAGMENT;
    }
    return "redirect:/platform/account?updated";
  }

  // PMD.OnlyOneReturn: two real, distinct exit paths (htmx re-render vs. classic redirect) — same
  // rationale updateProfile's own identical suppression above documents.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping("/picture/remove")
  public String removePicture(final HttpServletRequest request, final Model model) {
    final PlatformAccountId platformAccountId = requireCurrentPlatformAccount(request);
    removePicture.handle(platformAccountId);
    if (isHtmxRequest(request)) {
      populateModel(model, platformAccountId);
      model.addAttribute("removed", true);
      return PROFILE_FRAGMENT;
    }
    return "redirect:/platform/account?removed";
  }

  private void populateModel(final Model model, final PlatformAccountId platformAccountId) {
    model.addAttribute("account", requireAccount(platformAccountId));
    model.addAttribute("connectedAccounts", listConnectedAccounts.handle(platformAccountId));

    final List<ActivePlatformAccountSession> sessions =
        listSessions.handle(new ListActiveSessionsForPlatformAccountQuery(platformAccountId));
    model.addAttribute("sessions", sessions);
    model.addAttribute(
        "friendlyDeviceLabels",
        sessions.stream()
            .collect(
                Collectors.toMap(
                    ActivePlatformAccountSession::sessionId,
                    session -> UserAgentLabel.friendly(session.userAgent()))));
  }

  private PlatformAccount requireAccount(final PlatformAccountId platformAccountId) {
    return accounts
        .findById(platformAccountId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  // Not expected to ever actually be empty — app's own security chain guarantees an authenticated
  // PlatformAccount before this controller runs — see PlatformAccountSessionsController's own
  // identical rationale.
  private PlatformAccountId requireCurrentPlatformAccount(final HttpServletRequest request) {
    return CurrentSessionSupport.requireResolved(
        currentPlatformAccount.resolve(request), "PlatformAccount");
  }

  private static boolean isHtmxRequest(final HttpServletRequest request) {
    return "true".equals(request.getHeader(HX_REQUEST_HEADER));
  }

  private static String blankToNull(final String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  private byte[] readBytes(final MultipartFile file) {
    try {
      return file.getBytes();
    } catch (final IOException e) {
      throw new UncheckedIOException("Failed to read the uploaded profile picture", e);
    }
  }
}
