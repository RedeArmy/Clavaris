package com.clavaris.app.infrastructure.adapter.out.persistence;

import java.time.Duration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * TD-SEC-095: the Redis the abuse counters live on, the rate limiter's buckets and the
 * one-time-code lockout, apart from the one the HTTP sessions live on.
 *
 * <p>They were one instance with a hard ceiling and no eviction (TD-SEC-083), chosen so that a full
 * Redis is loud and local rather than silently dropping someone's session. The residue: the code
 * counters are keyed on what an anonymous caller types, so a stream of distinct values fills Redis,
 * and past the ceiling the limiter fails open (by design, TD-SEC-022) while session writes fail
 * too, which is a sign-in outage for everyone with no brute-force protection meanwhile. A flood of
 * counters could take the sessions down with it.
 *
 * <p>Separate, the two have different right answers to a full memory. Sessions keep {@code
 * noeviction}: dropping one signs a person out. Every counter key carries a TTL, so the counters'
 * instance can run {@code volatile-ttl} and evict the keys closest to expiry when it fills: a flood
 * costs the oldest counters some of their history, and nobody is signed out.
 *
 * <p>With {@code clavaris.redis.counters.host} unset this reuses the sessions' own connection, so a
 * local run, a test and a single-Redis deployment are unchanged. It is deliberately not a {@code
 * RedisConnectionFactory} bean: Spring Boot builds its own only when none exists, and a second one
 * would switch the first off.
 */
public final class CountersRedis implements AutoCloseable {

  private final StringRedisTemplate template;
  // Null when the counters share the primary connection, which this does not own and never closes.
  private final LettuceConnectionFactory ownedFactory;

  private CountersRedis(final StringRedisTemplate template, final LettuceConnectionFactory owned) {
    this.template = template;
    this.ownedFactory = owned;
  }

  /** The counters on an existing connection: the default, and what every test uses. */
  public static CountersRedis sharing(final StringRedisTemplate template) {
    return new CountersRedis(template, null);
  }

  /** The counters on a Redis of their own, with the same command timeout as the primary one. */
  public static CountersRedis dedicated(
      final String host, final int port, final String password, final Duration commandTimeout) {
    final RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(host, port);
    if (password != null && !password.isBlank()) {
      standalone.setPassword(RedisPassword.of(password));
    }
    final LettuceConnectionFactory factory =
        new LettuceConnectionFactory(
            standalone,
            LettuceClientConfiguration.builder().commandTimeout(commandTimeout).build());
    factory.afterPropertiesSet();
    final StringRedisTemplate template = new StringRedisTemplate(factory);
    template.afterPropertiesSet();
    return new CountersRedis(template, factory);
  }

  public StringRedisTemplate template() {
    return template;
  }

  /** Whether the counters have a Redis of their own. */
  public boolean isDedicated() {
    return ownedFactory != null;
  }

  @Override
  public void close() {
    if (ownedFactory != null) {
      ownedFactory.destroy();
    }
  }
}
