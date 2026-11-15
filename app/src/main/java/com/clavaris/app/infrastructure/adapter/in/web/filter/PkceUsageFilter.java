package com.clavaris.app.infrastructure.adapter.in.web.filter;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.common.application.port.SecurityMetricsRecorder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * ADR-0043: counts the authorization requests that do and do not carry a PKCE challenge, so the
 * decision to leave {@code OPTIONAL} available (or to switch {@code
 * clavaris.oauth.pkce.allow-optional} off) is made on what clients actually send, not on a guess.
 *
 * <p>One counter, {@code clavaris.oauth.authorize.requests}, with two tags:
 *
 * <ul>
 *   <li>{@code pkce}: {@code present} when the request has a {@code code_challenge}, {@code absent}
 *       when it has none;
 *   <li>{@code policy}: the client's stored policy ({@code REQUIRED} or {@code OPTIONAL}), looked
 *       up only for a request without a challenge, and {@code unknown} when the client does not
 *       exist, is inactive, or belongs to another Organization. A request with a challenge is
 *       tagged {@code unknown} on purpose: it costs no lookup, and what is worth watching is the
 *       other half.
 * </ul>
 *
 * <p>The row to watch is {@code pkce=absent, policy=OPTIONAL}: a client that is actually using the
 * relaxation. {@code pkce=absent, policy=REQUIRED} is a request SAS is about to refuse. Both tag
 * values come from a closed set, so the counter's cardinality is six series at most; neither the
 * client id nor the Organization is a tag.
 *
 * <p>It only observes. It never changes a request or a response, and a request that is not the
 * Organization authorize endpoint goes straight through.
 */
public final class PkceUsageFilter extends OncePerRequestFilter {

  /** The counter's name. */
  public static final String METRIC = "clavaris.oauth.authorize.requests";

  private static final Pattern AUTHORIZE_PATH =
      Pattern.compile("^/o/([0-9a-fA-F-]{36})/oauth2/authorize$");
  private static final String UNKNOWN = "unknown";

  private final OAuthClientRepository oauthClients;
  private final SecurityMetricsRecorder metrics;

  public PkceUsageFilter(
      final OAuthClientRepository oauthClients, final SecurityMetricsRecorder metrics) {
    super();
    this.oauthClients = oauthClients;
    this.metrics = metrics;
  }

  @Override
  protected void doFilterInternal(
      final HttpServletRequest request, final HttpServletResponse response, final FilterChain chain)
      throws ServletException, IOException {
    if (AUTHORIZE_PATH
        .matcher(UrlPathHelper.defaultInstance.getPathWithinApplication(request))
        .matches()) {
      record(request);
    }
    chain.doFilter(request, response);
  }

  private void record(final HttpServletRequest request) {
    final String challenge = request.getParameter("code_challenge");
    if (challenge != null && !challenge.isBlank()) {
      metrics.increment(METRIC, "pkce", "present", "policy", UNKNOWN);
      return;
    }
    metrics.increment(METRIC, "pkce", "absent", "policy", storedPolicy(request));
  }

  private String storedPolicy(final HttpServletRequest request) {
    final String clientId = request.getParameter("client_id");
    if (clientId == null) {
      return UNKNOWN;
    }
    return oauthClients
        .findByClientId(clientId)
        .filter(OAuthClient::active)
        .map(client -> client.pkcePolicy().name())
        .orElse(UNKNOWN);
  }
}
