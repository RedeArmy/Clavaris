package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.common.domain.model.KeysetPageRequest;
import com.clavaris.identity.application.usecase.admincreateaccountfororganization.AdminCreateAccountForOrganizationCommand;
import com.clavaris.identity.application.usecase.admincreateaccountfororganization.AdminCreateAccountForOrganizationUseCase;
import com.clavaris.identity.application.usecase.listaccountsfororganization.ListAccountsForOrganizationQuery;
import com.clavaris.identity.application.usecase.listaccountsfororganization.ListAccountsForOrganizationUseCase;
import com.clavaris.identity.application.usecase.listaccountsfororganization.WorkspaceRoleDisplay;
import com.clavaris.identity.application.usecase.listaccountsfororganization.WorkspaceRoleDisplayReader;
import com.clavaris.identity.application.usecase.registeraccount.AccessRestrictedException;
import com.clavaris.identity.application.usecase.registeraccount.BreachedPasswordException;
import com.clavaris.identity.application.usecase.registeraccount.EmailAlreadyRegisteredException;
import com.clavaris.identity.application.usecase.registeraccount.UsernameAlreadyRegisteredException;
import com.clavaris.identity.application.usecase.registeraccount.WeakPasswordException;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.PlatformAccountId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * SDE-III review, 2026-09-19 — Clerk dashboard "Users" tab parity: lists every {@code Account} in
 * one Organization (name, email, username, phone number, last signed in, joined) and a "Create
 * user" dialog backed by {@link AdminCreateAccountForOrganizationUseCase} — the operator sets the
 * password directly, unlike self-service registration.
 *
 * <p>Plain form POST + {@code &lt;dialog&gt;}, not HTMX, for create — same pattern {@code
 * dashboard.html}'s own "Create your organization" dialog already establishes (a validation error
 * re-renders the whole page with {@code data-dialog-open-on-load} reopening it), reusing that same
 * generic, org-specific-logic-free {@code organization-dialog.js}. Pagination (list navigation)
 * still uses HTMX, same convention as every other paginated dashboard list in this codebase.
 *
 * <p>{@code organizationId} resolves through {@link OrganizationForPlatformAccountResolver} — same
 * anti-enumeration posture as every other dashboard controller.
 */
// PMD.ExcessiveImports: ADR-0029 added a search/pagination-affecting request param and a new
// read-only role-display collaborator to a class that already sat right at PMD's own threshold —
// wiring, not sprawl, same reasoning every other growing controller in this codebase documents.
@SuppressWarnings({"PMD.LongVariable", "PMD.ExcessiveImports"})
@Controller
@RequestMapping("/platform/dashboard/organizations/{organizationId}/users")
public class PlatformAccountsController {

  private static final String LIST_VIEW = "identity/platform/organization-users";
  private static final String USERS_FRAGMENT = LIST_VIEW + " :: users";
  private static final String ORGANIZATION_ID_ATTRIBUTE = "organizationId";
  private static final String ORGANIZATION_NAME_ATTRIBUTE = "organizationName";
  private static final String CREATE_FORM_ATTRIBUTE = "createForm";
  private static final String EMAIL = "email";

  private static final String HX_REQUEST_HEADER = "HX-Request";

  // ADR-0029: this page's own override — every other dashboard list still gets
  // KeysetPageRequest.DEFAULT_SIZE (20) through the no-size overloads, unaware this exists.
  private static final int PAGE_SIZE = 25;

  private final ListAccountsForOrganizationUseCase listAccounts;
  private final AdminCreateAccountForOrganizationUseCase createAccount;
  private final WorkspaceRoleDisplayReader roleDisplayReader;
  private final PlatformAccountOrganizationAccess organizationAccess;

  public PlatformAccountsController(
      final ListAccountsForOrganizationUseCase listAccounts,
      final AdminCreateAccountForOrganizationUseCase createAccount,
      final WorkspaceRoleDisplayReader roleDisplayReader,
      final OrganizationForPlatformAccountResolver organizationResolver,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.listAccounts = listAccounts;
    this.createAccount = createAccount;
    this.roleDisplayReader = roleDisplayReader;
    this.organizationAccess =
        new PlatformAccountOrganizationAccess(organizationResolver, currentPlatformAccount);
  }

  // PMD.ShortVariable: "q" — same short, deliberate query-string param name every search box on
  // the web uses (Google's own included), not a placeholder that should have a longer name.
  @SuppressWarnings("PMD.ShortVariable")
  @GetMapping
  public String showList(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @RequestParam(required = false) final String after,
      @RequestParam(required = false) final String before,
      @RequestParam(required = false) final String q,
      final Model model) {
    final PlatformAccountId ownerPlatformAccountId =
        organizationAccess.requireCurrentPlatformAccount(request);
    final OrganizationId orgId = new OrganizationId(organizationId);
    final String organizationName =
        organizationAccess.requireOwnedOrganizationName(orgId, ownerPlatformAccountId);
    populateHeaderModel(model, organizationId, organizationName);
    model.addAttribute(CREATE_FORM_ATTRIBUTE, new AdminCreateAccountForm());
    model.addAttribute("searchTerm", q);
    populateUsersModel(model, orgId, KeysetPageRequest.fromCursors(after, before, PAGE_SIZE), q);
    return isHtmxRequest(request) ? USERS_FRAGMENT : LIST_VIEW;
  }

