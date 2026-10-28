package com.clavaris.app.infrastructure.adapter.in.web.filter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ReferrerPolicyFilterTest {

  @Test
  void everyResponseCarriesNoReferrer() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();

    new ReferrerPolicyFilter()
        .doFilter(
            new MockHttpServletRequest("GET", "/o/x/verify-email"),
            response,
            new MockFilterChain());

    assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
  }

  @Test
  void theRequestStillReachesTheRestOfTheChain() throws Exception {
    MockFilterChain chain = new MockFilterChain();
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/oauth2/token");

    new ReferrerPolicyFilter().doFilter(request, new MockHttpServletResponse(), chain);

    assertThat(chain.getRequest()).isSameAs(request);
  }
}
