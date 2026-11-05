package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.approveaccountregistration.ApproveAccountRegistrationCommand;
import com.clavaris.identity.application.usecase.approveaccountregistration.ApproveAccountRegistrationUseCase;
import com.clavaris.identity.application.usecase.getaccountfororganization.GetAccountForOrganizationUseCase;
import com.clavaris.identity.application.usecase.listpendingaccountsfororganization.ListPendingAccountsForOrganizationQuery;
import com.clavaris.identity.application.usecase.listpendingaccountsfororganization.ListPendingAccountsForOrganizationUseCase;
import com.clavaris.identity.application.usecase.rejectaccountregistration.RejectAccountRegistrationCommand;
import com.clavaris.identity.application.usecase.rejectaccountregistration.RejectAccountRegistrationUseCase;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.PlatformAccountId;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * TD-FUT-019, Clerk "waitlist/approval" parity: the dashboard's own approval queue for an
 * Organization that holds self-registrations until an operator decides (BR-ID-24). Until now the
 * only way to see or decide one was the admin API; the Users list showed such an Account with no
 * action at all.
 *
 * <p>Ownership is checked the way every other account action on the dashboard checks it: the
 * Organization must be the caller's, and the Account must belong to it, so a crafted id cannot
 * decide another tenant's registration. Approving and rejecting are the same use cases the API
 * calls, so the audit entry, the outbox event and the idempotence (deciding an Account that is no
 * longer pending changes nothing) are not written a second time here. The rejection reason is
 * optional and bounded, because it is stored and shown back to the operator.
 */
@Controller
@RequestMapping("/platform/dashboard/organizations/{organizationId}/users/pending")
public class PlatformPendingAccountsController {

  private static final String VIEW = "identity/platform/organization-users-pending";
  private static final String FRAGMENT = VIEW + " :: pending";
  private static final String HX_REQUEST_HEADER = "HX-Request";
  private static final String REDIRECT_PREFIX = "redirect:/platform/dashboard/organizations/";

  /** How many are shown at once; the page says when there are more. */
  /* package */ static final int PAGE_SIZE = 50;

  /** The rejection reason is stored and read back, so it has a ceiling. */
  /* package */ static final int MAX_REASON_LENGTH = 500;

  private final ListPendingAccountsForOrganizationUseCase listPending;
  private final ApproveAccountRegistrationUseCase approve;
  private final RejectAccountRegistrationUseCase reject;
  private final GetAccountForOrganizationUseCase getAccount;
  private final PlatformAccountOrganizationAccess organizationAccess;

  @SuppressWarnings("java:S107")
  public PlatformPendingAccountsController(
      final ListPendingAccountsForOrganizationUseCase listPending,
      final ApproveAccountRegistrationUseCase approve,
      final RejectAccountRegistrationUseCase reject,
      final GetAccountForOrganizationUseCase getAccount,
      final OrganizationForPlatformAccountResolver organizationResolver,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.listPending = listPending;
    this.approve = approve;
    this.reject = reject;
    this.getAccount = getAccount;
    this.organizationAccess =
        new PlatformAccountOrganizationAccess(organizationResolver, currentPlatformAccount);
  }

  @GetMapping
  public String show(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      final Model model) {
    final PlatformAccountId owner = organizationAccess.requireCurrentPlatformAccount(request);
    final OrganizationId orgId = new OrganizationId(organizationId);
    final String organizationName = organizationAccess.requireOwnedOrganizationName(orgId, owner);
    final ListPendingAccountsForOrganizationUseCase.PendingAccounts pending =
        listPending.handle(new ListPendingAccountsForOrganizationQuery(orgId, PAGE_SIZE));
    model.addAttribute("organizationId", organizationId);
    model.addAttribute("organizationName", organizationName);
    model.addAttribute("pending", pending.accounts());
    model.addAttribute("pendingTotal", pending.total());
    model.addAttribute("pageSize", PAGE_SIZE);
    model.addAttribute("maxReasonLength", MAX_REASON_LENGTH);
    return "true".equals(request.getHeader(HX_REQUEST_HEADER)) ? FRAGMENT : VIEW;
  }

  @PostMapping("/{accountId}/approve")
  public String approve(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID accountId) {
    final PlatformAccountOrganizationAccess.ResolvedAccountAccess access =
        organizationAccess.requireOwnedAccount(request, organizationId, accountId, getAccount);
    approve.handle(new ApproveAccountRegistrationCommand(access.account().id(), actorFor(access)));
    return backToQueue(organizationId);
  }

  @PostMapping("/{accountId}/reject")
  public String reject(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID accountId,
      @RequestParam(name = "reason", required = false) final String reason) {
    final PlatformAccountOrganizationAccess.ResolvedAccountAccess access =
        organizationAccess.requireOwnedAccount(request, organizationId, accountId, getAccount);
    reject.handle(
        new RejectAccountRegistrationCommand(
            access.account().id(), cleanReason(reason), actorFor(access)));
    return backToQueue(organizationId);
  }

  // Blank is no reason, and a reason is cut to its ceiling rather than refused: the decision
  // matters more than the note, and a refused post would leave the Account waiting.
  private static String cleanReason(final String reason) {
    if (reason == null || reason.isBlank()) {
      return null;
    }
    final String trimmed = reason.strip();
    return trimmed.length() <= MAX_REASON_LENGTH
        ? trimmed
        : trimmed.substring(0, MAX_REASON_LENGTH);
  }

  private static AuditActor actorFor(
      final PlatformAccountOrganizationAccess.ResolvedAccountAccess access) {
    return AuditActor.platformAccount(access.ownerPlatformAccountId().value());
  }

  private static String backToQueue(final UUID organizationId) {
    return REDIRECT_PREFIX + organizationId + "/users/pending";
  }
}
