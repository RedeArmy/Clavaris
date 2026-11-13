package com.clavaris.app.infrastructure.adapter.in.web.filter;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class MalformedRequestFilterTest {

  @Test
  void multipartWithoutABoundaryIsABadRequest() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/x");
    request.setContentType("multipart/form-data");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    new MalformedRequestFilter().doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentType()).startsWith("application/problem+json");
    assertThat(chain.getRequest()).isNull();
  }

  @Test
  void multipartWithABoundaryPassesThrough() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/upload");
    request.setContentType("multipart/form-data; boundary=----x");
    MockFilterChain chain = new MockFilterChain();

    new MalformedRequestFilter().doFilter(request, new MockHttpServletResponse(), chain);

    assertThat(chain.getRequest()).isSameAs(request);
  }

  @Test
  void aQueryStringTheContainerCannotSplitIsABadRequest() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/x");
    request.setQueryString("=broken");
    HttpServletRequest unreadable =
        new HttpServletRequestWrapper(request) {
          @Override
          public Map<String, String[]> getParameterMap() {
            throw new IllegalStateException("Invalid chunk");
          }
        };
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    new MalformedRequestFilter().doFilter(unreadable, response, chain);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(chain.getRequest()).isNull();
  }

  @Test
  void anOrdinaryRequestPassesThrough() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/platform/login");
    request.setQueryString("lang=es");
    request.setParameter("lang", "es");
    MockFilterChain chain = new MockFilterChain();

    new MalformedRequestFilter().doFilter(request, new MockHttpServletResponse(), chain);

    assertThat(chain.getRequest()).isSameAs(request);
  }

  @Test
  void aFormBodyIsNotReadHere() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
    request.setContentType("application/x-www-form-urlencoded");
    request.setQueryString("=x");
    MockFilterChain chain = new MockFilterChain();

    new MalformedRequestFilter().doFilter(request, new MockHttpServletResponse(), chain);

    assertThat(chain.getRequest()).isSameAs(request);
  }
}
