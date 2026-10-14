package com.clavaris.app.infrastructure.adapter.out.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.http.converter.OAuth2ErrorHttpMessageConverter;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

/**
 * RFC 6749 §5.2: when the client tried to authenticate with the {@code Authorization} header and
 * failed, the 401 {@code invalid_client} response MUST carry a {@code WWW-Authenticate} header for
 * that scheme. Spring Authorization Server's own handler sends the same 401 and JSON body but
 * without it, and its stock replacement always answers 400, so this reproduces the status mapping
 * (401 for {@code invalid_client}, 400 for everything else) and adds the challenge.
 */
public final class ClientAuthenticationFailureHandler implements AuthenticationFailureHandler {

  private static final String REALM = "Clavaris";

  private final HttpMessageConverter<OAuth2Error> errorConverter;

  public ClientAuthenticationFailureHandler() {
    this.errorConverter = new OAuth2ErrorHttpMessageConverter();
  }

  // PMD.CloseResource: the wrapper only writes the error body onto the container's own response
  // stream; closing it would close that stream, which the container owns (SAS's own handlers do
  // the same).
  @SuppressWarnings("PMD.CloseResource")
  @Override
  public void onAuthenticationFailure(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final AuthenticationException exception)
      throws IOException {
    final OAuth2Error error =
        exception instanceof OAuth2AuthenticationException oauth2
            ? oauth2.getError()
            : new OAuth2Error(OAuth2ErrorCodes.INVALID_CLIENT);
    final boolean invalidClient = OAuth2ErrorCodes.INVALID_CLIENT.equals(error.getErrorCode());
    final HttpStatus status = invalidClient ? HttpStatus.UNAUTHORIZED : HttpStatus.BAD_REQUEST;
    final String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (invalidClient
        && authorization != null
        && authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
      response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"" + REALM + "\"");
    }
    final ServletServerHttpResponse httpResponse = new ServletServerHttpResponse(response);
    httpResponse.setStatusCode(status);
    errorConverter.write(error, null, httpResponse);
  }
}
