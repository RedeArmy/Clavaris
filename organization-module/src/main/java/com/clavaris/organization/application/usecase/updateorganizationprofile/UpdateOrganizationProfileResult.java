package com.clavaris.organization.application.usecase.updateorganizationprofile;

import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.OrganizationProfile;

/** The Organization and its profile as they are after the update. */
public record UpdateOrganizationProfileResult(
    Organization organization, OrganizationProfile profile) {}
