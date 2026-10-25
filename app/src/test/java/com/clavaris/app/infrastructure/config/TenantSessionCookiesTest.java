package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class TenantSessionCookiesTest {

  @Test
  void theOrganizationOfATenantCookieIsTheOneItWasNamedAfter() {
    UUID organizationId = UUID.randomUUID();

    assertThat(TenantSessionCookies.organizationOf(TenantSessionCookies.nameFor(organizationId)))
        .contains(organizationId);
  }

  @Test
  void cookiesThatNameNoOrganizationHaveNone() {
    assertThat(TenantSessionCookies.organizationOf("SESSION")).isEmpty();
    assertThat(TenantSessionCookies.organizationOf("PLATFORM_SESSION")).isEmpty();
    assertThat(TenantSessionCookies.organizationOf("SESSION_not-a-uuid")).isEmpty();
    assertThat(TenantSessionCookies.organizationOf("SESSION_1234")).isEmpty();
    assertThat(TenantSessionCookies.organizationOf("OTHER_11111111111111111111111111111111"))
        .isEmpty();
  }
}
