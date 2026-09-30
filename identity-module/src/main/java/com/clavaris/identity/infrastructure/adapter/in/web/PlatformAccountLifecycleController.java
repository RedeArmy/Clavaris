package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.banaccount.BanAccountCommand;
import com.clavaris.identity.application.usecase.banaccount.BanAccountUseCase;
import com.clavaris.identity.application.usecase.deleteaccount.DeleteAccountCommand;
import com.clavaris.identity.application.usecase.deleteaccount.DeleteAccountUseCase;
import com.clavaris.identity.application.usecase.getaccountfororganization.GetAccountForOrganizationUseCase;
import com.clavaris.identity.application.usecase.reactivateaccount.ReactivateAccountCommand;
import com.clavaris.identity.application.usecase.reactivateaccount.ReactivateAccountUseCase;
import com.clavaris.identity.application.usecase.suspendaccount.SuspendAccountCommand;
import com.clavaris.identity.application.usecase.suspendaccount.SuspendAccountUseCase;
import com.clavaris.identity.application.usecase.unbanaccount.UnbanAccountCommand;
import com.clavaris.identity.application.usecase.unbanaccount.UnbanAccountUseCase;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * SDE-III review, 2026-09-19 — Clerk dashboard "Users" tab Lock/Ban/Delete menu items. Each action
 * is idempotent at the domain level ({@code Account.suspend()}/{@code ban()}/etc. are no-ops
 * outside their own valid source state — see {@code AccountStatus}'s own Javadoc), so this
 * controller never needs to pre-check the current status before calling the matching use case; the
 * Users table template picks which button to show (Lock vs. Unlock, Ban vs. Unban) from {@code
 * account.status()} alone.
 *
 * <p>Every action redirects back to the Users list — same place every one of these actions is
 * triggered from (the 3-dot row menu), and the only page still guaranteed to exist after {@code
 * delete} removes the Account's own profile page from under it.
 *
 * <p>{@code AuditActor.platformAccount(...)}, not {@code platformClient(...)}: same reasoning as
 * {@link PlatformAccountImpersonationController} — this is a session-authenticated dashboard
 * operator, not a {@code PlatformClient} bearer-token caller, even though {@code
 * DeleteAccountCommand}'s own Javadoc predates this second calling tier and still only names the
 * REST admin API's.
 */
@SuppressWarnings("PMD.LongVariable")
@Controller
@RequestMapping("/platform/dashboard/organizations/{organizationId}/users/{accountId}")
public class PlatformAccountLifecycleController {

  private static final String REDIRECT_TO_USERS = "redirect:/platform/dashboard/organizations/";

  private final GetAccountForOrganizationUseCase getAccount;
  private final SuspendAccountUseCase suspendAccount;
  private final ReactivateAccountUseCase reactivateAccount;
  private final BanAccountUseCase banAccount;
  private final UnbanAccountUseCase unbanAccount;
  private final DeleteAccountUseCase deleteAccount;
  private final PlatformAccountOrganizationAccess organizationAccess;

  @SuppressWarnings("java:S107")
  public PlatformAccountLifecycleController(
      final GetAccountForOrganizationUseCase getAccount,
      final SuspendAccountUseCase suspendAccount,
      final ReactivateAccountUseCase reactivateAccount,
      final BanAccountUseCase banAccount,
      final UnbanAccountUseCase unbanAccount,
      final DeleteAccountUseCase deleteAccount,
      final OrganizationForPlatformAccountResolver organizationResolver,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.getAccount = getAccount;
    this.suspendAccount = suspendAccount;
    this.reactivateAccount = reactivateAccount;
    this.banAccount = banAccount;
    this.unbanAccount = unbanAccount;
    this.deleteAccount = deleteAccount;
    this.organizationAccess =
        new PlatformAccountOrganizationAccess(organizationResolver, currentPlatformAccount);
  }

  @PostMapping("/suspend")
  public String suspend(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID accountId) {
    final PlatformAccountOrganizationAccess.ResolvedAccountAccess access =
        organizationAccess.requireOwnedAccount(request, organizationId, accountId, getAccount);
    suspendAccount.handle(new SuspendAccountCommand(access.account().id(), actorFor(access)));
    return redirectToUsers(organizationId);
  }

  @PostMapping("/reactivate")
  public String reactivate(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID accountId) {
    final PlatformAccountOrganizationAccess.ResolvedAccountAccess access =
        organizationAccess.requireOwnedAccount(request, organizationId, accountId, getAccount);
    reactivateAccount.handle(new ReactivateAccountCommand(access.account().id(), actorFor(access)));
    return redirectToUsers(organizationId);
  }

  @PostMapping("/ban")
  public String ban(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID accountId) {
    final PlatformAccountOrganizationAccess.ResolvedAccountAccess access =
        organizationAccess.requireOwnedAccount(request, organizationId, accountId, getAccount);
    banAccount.handle(new BanAccountCommand(access.account().id(), actorFor(access)));
    return redirectToUsers(organizationId);
  }

  @PostMapping("/unban")
  public String unban(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID accountId) {
    final PlatformAccountOrganizationAccess.ResolvedAccountAccess access =
        organizationAccess.requireOwnedAccount(request, organizationId, accountId, getAccount);
    unbanAccount.handle(new UnbanAccountCommand(access.account().id(), actorFor(access)));
    return redirectToUsers(organizationId);
  }

  // TD-FUT-036 (closed): a hard, irreversible delete ("every session and token is revoked
  // immediately," this dialog's own copy) used to need only a single click-through — weaker
  // confirmation than Delete-workspace, despite comparable-or-greater blast radius. Strengthened
  // to match, not Delete-workspace weakened: typing the account's own exact current email is the
  // same "type the value on screen to confirm" tier PlatformWorkspaceController#deleteWorkspace
  // already establishes for workspace name. A mismatch redirects back to the Users list with a
  // flash-carried id (this controller always redirects, never renders — see class Javadoc — so a
  // flash attribute, not a model attribute, is what survives the redirect) instead of deleting.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping("/delete")
  public String delete(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID accountId,
      @RequestParam("confirmedEmail") final String confirmedEmail,
      final RedirectAttributes redirectAttributes) {
    final PlatformAccountOrganizationAccess.ResolvedAccountAccess access =
        organizationAccess.requireOwnedAccount(request, organizationId, accountId, getAccount);

    if (!access.account().email().value().equals(confirmedEmail)) {
      redirectAttributes.addFlashAttribute("deleteUserMismatchId", accountId);
      return redirectToUsers(organizationId);
    }

    deleteAccount.handle(new DeleteAccountCommand(access.account().id(), actorFor(access)));
    return redirectToUsers(organizationId);
  }

  private static AuditActor actorFor(
      final PlatformAccountOrganizationAccess.ResolvedAccountAccess access) {
    return AuditActor.platformAccount(access.ownerPlatformAccountId().value());
  }

  private static String redirectToUsers(final UUID organizationId) {
    return REDIRECT_TO_USERS + organizationId + "/users";
  }
}
