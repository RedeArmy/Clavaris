package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import tools.jackson.databind.ObjectMapper;

/**
 * Same "real local {@link HttpServer} for the happy path, mocked {@link HttpClient} for error
 * paths" strategy as {@code ResendMailSenderTest} — the only other class in this codebase that
 * makes a real outbound third-party API call. Two calls happen per {@link
 * GitHubVerifiedEmailUserService#loadUser}: {@code DefaultOAuth2UserService}'s own internal fetch
 * of {@code userInfoUri} (this test always points that at the local stub server's {@code /user},
 * never mocked — {@code DefaultOAuth2UserService} has no injectable {@link HttpClient} of its own
 * to mock), and this class's own {@code GET /user/emails} call (via the injected {@link HttpClient}
 * — real for the happy path, mocked for the error paths).
 */
class GitHubVerifiedEmailUserServiceTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private HttpServer stubServer;

  @BeforeEach
  void startStubServer() throws IOException {
    stubServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    stubServer.createContext(
        "/user", exchange -> respond(exchange, 200, "{\"id\":12345,\"login\":\"octocat\"}"));
    stubServer.start();
  }

  @AfterEach
  void stopStubServer() {
    stubServer.stop(0);
  }

  @Test
  void attachesThePrimaryVerifiedEmailAsASyntheticAttribute() {
    stubServer.createContext(
        "/user/emails",
        exchange ->
            respond(
                exchange,
                200,
                "[{\"email\":\"secondary@example.com\",\"primary\":false,\"verified\":true},"
                    + "{\"email\":\"verified@example.com\",\"primary\":true,\"verified\":true}]"));
    GitHubVerifiedEmailUserService service = serviceWith(HttpClient.newHttpClient());

    OAuth2User user = service.loadUser(userRequest());

    assertThat(user.<Integer>getAttribute("id")).isEqualTo(12345);
    assertThat(user.<String>getAttribute(GitHubVerifiedEmailUserService.VERIFIED_EMAIL_ATTRIBUTE))
        .isEqualTo("verified@example.com");
  }

  @Test
  void attachesNoAttributeWhenNoEmailIsBothPrimaryAndVerified() {
    stubServer.createContext(
        "/user/emails",
        exchange ->
            respond(
                exchange,
                200,
                "[{\"email\":\"unverified@example.com\",\"primary\":true,\"verified\":false}]"));
    GitHubVerifiedEmailUserService service = serviceWith(HttpClient.newHttpClient());

    OAuth2User user = service.loadUser(userRequest());

    assertThat(user.<String>getAttribute(GitHubVerifiedEmailUserService.VERIFIED_EMAIL_ATTRIBUTE))
        .isNull();
  }

  @Test
  void delegatesUnmodifiedForANonGitHubRegistration() {
    stubServer.createContext(
        "/other-user", exchange -> respond(exchange, 200, "{\"sub\":\"abc\"}"));
    GitHubVerifiedEmailUserService service = serviceWith(HttpClient.newHttpClient());
    ClientRegistration googleLike =
        registrationBuilder("google", "/other-user").userNameAttributeName("sub").build();
    OAuth2UserRequest request = new OAuth2UserRequest(googleLike, accessToken());

    OAuth2User user = service.loadUser(request);

    assertThat(user.<String>getAttribute("sub")).isEqualTo("abc");
    assertThat(user.<String>getAttribute(GitHubVerifiedEmailUserService.VERIFIED_EMAIL_ATTRIBUTE))
        .isNull();
  }

  @Test
  void throwsOAuth2AuthenticationExceptionOnMalformedJson() {
    // Code review finding: Jackson 3.x's JacksonException is unchecked and Spring Security's own
    // exception handling only catches AuthenticationException subtypes — a malformed-JSON 200
    // response must surface as this class's own OAuth2AuthenticationException, same as every
    // other failure mode here, not as a raw, uncaught JacksonException.
    stubServer.createContext(
        "/user/emails-malformed", exchange -> respond(exchange, 200, "not-valid-json{"));
    GitHubVerifiedEmailUserService service =
        new GitHubVerifiedEmailUserService(
            HttpClient.newHttpClient(),
            objectMapper,
            URI.create(stubServerUri() + "/user/emails-malformed"));

    assertThatExceptionOfType(OAuth2AuthenticationException.class)
        .isThrownBy(() -> service.loadUser(userRequest()));
  }

  @Test
  void cachesTheVerifiedEmailPerGitHubUserIdWithinTheTtl() {
    // Code review finding (efficiency): a returning user's own second login within the cache TTL
    // must not pay a second real /user/emails round trip.
    AtomicInteger emailsCallCount = new AtomicInteger();
    stubServer.createContext(
        "/user/emails",
        exchange -> {
          emailsCallCount.incrementAndGet();
          respond(
              exchange,
              200,
              "[{\"email\":\"verified@example.com\",\"primary\":true,\"verified\":true}]");
        });
    GitHubVerifiedEmailUserService service = serviceWith(HttpClient.newHttpClient());

    service.loadUser(userRequest());
    OAuth2User second = service.loadUser(userRequest());

    assertThat(emailsCallCount.get()).isEqualTo(1);
    assertThat(second.<String>getAttribute(GitHubVerifiedEmailUserService.VERIFIED_EMAIL_ATTRIBUTE))
        .isEqualTo("verified@example.com");
  }

  @Test
  void throwsOAuth2AuthenticationExceptionOnANon200EmailsResponse() {
    stubServer.createContext(
        "/emails-forbidden", exchange -> respond(exchange, 403, "{\"message\":\"Forbidden\"}"));
    GitHubVerifiedEmailUserService service =
        new GitHubVerifiedEmailUserService(
            HttpClient.newHttpClient(),
            objectMapper,
            URI.create(stubServerUri() + "/emails-forbidden"));

    assertThatExceptionOfType(OAuth2AuthenticationException.class)
        .isThrownBy(() -> service.loadUser(userRequest()))
        .withMessageContaining("403");
  }

  @Test
  void throwsOAuth2AuthenticationExceptionWrappingAnIOException()
      throws IOException, InterruptedException {
    HttpClient httpClient = mock(HttpClient.class);
    IOException networkFailure = new IOException("connection reset");
    when(httpClient.send(any(), any())).thenThrow(networkFailure);
    GitHubVerifiedEmailUserService service = serviceWith(httpClient);

    assertThatExceptionOfType(OAuth2AuthenticationException.class)
        .isThrownBy(() -> service.loadUser(userRequest()))
        .withCause(networkFailure);
  }

  @Test
  void restoresTheInterruptFlagOnInterruptedException() throws IOException, InterruptedException {
    HttpClient httpClient = mock(HttpClient.class);
    when(httpClient.send(any(), any())).thenThrow(new InterruptedException("interrupted mid-send"));
    GitHubVerifiedEmailUserService service = serviceWith(httpClient);

    try {
      assertThatExceptionOfType(OAuth2AuthenticationException.class)
          .isThrownBy(() -> service.loadUser(userRequest()));
      assertThat(Thread.interrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  private GitHubVerifiedEmailUserService serviceWith(final HttpClient httpClient) {
    return new GitHubVerifiedEmailUserService(
        httpClient, objectMapper, URI.create(stubServerUri() + "/user/emails"));
  }

  private OAuth2UserRequest userRequest() {
    return new OAuth2UserRequest(registrationBuilder("github", "/user").build(), accessToken());
  }

  private OAuth2AccessToken accessToken() {
    return new OAuth2AccessToken(
        OAuth2AccessToken.TokenType.BEARER,
        "test-access-token",
        Instant.now(),
        Instant.now().plusSeconds(3600));
  }

  private ClientRegistration.Builder registrationBuilder(
      final String registrationId, final String userInfoPath) {
    return ClientRegistration.withRegistrationId(registrationId)
        .clientId("test-client-id")
        .clientSecret("test-client-secret")
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
        .authorizationUri("https://example.test/authorize")
        .tokenUri("https://example.test/token")
        .userInfoUri(stubServerUri() + userInfoPath)
        .userNameAttributeName("id")
        .clientName(registrationId);
  }

  private String stubServerUri() {
    return "http://localhost:" + stubServer.getAddress().getPort();
  }

  private void respond(
      final com.sun.net.httpserver.HttpExchange exchange, final int statusCode, final String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(statusCode, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
