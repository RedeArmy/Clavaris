package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.getaccountfororganization.GetAccountForOrganizationUseCase;
import com.clavaris.identity.application.usecase.updateaccountmetadata.InvalidMetadataException;
import com.clavaris.identity.application.usecase.updateaccountmetadata.UpdateAccountMetadataCommand;
import com.clavaris.identity.application.usecase.updateaccountmetadata.UpdateAccountMetadataUseCase;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * TD-FUT-034, Clerk "View Profile" > Metadata tab parity. Same {@link
 * PlatformAccountOrganizationAccess} prologue every sibling {@code .../users/{accountId}/**}
 * controller already shares, same redirect-on-invalid-input shape {@code
 * RegisterAccountController}'s own form-redisplay handling establishes for a typed use-case
 * exception, adapted here to a redirect (this controller, like {@code
 * PlatformAccountSettingsController}, never renders directly).
 */
// PMD.AvoidFieldNameMatchingMethodName: updateMetadata below is this controller's own POST
// handler, same record-style-accessor-adjacent naming convention Account's own class Javadoc
// already documents for this codebase.
@SuppressWarnings({"PMD.LongVariable", "PMD.AvoidFieldNameMatchingMethodName"})
@Controller
@RequestMapping("/platform/dashboard/organizations/{organizationId}/users/{accountId}")
public class PlatformAccountMetadataController {

  private static final String REDIRECT_TO_PROFILE = "redirect:/platform/dashboard/organizations/";

  private final GetAccountForOrganizationUseCase getAccount;
  private final UpdateAccountMetadataUseCase updateMetadata;
  private final PlatformAccountOrganizationAccess organizationAccess;

  public PlatformAccountMetadataController(
      final GetAccountForOrganizationUseCase getAccount,
      final UpdateAccountMetadataUseCase updateMetadata,
      final OrganizationForPlatformAccountResolver organizationResolver,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.getAccount = getAccount;
    this.updateMetadata = updateMetadata;
    this.organizationAccess =
        new PlatformAccountOrganizationAccess(organizationResolver, currentPlatformAccount);
  }

  // Two exits (invalid tier -> redirect with an error param, success -> redirect with a success
  // param) — same rationale every other guard-clause-heavy dashboard-form controller in this
  // codebase already documents for its own identical suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping("/metadata")
  public String updateMetadata(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID accountId,
      @RequestParam(required = false) final String publicMetadata,
      @RequestParam(required = false) final String privateMetadata,
      @RequestParam(required = false) final String unsafeMetadata) {
    final PlatformAccountOrganizationAccess.ResolvedAccountAccess access =
        organizationAccess.requireOwnedAccount(request, organizationId, accountId, getAccount);
    final String redirectBase = REDIRECT_TO_PROFILE + organizationId + "/users/" + accountId;
    try {
      updateMetadata.handle(
          new UpdateAccountMetadataCommand(
              access.account().id(),
              publicMetadata,
              privateMetadata,
              unsafeMetadata,
              AuditActor.platformAccount(access.ownerPlatformAccountId().value())));
    } catch (final InvalidMetadataException e) {
      return RedirectQueryParams.appendIfPresent(redirectBase, "metadataError", e.tier());
    }
    return redirectBase + "?metadataUpdated";
  }
}
