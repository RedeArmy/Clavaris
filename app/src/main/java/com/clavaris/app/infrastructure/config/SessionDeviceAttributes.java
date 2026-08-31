package com.clavaris.app.infrastructure.config;

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
 * <p>Plain {@code String} values only: this deployment has no {@code
 * springSessionDefaultRedisSerializer} bean, so {@code RedisTemplate} falls back to {@code
 * JdkSerializationRedisSerializer} for session attribute values — {@code String} is safely
 * serializable under it without any extra wiring; an arbitrary record/POJO would not be, without
 * also adding a serializer bean nothing here currently needs.
 */
final class SessionDeviceAttributes {

  /* package */ static final String USER_AGENT = "clavaris.device.userAgent";

  /* package */ static final String SOURCE_IP = "clavaris.device.sourceIp";

  private SessionDeviceAttributes() {
    // Constants only.
  }
}
