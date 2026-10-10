package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.common.domain.model.KeysetPageRequest;
import com.clavaris.organization.application.usecase.getorganizationprofiles.GetOrganizationProfilesUseCase;
import com.clavaris.organization.application.usecase.listorganizationsforplatformaccountpaged.ListOrganizationsForPlatformAccountPagedQuery;
import com.clavaris.organization.application.usecase.listorganizationsforplatformaccountpaged.ListOrganizationsForPlatformAccountPagedUseCase;
import com.clavaris.organization.domain.model.Organization;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

/**
 * Puts one page of an owner's Organizations, and the profile of each, into the model of the "Your
 * organizations" page. Shared by the controller that shows the page and the one that edits an
 * Organization from it, because a rejected edit re-renders the whole page (with its dialog open)
 * and has to rebuild exactly what a plain visit would.
 */
@Component
public class OrganizationDashboardModel {

  public static final String ORGANIZATIONS = "organizations";
  public static final String PAGE = "organizationsPage";
  public static final String PROFILES = "profiles";

  private final ListOrganizationsForPlatformAccountPagedUseCase listOrganizations;
  private final GetOrganizationProfilesUseCase getProfiles;

  public OrganizationDashboardModel(
      final ListOrganizationsForPlatformAccountPagedUseCase listOrganizations,
      final GetOrganizationProfilesUseCase getProfiles) {
    this.listOrganizations = listOrganizations;
    this.getProfiles = getProfiles;
  }

  /** Adds the page's Organizations, the page itself (for its cursors) and their profiles. */
  public void populate(final Model model, final UUID owner, final KeysetPageRequest pageRequest) {
    final KeysetPage<Organization> page =
        listOrganizations.handle(
            new ListOrganizationsForPlatformAccountPagedQuery(owner, pageRequest));
    final List<UUID> ids = page.content().stream().map(Organization::id).toList();
    model.addAttribute(ORGANIZATIONS, page.content());
    model.addAttribute(PAGE, page);
    model.addAttribute(PROFILES, getProfiles.handle(ids));
  }
}
