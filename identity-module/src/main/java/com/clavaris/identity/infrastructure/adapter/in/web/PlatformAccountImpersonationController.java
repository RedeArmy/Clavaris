package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.getaccountfororganization.GetAccountForOrganizationUseCase;
import com.clavaris.identity.application.usecase.impersonateaccount.AccountNotActiveException;
import com.clavaris.identity.application.usecase.impersonateaccount.ImpersonateAccountCommand;
import com.clavaris.identity.application.usecase.impersonateaccount.ImpersonateAccountResult;
import com.clavaris.identity.application.usecase.impersonateaccount.ImpersonateAccountUseCase;
import com.clavaris.identity.application.usecase.impersonateaccount.ImpersonationClientNotFoundException;
import com.clavaris.identity.application.usecase.impersonateaccount.ImpersonationScopeNotAllowedException;
import com.clavaris.identity.application.usecase.impersonateaccount.ImpersonationTokenMinter;
import com.clavaris.identity.application.usecase.impersonateaccount.MintedImpersonationToken;
import com.clavaris.identity.domain.model.AccountId;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * SDE-III review, 2026-09-19 — Clerk dashboard "Users" tab "Impersonate user" menu item: mints a
 * raw Bearer access token for a target Account, exactly the same behavior as the REST {@code POST
 * /api/v1/admin/accounts/{id}:impersonate} endpoint (same {@link ImpersonateAccountUseCase}, same
 * {@link ImpersonationTokenMinter} bridge to {@code ImpersonationTokenIssuer}) — no ID token, no
 * refresh token, same v1 scope decision.
 *
 * <p>Post/redirect/get: the minted token (or an error) travels as a one-time flash attribute back
 * to {@link PlatformAccountDetailController}'s own GET — avoids both a token surviving a page
 * refresh in the address bar/POST-resubmission dialog and duplicating that controller's own model
 * population here.
 *
 * <p>Rate limiting: a NEW rule in {@code PlatformDashboardSecurityConfig}, keyed by the
 * authenticated {@code PlatformAccountId} — the REST endpoint's own {@code
 * accounts-impersonate:client} limiter is scoped to {@code /api/v1/admin/**} and a {@code
 * PlatformClient} bearer token, neither of which a session-authenticated dashboard POST carries, so
 * it would not otherwise be covered at all.
 */
@SuppressWarnings("PMD.LongVariable")
@Controller
@RequestMapping("/platform/dashboard/organizations/{organizationId}/users/{accountId}/impersonate")
public class PlatformAccountImpersonationController {

  private final GetAccountForOrganizationUseCase getAccount;
  private final ImpersonateAccountUseCase impersonateAccount;
  private final ImpersonationTokenMinter tokenMinter;
  private final PlatformAccountOrganizationAccess organizationAccess;

  @SuppressWarnings("java:S107")
  public PlatformAccountImpersonationController(
      final GetAccountForOrganizationUseCase getAccount,
      final ImpersonateAccountUseCase impersonateAccount,
      final ImpersonationTokenMinter tokenMinter,
      final OrganizationForPlatformAccountResolver organizationResolver,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.getAccount = getAccount;
    this.impersonateAccount = impersonateAccount;
    this.tokenMinter = tokenMinter;
    this.organizationAccess =
        new PlatformAccountOrganizationAccess(organizationResolver, currentPlatformAccount);
  }

  @PostMapping
  public String impersonate(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID accountId,
      @RequestParam final String clientId,
      @RequestParam(required = false) final String scopes,
      final RedirectAttributes redirectAttributes) {
    final PlatformAccountOrganizationAccess.ResolvedAccountAccess access =
        organizationAccess.requireOwnedAccount(request, organizationId, accountId, getAccount);
    final AccountId targetAccountId = access.account().id();

    final AuditActor actor = AuditActor.platformAccount(access.ownerPlatformAccountId().value());
    attemptImpersonation(request, targetAccountId, clientId, scopes, actor, redirectAttributes);

    return "redirect:/platform/dashboard/organizations/" + organizationId + "/users/" + accountId;
  }

  private void attemptImpersonation(
      final HttpServletRequest request,
      final AccountId targetAccountId,
      final String clientId,
      final String scopes,
      final AuditActor actor,
      final RedirectAttributes redirectAttributes) {
    try {
      final ImpersonateAccountResult result =
          impersonateAccount.handle(new ImpersonateAccountCommand(targetAccountId, actor));
      final String baseUrl =
          ServletUriComponentsBuilder.fromRequestUri(request)
              .replacePath(null)
              .build()
              .toUriString();
      final MintedImpersonationToken token =
          tokenMinter.mint(
              result.accountId(),
              result.organizationId(),
              clientId,
              parseScopes(scopes),
              actor,
              baseUrl);
      redirectAttributes.addFlashAttribute("impersonationToken", token);
    } catch (final AccountNotActiveException _) {
      redirectAttributes.addFlashAttribute(
          "impersonationError", "This Account is not ACTIVE and cannot be impersonated.");
    } catch (final ImpersonationClientNotFoundException _) {
      redirectAttributes.addFlashAttribute(
          "impersonationError", "That OAuth Client isn't registered for this Organization.");
    } catch (final ImpersonationScopeNotAllowedException _) {
      redirectAttributes.addFlashAttribute(
          "impersonationError",
          "One or more requested scopes aren't allowed for that OAuth Client.");
    }
  }

  private static List<String> parseScopes(final String scopes) {
    return scopes == null || scopes.isBlank() ? List.of() : List.of(scopes.trim().split("\\s+"));
  }
}
