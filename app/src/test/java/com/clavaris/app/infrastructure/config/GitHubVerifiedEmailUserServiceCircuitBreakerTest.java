package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * SDE-III optimization pass, P2 point 4: dedicated coverage for the property {@link
 * GitHubVerifiedEmailUserServiceTest} can't practically exercise — {@code
 * CircuitBreaker.ofDefaults} (what the test-only constructor builds) needs 100 calls in its sliding
 * window before it can ever trip. This class constructs {@link GitHubVerifiedEmailUserService}
 * directly with a deliberately small, test-friendly window instead — same technique {@code
 * ResendHttpClientTest} already establishes for the identical problem on Resend's own circuit
 * breaker.
 */
class GitHubVerifiedEmailUserServiceCircuitBreakerTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final RestTemplate userInfoRestOperations = new RestTemplate();
  private HttpServer stubServer;

  @BeforeEach
  void startStubServer() throws IOException {
    stubServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    // The delegate's own base /user call (a real RestOperations call, not the circuit-breaker-
    // wrapped one this test cares about) must succeed for loadUser to ever reach the
    // /user/emails call at all.
    stubServer.createContext(
        "/user",
        exchange -> {
          byte[] bytes = "{\"id\":12345,\"login\":\"octocat\"}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    stubServer.start();
  }

  @AfterEach
  void stopStubServer() {
    stubServer.stop(0);
  }

  @Test
  void tripsOpenAfterRepeatedFailuresAndThenFailsFastWithoutCallingTheRealHttpClientAgain()
      throws IOException, InterruptedException {
    HttpClient httpClient = mock(HttpClient.class);
    when(httpClient.send(any(), any())).thenThrow(new IOException("connection refused"));
    CircuitBreaker circuitBreaker =
        CircuitBreaker.of(
            "github-emails-test",
            CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMinutes(5))
                .build());
    GitHubVerifiedEmailUserService service =
        new GitHubVerifiedEmailUserService(
            httpClient,
            objectMapper,
            URI.create(stubServerUri() + "/user/emails"),
            userInfoRestOperations,
            circuitBreaker);
    OAuth2UserRequest request = userRequest();

    // Two failures fill the 2-call sliding window and open the circuit — both still reach the
    // real (mocked) HttpClient.
    assertThatExceptionOfType(OAuth2AuthenticationException.class)
        .isThrownBy(() -> service.loadUser(request));
    assertThatExceptionOfType(OAuth2AuthenticationException.class)
        .isThrownBy(() -> service.loadUser(request));
    assertThat(circuitBreaker.getState())
        .as("2 failures out of a 2-call window at a 50% threshold must open the circuit")
        .isEqualTo(CircuitBreaker.State.OPEN);

    // The third call must fail immediately, with the circuit-breaker-specific message — and,
    // decisively, without ever reaching the real HttpClient again.
    assertThatExceptionOfType(OAuth2AuthenticationException.class)
        .isThrownBy(() -> service.loadUser(request))
        .withMessageContaining("circuit breaker is open");
    verify(httpClient, times(2)).send(any(), any());
  }

  private OAuth2UserRequest userRequest() {
    OAuth2AccessToken accessToken =
        new OAuth2AccessToken(
            OAuth2AccessToken.TokenType.BEARER,
            "test-access-token",
            Instant.now(),
            Instant.now().plusSeconds(3600));
    ClientRegistration registration =
        ClientRegistration.withRegistrationId("github")
            .clientId("test-client-id")
            .clientSecret("test-client-secret")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
            .authorizationUri("https://example.test/authorize")
            .tokenUri("https://example.test/token")
            .userInfoUri(stubServerUri() + "/user")
            .userNameAttributeName("id")
            .clientName("github")
            .build();
    return new OAuth2UserRequest(registration, accessToken);
  }

  private String stubServerUri() {
    return "http://localhost:" + stubServer.getAddress().getPort();
  }
}
