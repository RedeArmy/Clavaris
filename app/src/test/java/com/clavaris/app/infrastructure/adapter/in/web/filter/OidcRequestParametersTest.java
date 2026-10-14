package com.clavaris.app.infrastructure.adapter.in.web.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class OidcRequestParametersTest {

  @Test
  void aRegisteredUriIsNeverEncodedTwiceWhenParametersAreAppended() {
    Map<String, String> parameters = new LinkedHashMap<>();
    parameters.put("error", "login_required");
    parameters.put("state", "a b&c=d");

    assertThat(
            OidcRequestParameters.withQuery("https://app.example.com/cb?tenant=a%20b", parameters))
        .isEqualTo(
            "https://app.example.com/cb?tenant=a%20b&error=login_required&state=a%20b%26c%3Dd");
  }

  @Test
  void aMissingValueIsSkippedAndTheFirstParameterStartsTheQuery() {
    Map<String, String> parameters = new LinkedHashMap<>();
    parameters.put("error", "invalid_request");
    parameters.put("state", null);

    assertThat(OidcRequestParameters.withQuery("https://app.example.com/cb", parameters))
        .isEqualTo("https://app.example.com/cb?error=invalid_request");
  }

  @Test
  void onlyANavigationOrAClientWithoutFetchMetadataMayChangeTheEndUsersState() {
    assertThat(OidcRequestParameters.isNavigation(requestWithDestination(null))).isTrue();
    assertThat(OidcRequestParameters.isNavigation(requestWithDestination("document"))).isTrue();
    assertThat(OidcRequestParameters.isNavigation(requestWithDestination("iframe"))).isTrue();
    assertThat(OidcRequestParameters.isNavigation(requestWithDestination("image"))).isFalse();
    assertThat(OidcRequestParameters.isNavigation(requestWithDestination("script"))).isFalse();
    assertThat(OidcRequestParameters.isNavigation(requestWithDestination("empty"))).isFalse();
  }

  private static MockHttpServletRequest requestWithDestination(final String destination) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
    if (destination != null) {
      request.addHeader("Sec-Fetch-Dest", destination);
    }
    return request;
  }
}
