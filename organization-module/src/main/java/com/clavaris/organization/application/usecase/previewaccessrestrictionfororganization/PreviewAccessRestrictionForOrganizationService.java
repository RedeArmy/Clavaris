package com.clavaris.organization.application.usecase.previewaccessrestrictionfororganization;

import com.clavaris.organization.application.usecase.addaccessrestrictionentry.AccessRestrictionEntryRepository;
import com.clavaris.organization.domain.service.AccessRestrictionDecision;
import com.clavaris.organization.domain.service.AccessRestrictionPolicy;

/** The same evaluator the registration uses, so the preview can never disagree with the gate. */
public class PreviewAccessRestrictionForOrganizationService
    implements PreviewAccessRestrictionForOrganizationUseCase {

  private final AccessRestrictionEntryRepository entries;

  public PreviewAccessRestrictionForOrganizationService(
      final AccessRestrictionEntryRepository entries) {
    this.entries = entries;
  }

  @Override
  public AccessRestrictionDecision handle(
      final PreviewAccessRestrictionForOrganizationQuery query) {
    return AccessRestrictionPolicy.evaluate(
        entries.findAllByOrganizationId(query.organizationId()), query.email());
  }
}
