package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.common.domain.model.KeysetPageRequest;
import com.clavaris.identity.application.usecase.admincreateaccountfororganization.AdminCreateAccountForOrganizationCommand;
import com.clavaris.identity.application.usecase.admincreateaccountfororganization.AdminCreateAccountForOrganizationUseCase;
import com.clavaris.identity.application.usecase.listaccountsfororganization.ListAccountsForOrganizationQuery;
import com.clavaris.identity.application.usecase.listaccountsfororganization.ListAccountsForOrganizationUseCase;
import com.clavaris.identity.application.usecase.registeraccount.AccessRestrictedException;
import com.clavaris.identity.application.usecase.registeraccount.EmailAlreadyRegisteredException;
import com.clavaris.identity.application.usecase.registeraccount.UsernameAlreadyRegisteredException;
import com.clavaris.identity.application.usecase.registeraccount.WeakPasswordException;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.PlatformAccountId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

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
@SuppressWarnings("PMD.LongVariable")
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

  private final ListAccountsForOrganizationUseCase listAccounts;
  private final AdminCreateAccountForOrganizationUseCase createAccount;
  private final OrganizationForPlatformAccountResolver organizationResolver;
  private final CurrentPlatformAccountResolver currentPlatformAccount;

  public PlatformAccountsController(
      final ListAccountsForOrganizationUseCase listAccounts,
      final AdminCreateAccountForOrganizationUseCase createAccount,
      final OrganizationForPlatformAccountResolver organizationResolver,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.listAccounts = listAccounts;
    this.createAccount = createAccount;
    this.organizationResolver = organizationResolver;
    this.currentPlatformAccount = currentPlatformAccount;
  }

  @GetMapping
  public String showList(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @RequestParam(required = false) final String after,
      @RequestParam(required = false) final String before,
      final Model model) {
    final PlatformAccountId ownerPlatformAccountId = requireCurrentPlatformAccount(request);
    final OrganizationId orgId = new OrganizationId(organizationId);
    final String organizationName = requireOwnedOrganizationName(orgId, ownerPlatformAccountId);
    populateHeaderModel(model, organizationId, organizationName);
    model.addAttribute(CREATE_FORM_ATTRIBUTE, new AdminCreateAccountForm());
    populateUsersModel(model, orgId, KeysetPageRequest.fromCursors(after, before));
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
    final PlatformAccountId ownerPlatformAccountId = requireCurrentPlatformAccount(request);
    final OrganizationId orgId = new OrganizationId(organizationId);
    final String organizationName = requireOwnedOrganizationName(orgId, ownerPlatformAccountId);

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
    populateUsersModel(model, orgId, KeysetPageRequest.first());
    return LIST_VIEW;
  }

  private void populateHeaderModel(
      final Model model, final UUID organizationId, final String organizationName) {
    model.addAttribute(ORGANIZATION_ID_ATTRIBUTE, organizationId);
    model.addAttribute(ORGANIZATION_NAME_ATTRIBUTE, organizationName);
  }

  private void populateUsersModel(
      final Model model, final OrganizationId organizationId, final KeysetPageRequest pageRequest) {
    final KeysetPage<Account> usersPage =
        listAccounts.handle(new ListAccountsForOrganizationQuery(organizationId, pageRequest));
    model.addAttribute("users", usersPage.content());
    model.addAttribute("usersPage", usersPage);
  }

  private String requireOwnedOrganizationName(
      final OrganizationId organizationId, final PlatformAccountId ownerPlatformAccountId) {
    return organizationResolver
        .resolveName(organizationId, ownerPlatformAccountId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  private static boolean isHtmxRequest(final HttpServletRequest request) {
    return "true".equals(request.getHeader(HX_REQUEST_HEADER));
  }

  private PlatformAccountId requireCurrentPlatformAccount(final HttpServletRequest request) {
    return CurrentSessionSupport.requireResolved(
        currentPlatformAccount.resolve(request), "PlatformAccount");
  }

  private static String blankToNull(final String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
