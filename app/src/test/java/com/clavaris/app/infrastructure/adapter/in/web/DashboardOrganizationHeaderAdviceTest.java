package com.clavaris.app.infrastructure.adapter.in.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.common.domain.model.OrganizationHeaderView;
import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.infrastructure.adapter.in.web.CurrentPlatformAccountResolver;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class DashboardOrganizationHeaderAdviceTest {

  private static final UUID OWNER_ID = UUID.randomUUID();

  private OrganizationRepository organizations;
  private CurrentPlatformAccountResolver currentPlatformAccount;
  private DashboardOrganizationHeaderAdvice advice;
  private Organization organization;

  @BeforeEach
  void setUp() {
    organizations = mock(OrganizationRepository.class);
    currentPlatformAccount = mock(CurrentPlatformAccountResolver.class);
    advice = new DashboardOrganizationHeaderAdvice(organizations, currentPlatformAccount);
    organization = Organization.register("Acme Co", OWNER_ID);
    when(currentPlatformAccount.resolve(any())).thenReturn(Optional.of(OWNER_ID));
    when(organizations.findById(organization.id())).thenReturn(Optional.of(organization));
  }

  private MockHttpServletRequest get(final String path) {
    return new MockHttpServletRequest("GET", path);
  }

  @Test
  void addsTheHeaderOnEveryOrganizationTabAndSubPage() {
    for (final String suffix :
        new String[] {
          "", "/users", "/audit-log", "/oauth-clients", "/webhook-endpoints/activity"
        }) {
      final OrganizationHeaderView header =
          advice.organizationHeader(
              get("/platform/dashboard/organizations/" + organization.id() + suffix));

      assertNotNull(header, suffix);
      assertEquals("Acme Co", header.name());
      assertEquals(organization.id(), header.id());
      assertEquals("DEVELOPMENT", header.environmentLabel());
      assertEquals(organization.createdAt(), header.createdAt());
    }
  }

  @Test
  void addsNothingOutsideAnOrganizationPage() {
    assertNull(advice.organizationHeader(get("/platform/dashboard")));
    assertNull(advice.organizationHeader(get("/platform/account/sessions")));
    assertNull(
        advice.organizationHeader(get("/platform/dashboard/organizations/not-a-uuid/users")));
  }

  @Test
  void addsNothingForAnOrganizationOwnedBySomeoneElse() {
    when(currentPlatformAccount.resolve(any())).thenReturn(Optional.of(UUID.randomUUID()));

    assertNull(
        advice.organizationHeader(
            get("/platform/dashboard/organizations/" + organization.id() + "/users")));
  }

  @Test
  void addsNothingWithoutAnAuthenticatedPlatformAccount() {
    when(currentPlatformAccount.resolve(any())).thenReturn(Optional.empty());

    assertNull(
        advice.organizationHeader(get("/platform/dashboard/organizations/" + organization.id())));
  }

  @Test
  void skipsHtmxFragmentRequestsBecauseTheyNeverSwapTheHeader() {
    final MockHttpServletRequest request =
        get("/platform/dashboard/organizations/" + organization.id() + "/users");
    request.addHeader("HX-Request", "true");

    assertNull(advice.organizationHeader(request));
  }
}
