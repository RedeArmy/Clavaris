package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.authenticatewithsocialprovider.OrganizationSocialLoginPolicyProvider;
import com.clavaris.identity.application.usecase.authenticatewithsocialprovider.SocialIdentityRepository;
import com.clavaris.identity.application.usecase.disconnectsocialidentity.DisconnectSocialIdentityCommand;
import com.clavaris.identity.application.usecase.disconnectsocialidentity.DisconnectSocialIdentityUseCase;
import com.clavaris.identity.application.usecase.disconnectsocialidentity.LastSignInMethodException;
import com.clavaris.identity.application.usecase.disconnectsocialidentity.SocialIdentityNotLinkedException;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.SocialIdentity;
import com.clavaris.identity.domain.model.SocialProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * TD-FUT-043: the signed-in End-User's "Connected accounts" page — see which Google/GitHub
 * identities are linked, link another, or unlink one. Same posture as {@link
 * AccountPasswordController}: the path's {@code organizationId} is cosmetic, every action is scoped
 * by the session's Account.
 *
 * <p>Both actions are POST (CSRF-protected), which matters for connecting in particular: it plants
 * the intent that makes the next provider callback link an identity instead of signing in, so it
 * must not be triggerable by a link or an image. The callback itself is completed by {@code app}'s
 * social success handler, which reads the intent back.
 */
@Controller
@RequestMapping("/o/{organizationId}/account/connections")
public class AccountConnectionsController {

  private static final String VIEW = "identity/account/connections";
  private static final String ACCOUNT = "Account";
  private static final String CONNECT_ERROR = "?connectError";

  private final CurrentAccountResolver currentAccount;
  private final SocialIdentityRepository identities;
  private final OrganizationSocialLoginPolicyProvider policy;
  private final DisconnectSocialIdentityUseCase disconnect;

  public AccountConnectionsController(
      final CurrentAccountResolver currentAccount,
      final SocialIdentityRepository identities,
      final OrganizationSocialLoginPolicyProvider policy,
      final DisconnectSocialIdentityUseCase disconnect) {
    this.currentAccount = currentAccount;
    this.identities = identities;
    this.policy = policy;
    this.disconnect = disconnect;
  }

  @GetMapping
  public String show(
      @PathVariable final UUID organizationId,
      final HttpServletRequest request,
      final Model model) {
    final AccountId accountId =
        CurrentSessionSupport.requireResolved(currentAccount.resolve(request), ACCOUNT);
    final Set<SocialProvider> linked =
        identities.findAllByAccountId(accountId).stream()
            .map(SocialIdentity::provider)
            .collect(Collectors.toSet());
    model.addAttribute("organizationId", organizationId);
    model.addAttribute("linked", linked);
    model.addAttribute(
        "connectable",
        policy.allowedProviders(new OrganizationId(organizationId)).stream()
            .filter(provider -> !linked.contains(provider))
            .toList());
    return VIEW;
  }

  @PostMapping("/{provider}/connect")
  public void connect(
      @PathVariable final UUID organizationId,
      @PathVariable final String provider,
      final HttpServletRequest request,
      final HttpServletResponse response)
      throws IOException {
    final AccountId accountId =
        CurrentSessionSupport.requireResolved(currentAccount.resolve(request), ACCOUNT);
    final SocialProvider socialProvider = parse(provider);
    if (socialProvider == null
        || !policy.isProviderAllowed(new OrganizationId(organizationId), socialProvider)) {
      response.sendRedirect(connectionsUrl(request, organizationId) + CONNECT_ERROR);
      return;
    }
    request
        .getSession()
        .setAttribute(
            SocialLoginRedirectController.ORGANIZATION_ID_SESSION_ATTRIBUTE,
            organizationId.toString());
    request
        .getSession()
        .setAttribute(
            SocialLoginRedirectController.LINK_ACCOUNT_ID_SESSION_ATTRIBUTE,
            accountId.value().toString());
    request
        .getSession()
        .setAttribute(
            SocialLoginRedirectController.LINK_REQUESTED_AT_SESSION_ATTRIBUTE, Instant.now());
    response.sendRedirect(
        request.getContextPath()
            + "/oauth2/authorization/"
            + socialProvider.name().toLowerCase(Locale.ROOT)
            + "?"
            + SocialLoginRedirectController.TENANT_TAG_PARAMETER
            + "="
            + organizationId.toString().replace("-", ""));
  }

  @PostMapping("/{provider}/disconnect")
  public void disconnect(
      @PathVariable final UUID organizationId,
      @PathVariable final String provider,
      final HttpServletRequest request,
      final HttpServletResponse response)
      throws IOException {
    final AccountId accountId =
        CurrentSessionSupport.requireResolved(currentAccount.resolve(request), ACCOUNT);
    final SocialProvider socialProvider = parse(provider);
    String outcome = CONNECT_ERROR;
    if (socialProvider != null) {
      try {
        disconnect.handle(
            new DisconnectSocialIdentityCommand(
                accountId, socialProvider, AuditActor.account(accountId.value())));
        outcome = "?disconnected";
      } catch (final LastSignInMethodException _) {
        outcome = "?lastMethod";
      } catch (final SocialIdentityNotLinkedException _) {
        outcome = CONNECT_ERROR;
      }
    }
    response.sendRedirect(connectionsUrl(request, organizationId) + outcome);
  }

  private static String connectionsUrl(
      final HttpServletRequest request, final UUID organizationId) {
    return request.getContextPath() + "/o/" + organizationId + "/account/connections";
  }

  private static SocialProvider parse(final String provider) {
    try {
      return SocialProvider.valueOf(provider.toUpperCase(Locale.ROOT));
    } catch (final IllegalArgumentException _) {
      return null;
    }
  }
}
