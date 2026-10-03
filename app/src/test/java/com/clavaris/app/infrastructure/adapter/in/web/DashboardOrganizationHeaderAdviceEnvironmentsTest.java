package com.clavaris.app.infrastructure.adapter.in.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.common.domain.model.EnvironmentOption;
import com.clavaris.common.domain.model.OrganizationHeaderView;
import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.infrastructure.adapter.in.web.CurrentPlatformAccountResolver;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** The Development / Production switcher the advice builds for the Organization header. */
class DashboardOrganizationHeaderAdviceEnvironmentsTest {

  private static final UUID OWNER_ID = UUID.randomUUID();
  private static final String BASE = "/platform/dashboard/organizations/";

  private OrganizationRepository organizations;
  private DashboardOrganizationHeaderAdvice advice;

  @BeforeEach
  void setUp() {
    organizations = mock(OrganizationRepository.class);
    final CurrentPlatformAccountResolver currentAccount =
        mock(CurrentPlatformAccountResolver.class);
    when(currentAccount.resolve(any())).thenReturn(Optional.of(OWNER_ID));
    advice = new DashboardOrganizationHeaderAdvice(organizations, currentAccount);
  }

  private void exists(final Organization... all) {
    for (final Organization organization : all) {
      when(organizations.findById(organization.id())).thenReturn(Optional.of(organization));
    }
  }

  private OrganizationHeaderView headerOn(final Organization organization, final String suffix) {
    return advice.organizationHeader(
        new MockHttpServletRequest("GET", BASE + organization.id() + suffix));
  }

  private static Organization production(final Organization development, final UUID ownerId) {
    return Organization.registerProductionEnvironment("Acme Prod", ownerId, development.id());
  }

  @Test
  void aDevelopmentOrganizationThatWasNeverPromotedOffersProductionAsASetUpEntry() {
    final Organization development = Organization.register("Acme Dev", OWNER_ID);
    exists(development);

    final List<EnvironmentOption> options = headerOn(development, "/users").environments();

    assertEquals(2, options.size());
    final EnvironmentOption here = options.get(0);
    assertFalse(here.production());
    assertTrue(here.current());
    assertEquals(BASE + development.id() + "/users", here.href());
    final EnvironmentOption production = options.get(1);
    assertTrue(production.production());
    assertFalse(production.setUp());
    assertEquals(BASE + development.id() + "/promote-to-production", production.href());
    assertNull(production.organizationName());
  }

  @Test
  void aPromotedDevelopmentOrganizationLinksToItsProductionSiblingOnTheSamePage() {
    final Organization development = Organization.register("Acme Dev", OWNER_ID);
    final Organization production = production(development, OWNER_ID);
    final Organization linkedDevelopment =
        development.withLinkedEnvironmentOrganizationId(production.id());
    exists(linkedDevelopment, production);

    final List<EnvironmentOption> options =
        headerOn(linkedDevelopment, "/oauth-clients").environments();

    final EnvironmentOption target = options.get(1);
    assertTrue(target.production());
    assertTrue(target.setUp());
    assertFalse(target.current());
    assertEquals(BASE + production.id() + "/oauth-clients", target.href());
    assertEquals("Acme Prod", target.organizationName());
  }

  @Test
  void aProductionOrganizationLinksBackToItsDevelopmentSibling() {
    final Organization development = Organization.register("Acme Dev", OWNER_ID);
    final Organization production = production(development, OWNER_ID);
    final Organization linkedDevelopment =
        development.withLinkedEnvironmentOrganizationId(production.id());
    exists(linkedDevelopment, production);

    final OrganizationHeaderView header = headerOn(production, "/users");

    assertTrue(header.production());
    assertEquals(2, header.environments().size());
    assertFalse(header.environments().get(0).production());
    assertEquals(BASE + linkedDevelopment.id() + "/users", header.environments().get(0).href());
    assertTrue(header.environments().get(1).current());
  }

  @Test
  void aProductionOrganizationWithNoPairedDevelopmentOneHasNoSwitcher() {
    // Every Organization that predates the environments feature: production, never linked.
    final Organization legacy =
        Organization.reconstitute(
            UUID.randomUUID(),
            "Legacy",
            java.time.Instant.now(),
            OWNER_ID,
            false,
            List.of(),
            com.clavaris.organization.domain.model.OrganizationEnvironment.PRODUCTION,
            null);
    exists(legacy);

    final OrganizationHeaderView header = headerOn(legacy, "");

    assertFalse(header.hasEnvironmentSwitcher());
    assertTrue(header.production());
  }

  @Test
  void aSiblingOwnedBySomeoneElseIsNeverOffered() {
    final Organization development = Organization.register("Acme Dev", OWNER_ID);
    final Organization foreignProduction = production(development, UUID.randomUUID());
    final Organization linkedDevelopment =
        development.withLinkedEnvironmentOrganizationId(foreignProduction.id());
    exists(linkedDevelopment, foreignProduction);

    final EnvironmentOption target = headerOn(linkedDevelopment, "").environments().get(1);

    assertFalse(target.setUp());
    assertEquals(BASE + linkedDevelopment.id() + "/promote-to-production", target.href());
  }

  @Test
  void aDeeperPathFallsBackToTheTopLevelTabAndAnUnsharedPageToTheHome() {
    final Organization development = Organization.register("Acme Dev", OWNER_ID);
    final Organization production = production(development, OWNER_ID);
    final Organization linkedDevelopment =
        development.withLinkedEnvironmentOrganizationId(production.id());
    exists(linkedDevelopment, production);

    // An OAuth client id exists in one environment only: land on the clients list, not a 404.
    assertEquals(
        BASE + production.id() + "/oauth-clients",
        headerOn(linkedDevelopment, "/oauth-clients/client_abc/delete")
            .environments()
            .get(1)
            .href());
    // A workspace id is likewise environment-specific, and "workspaces" is not a shared page.
    assertEquals(
        BASE + production.id(),
        headerOn(linkedDevelopment, "/workspaces/" + UUID.randomUUID())
            .environments()
            .get(1)
            .href());
  }
}
