package com.clavaris.identity.application.usecase.resolveorganizationname;

import com.clavaris.identity.domain.model.OrganizationId;
import java.util.Optional;

/**
 * The name of an Organization, for the places that speak on its behalf to its own people: the
 * emails its Accounts receive and the name a browser shows when it asks to save a passkey. Those
 * must read as the consuming application's, never as Clavaris's, so they are branded with the
 * Organization's name rather than the product's.
 *
 * <p>Deliberately does not reference organization-module's {@code Organization} directly — same
 * module-independence rule {@code RedirectUrlResolver} follows; implemented in {@code app}.
 */
@FunctionalInterface
public interface OrganizationNameProvider {

  /**
   * @return the Organization's name, or empty when it cannot be found or has no usable name — the
   *     caller then says nothing about who it is, rather than falling back to Clavaris.
   */
  Optional<String> nameFor(OrganizationId organizationId);
}
