package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.organization.application.usecase.setratelimitpolicyfororganization.RateLimitPolicyRepository;
import com.clavaris.organization.domain.model.RateLimitPolicy;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class OrganizationCapacityRateLimitingFilterTest {

  private static final int SYSTEM_DEFAULT = 600;

  @Test
  void usesTheSystemDefaultWhenNoRateLimitPolicyExistsForTheOrganization() throws Exception {
    UUID organizationId = UUID.randomUUID();
    RateLimiter rateLimiter = mock(RateLimiter.class);
    RateLimitPolicyRepository policies = mock(RateLimitPolicyRepository.class);
    when(policies.findByOrganizationId(organizationId)).thenReturn(Optional.empty());
    when(rateLimiter.tryConsume(any(), eq(SYSTEM_DEFAULT), any()))
        .thenReturn(new RateLimitDecision(true, 1, Duration.ofMinutes(1)));
    OrganizationCapacityRateLimitingFilter filter =
        new OrganizationCapacityRateLimitingFilter(rateLimiter, policies, SYSTEM_DEFAULT);
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/o/" + organizationId + "/login");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertThat(response.getStatus()).isEqualTo(200);
    verify(rateLimiter)
        .tryConsume("ratelimit:capacity:" + organizationId, SYSTEM_DEFAULT, Duration.ofMinutes(1));
  }

  @Test
  void usesTheOrganizationsOwnTunedCeilingWhenARateLimitPolicyExists() throws Exception {
    UUID organizationId = UUID.randomUUID();
    RateLimitPolicy tunedPolicy = RateLimitPolicy.define(organizationId, 50, 6000);
    RateLimiter rateLimiter = mock(RateLimiter.class);
    RateLimitPolicyRepository policies = mock(RateLimitPolicyRepository.class);
    when(policies.findByOrganizationId(organizationId)).thenReturn(Optional.of(tunedPolicy));
    when(rateLimiter.tryConsume(any(), eq(50), any()))
        .thenReturn(new RateLimitDecision(true, 1, Duration.ofMinutes(1)));
    OrganizationCapacityRateLimitingFilter filter =
        new OrganizationCapacityRateLimitingFilter(rateLimiter, policies, SYSTEM_DEFAULT);
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/o/" + organizationId + "/oauth2/token");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    verify(rateLimiter)
        .tryConsume("ratelimit:capacity:" + organizationId, 50, Duration.ofMinutes(1));
  }

  @Test
  void blocksWith429WhenTheAggregateCeilingIsExceeded() throws Exception {
    UUID organizationId = UUID.randomUUID();
    RateLimiter rateLimiter = mock(RateLimiter.class);
    RateLimitPolicyRepository policies = mock(RateLimitPolicyRepository.class);
    when(policies.findByOrganizationId(organizationId)).thenReturn(Optional.empty());
    when(rateLimiter.tryConsume(any(), eq(SYSTEM_DEFAULT), any()))
        .thenReturn(new RateLimitDecision(false, 601, Duration.ofSeconds(30)));
    OrganizationCapacityRateLimitingFilter filter =
        new OrganizationCapacityRateLimitingFilter(rateLimiter, policies, SYSTEM_DEFAULT);
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/o/" + organizationId + "/login");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(chain.getRequest()).isNull();
    assertThat(response.getStatus()).isEqualTo(429);
    assertThat(response.getHeader("Retry-After")).isEqualTo("30");
  }

  @Test
  void neverConsultsThePolicyRepositoryOrTheRateLimiterForAPathWithNoOrganizationIdSegment()
      throws Exception {
    RateLimiter rateLimiter = mock(RateLimiter.class);
    RateLimitPolicyRepository policies = mock(RateLimitPolicyRepository.class);
    OrganizationCapacityRateLimitingFilter filter =
        new OrganizationCapacityRateLimitingFilter(rateLimiter, policies, SYSTEM_DEFAULT);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/jwks");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertThat(response.getStatus()).isEqualTo(200);
    verify(policies, never()).findByOrganizationId(any());
  }
}