  // Never HTMX, never a redirect — see this class's own Javadoc for why the dialog pattern needs
  // a full-page re-render on validation error (data-dialog-open-on-load) and a full-page redirect
  // on success (the dialog's own <dialog> element isn't preserved across an HTMX swap the way the
  // rest of this page's own HX-Request branch assumes).
  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping
  public String create(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @Valid @ModelAttribute(CREATE_FORM_ATTRIBUTE) final AdminCreateAccountForm form,
      final BindingResult bindingResult,
      final Model model) {
    final PlatformAccountId ownerPlatformAccountId =
        organizationAccess.requireCurrentPlatformAccount(request);
    final OrganizationId orgId = new OrganizationId(organizationId);
    final String organizationName =
        organizationAccess.requireOwnedOrganizationName(orgId, ownerPlatformAccountId);

    if (!bindingResult.hasErrors()) {
      try {
        createAccount.handle(
            new AdminCreateAccountForOrganizationCommand(
                orgId,
                new Email(form.getEmail()),
                form.getPassword(),
                blankToNull(form.getFirstName()),
                blankToNull(form.getLastName()),
                blankToNull(form.getUsername()),
                blankToNull(form.getPhoneNumber()),
                form.isIgnorePasswordPolicy(),
                form.isIgnoreAccessRestrictions()));
        return "redirect:/platform/dashboard/organizations/" + organizationId + "/users";
      } catch (final EmailAlreadyRegisteredException _) {
        bindingResult.rejectValue(
            EMAIL, "email.alreadyRegistered", "This email is already registered");
      } catch (final UsernameAlreadyRegisteredException _) {
        bindingResult.rejectValue(
            "username", "username.alreadyRegistered", "This username is already taken");
      } catch (final WeakPasswordException _) {
        bindingResult.rejectValue(
            "password", "password.tooWeak", "Password does not meet the minimum requirements");
      } catch (final BreachedPasswordException _) {
        // BR-ID-07: deliberately NOT the WeakPasswordException message slot above — generic
        // wording only, never mentioning a breach/source (same exception's own Javadoc).
        bindingResult.rejectValue(
            "password",
            "password.breached",
            "This password cannot be used - please choose a different one");
      } catch (final AccessRestrictedException _) {
        bindingResult.rejectValue(
            EMAIL, "email.restricted", "This email is not allowed to register");
      } catch (final IllegalArgumentException _) {
        // Username's own domain constructor rejects a shape this form's own lack of a @Pattern
        // check doesn't catch — same gap/fix RegisterAccountController's own identical catch
        // documents.
        bindingResult.rejectValue("username", "username.invalid", "Enter a valid username");
      }
    }

    populateHeaderModel(model, organizationId, organizationName);
    populateUsersModel(model, orgId, KeysetPageRequest.first(PAGE_SIZE), null);
    return LIST_VIEW;
  }

  private void populateHeaderModel(
      final Model model, final UUID organizationId, final String organizationName) {
    model.addAttribute(ORGANIZATION_ID_ATTRIBUTE, organizationId);
    model.addAttribute(ORGANIZATION_NAME_ATTRIBUTE, organizationName);
  }

  private void populateUsersModel(
      final Model model,
      final OrganizationId organizationId,
      final KeysetPageRequest pageRequest,
      final String searchTerm) {
    final KeysetPage<Account> usersPage =
        listAccounts.handle(
            new ListAccountsForOrganizationQuery(organizationId, pageRequest, searchTerm));
    model.addAttribute("users", usersPage.content());
    model.addAttribute("usersPage", usersPage);
    // ADR-0029: one batched read against organization-module's own tables for the whole page,
    // never N+1 — see WorkspaceRoleDisplayReader's own Javadoc for why this reads that module's
    // tables directly instead of calling into it.
    final List<UUID> accountIds =
        usersPage.content().stream().map(account -> account.id().value()).toList();
    final Map<UUID, WorkspaceRoleDisplay> roleDisplayByAccountId =
        roleDisplayReader.findByAccountIds(accountIds);
    model.addAttribute("roleDisplayByAccountId", roleDisplayByAccountId);
  }

  private static boolean isHtmxRequest(final HttpServletRequest request) {
    return "true".equals(request.getHeader(HX_REQUEST_HEADER));
  }

  private static String blankToNull(final String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
