package com.clavaris.app.infrastructure.adapter.out.security;

/**
 * The two {@code HttpSession} attribute keys the self-service sessions/devices page is built on —
 * {@link SpringSecurityAuthenticatedSessionEstablisher} is the sole writer (populated once, right
 * after a tenant {@code Account} session is established), {@code
 * AccountActiveSessionsRepositoryBridge} is the sole reader (via the raw {@code
 * FindByIndexNameSessionRepository}, which {@code SessionRegistry}'s own {@code SessionInformation}
 * doesn't expose attributes through). One shared constants holder, not a literal string duplicated
 * in both places, so the two can never silently drift apart — same "tiny, private-constructor
 * constants class" shape as {@code SocialLinkingPolicy}.
 *
 * <p>Plain {@code String} values only: session attributes are stored in Redis as typed JSON under
 * an allow-list (TD-ARCH-039, {@code SessionJsonSerializerFactory}), and only Spring Security's own
 * types are on it. Anything of ours stored in a session should stay a {@code String}, or a {@code
 * LinkedHashMap}/{@code ArrayList} of them; an arbitrary record or POJO would fail to read back.
 */
public final class SessionDeviceAttributes {

  public static final String USER_AGENT = "clavaris.device.userAgent";

  public static final String SOURCE_IP = "clavaris.device.sourceIp";

  private SessionDeviceAttributes() {
    // Constants only.
  }
}
