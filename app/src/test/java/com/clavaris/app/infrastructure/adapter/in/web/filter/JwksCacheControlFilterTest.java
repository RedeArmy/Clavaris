package com.clavaris.app.infrastructure.adapter.in.web.filter;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** TD-PERF-045. */
class JwksCacheControlFilterTest {

  private final JwksCacheControlFilter filter = new JwksCacheControlFilter(60);

  private MockHttpServletResponse run(final String method, final FilterChain chain)
      throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(new MockHttpServletRequest(method, "/o/x/oauth2/jwks"), response, chain);
    return response;
  }

  @Test
  void aSuccessfulGetIsPubliclyCacheableForTheConfiguredAge() throws Exception {
    MockHttpServletResponse response = run("GET", (req, res) -> {});

    assertThat(response.getHeader("Cache-Control")).isEqualTo("public, max-age=60");
  }

  @Test
  void anErrorIsNeverCachedAsIfItWereTheKeySet() throws Exception {
    MockHttpServletResponse notFound =
        run("GET", (req, res) -> ((HttpServletResponse) res).setStatus(404));
    MockHttpServletResponse failed =
        run("GET", (req, res) -> ((HttpServletResponse) res).sendError(500, "boom"));

    assertThat(notFound.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(failed.getHeader("Cache-Control")).isEqualTo("no-store");
  }

  @Test
  void aNonGetRequestIsLeftAlone() throws Exception {
    MockHttpServletResponse response = run("POST", (req, res) -> {});

    assertThat(response.getHeader("Cache-Control")).isNull();
  }

  @Test
  void onlyTheTwoJwksPathsAreTouched() throws Exception {
    for (String path : new String[] {"/oauth2/jwks", "/o/6f1c/oauth2/jwks"}) {
      MockHttpServletResponse response = new MockHttpServletResponse();
      filter.doFilter(new MockHttpServletRequest("GET", path), response, (req, res) -> {});
      assertThat(response.getHeader("Cache-Control")).as(path).isEqualTo("public, max-age=60");
    }
    for (String path :
        new String[] {
          "/oauth2/token", "/o/6f1c/oauth2/token", "/o/6f1c/oauth2/jwks/extra", "/jwks"
        }) {
      MockHttpServletResponse response = new MockHttpServletResponse();
      filter.doFilter(new MockHttpServletRequest("GET", path), response, (req, res) -> {});
      assertThat(response.getHeader("Cache-Control")).as(path).isNull();
    }
  }
}
