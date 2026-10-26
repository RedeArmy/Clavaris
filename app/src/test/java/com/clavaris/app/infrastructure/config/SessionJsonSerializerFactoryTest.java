package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.savedrequest.DefaultSavedRequest;

/**
 * TD-ARCH-039: the session-compatibility test the register asked for. Every kind of value this
 * application puts in an {@code HttpSession} must survive a round trip through the JSON serializer
 * Spring Session now uses; if one stops doing so (a framework upgrade changing a class, a new
 * attribute of a type nobody allow-listed) this fails before a person is signed out by it.
 */
class SessionJsonSerializerFactoryTest {

  private final RedisSerializer<Object> serializer =
      SessionJsonSerializerFactory.create(getClass().getClassLoader());

  private Object roundTrip(final Object value) {
    return serializer.deserialize(serializer.serialize(value));
  }

  @Test
  void theSecurityContextOfASignedInAccountSurvives() {
    SecurityContext context = new SecurityContextImpl();
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(
            "3f2a1c9e-0000-4000-8000-000000000001",
            null,
            List.of(
                new SimpleGrantedAuthority("ROLE_ACCOUNT"),
                new SimpleGrantedAuthority("FACTOR_PASSWORD"))));

    Object read = roundTrip(context);

    assertThat(read).isInstanceOf(SecurityContext.class);
    var authentication = ((SecurityContext) read).getAuthentication();
    assertThat(authentication.isAuthenticated()).isTrue();
    assertThat(authentication.getName()).isEqualTo("3f2a1c9e-0000-4000-8000-000000000001");
    assertThat(authentication.getAuthorities())
        .extracting(Object::toString)
        .containsExactlyInAnyOrder("ROLE_ACCOUNT", "FACTOR_PASSWORD");
  }

  @Test
  void theSavedRequestThatResumesAnAuthorizeFlowSurvives() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/o/1234/oauth2/authorize");
    request.setQueryString("client_id=c&state=s");
    request.setParameter("client_id", "c");
    request.setParameter("state", "s");
    DefaultSavedRequest saved = new DefaultSavedRequest(request);

    Object read = roundTrip(saved);

    assertThat(read).isInstanceOf(DefaultSavedRequest.class);
    assertThat(((DefaultSavedRequest) read).getRedirectUrl()).isEqualTo(saved.getRedirectUrl());
  }

  @Test
  void theCsrfTokenSurvives() {
    CsrfToken token = new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "a-token-value");

    Object read = roundTrip(token);

    assertThat(read).isInstanceOf(CsrfToken.class);
    assertThat(((CsrfToken) read).getToken()).isEqualTo("a-token-value");
  }

  @Test
  void aSocialLoginAuthorizationRequestSurvives() {
    OAuth2AuthorizationRequest request =
        OAuth2AuthorizationRequest.authorizationCode()
            .authorizationUri("https://accounts.example.com/auth")
            .clientId("client")
            .redirectUri("https://clavaris.example.com/login/oauth2/code/google")
            .scopes(java.util.Set.of("openid", "email"))
            .state("state-value")
            .build();

    Object read = roundTrip(request);

    assertThat(read).isInstanceOf(OAuth2AuthorizationRequest.class);
    assertThat(((OAuth2AuthorizationRequest) read).getState()).isEqualTo("state-value");
    assertThat(((OAuth2AuthorizationRequest) read).getScopes())
        .containsExactlyInAnyOrder("openid", "email");
  }

  @Test
  void theApplicationsOwnPlainAttributesSurvive() {
    Map<String, String> notes = new LinkedHashMap<>();
    notes.put("client_id=c&state=s", Instant.parse("2026-10-26T10:00:00Z").toString());

    assertThat(roundTrip("a plain string")).isEqualTo("a plain string");
    assertThat(roundTrip(notes)).isEqualTo(notes).isInstanceOf(LinkedHashMap.class);
    // A mutable list, not List.of(...): the JDK's immutable collections carry no type id.
    assertThat(roundTrip(new java.util.ArrayList<>(List.of("a", "b"))))
        .isEqualTo(List.of("a", "b"));
  }

  @Test
  void theStoredFormIsReadableJsonNotJavaSerialization() {
    byte[] stored = serializer.serialize("hello");

    String text = new String(stored, StandardCharsets.UTF_8);
    assertThat(text).contains("hello");
    assertThat(stored[0]).as("not the 0xAC 0xED magic of a Java stream").isNotEqualTo((byte) 0xAC);
  }

  @Test
  void aTypeThatIsNotOnTheAllowListIsRefusedNotInstantiated() {
    byte[] hostile =
        "{\"@class\":\"java.lang.ProcessBuilder\",\"command\":[\"id\"]}"
            .getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() -> serializer.deserialize(hostile)).isInstanceOf(RuntimeException.class);
  }
}
