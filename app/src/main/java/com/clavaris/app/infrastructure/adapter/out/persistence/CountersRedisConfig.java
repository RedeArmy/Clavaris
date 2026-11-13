package com.clavaris.app.infrastructure.adapter.out.persistence;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * TD-SEC-095: builds {@link CountersRedis}. A dedicated connection when {@code
 * clavaris.redis.counters.host} is set (the production compose sets it to its second Redis), and
 * the sessions' own connection when it is not.
 */
@Configuration
class CountersRedisConfig {

  private static final Logger LOG = LoggerFactory.getLogger(CountersRedisConfig.class);

  @Bean(destroyMethod = "close")
  /* package */ CountersRedis countersRedis(
      final StringRedisTemplate primary,
      @Value("${clavaris.redis.counters.host:}") final String host,
      @Value("${clavaris.redis.counters.port:6379}") final int port,
      @Value("${clavaris.redis.counters.password:}") final String password,
      @Value("${spring.data.redis.timeout:2000ms}") final Duration commandTimeout) {
    if (host == null || host.isBlank()) {
      LOG.info("event=counters_redis mode=shared_with_sessions");
      return CountersRedis.sharing(primary);
    }
    LOG.info("event=counters_redis mode=dedicated host={} port={}", host, port);
    return CountersRedis.dedicated(host, port, password, commandTimeout);
  }
}
