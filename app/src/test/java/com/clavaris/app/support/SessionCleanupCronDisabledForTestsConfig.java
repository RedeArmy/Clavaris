package com.clavaris.app.support;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;

/**
 * Same race {@code RedisBackedIntegrationTest}'s own webhook-scheduler fix already documents, one
 * more real source of it: {@code DistributedSessionConfig}'s {@code cleanupCron}-every-minute
 * background job (its own Javadoc previously recorded this as "harmless log noise, not worth
 * fixing" — revisited on explicit request, same underlying race, worth closing the same way).
 *
 * <p>Can't be fixed the {@code @DynamicPropertySource} way {@code RedisBackedIntegrationTest} fixes
 * the webhook scheduler with: confirmed by decompiling {@code RedisIndexedHttpSessionConfiguration}
 * that {@code @EnableRedisIndexedHttpSession}'s {@code cleanupCron} attribute is read as a literal
 * String off the annotation with no {@code embeddedValueResolver}/property-placeholder support
 * (unlike its sibling {@code redisNamespace} attribute, which does get that treatment) — a {@code
 * ${...}} value here would be scheduled as a cron expression literally, not resolved, so {@code
 * DistributedSessionConfig} itself can't be made test-overridable this way without changing its own
 * compile-time annotation value for production too.
 *
 * <p>Instead: a {@link BeanPostProcessor} intervening at {@code postProcessBeforeInitialization},
 * before {@link RedisIndexedSessionRepository#afterPropertiesSet()} runs — confirmed by decompiling
 * that method that it's the one place the cron actually gets scheduled (a {@code
 * ThreadPoolTaskScheduler} + {@code CronTrigger}, gated on {@code cleanupCron} not already being
 * {@code "-"}), so setting the sentinel here executes strictly before that check. {@code "-"} is
 * Spring Session's own documented value for "no scheduled cleanup at all" (confirmed the same way —
 * {@code setCleanupCron} special-cases it, skipping the normal {@code CronExpression} validation),
 * not a magic string invented for this fix.
 */
@TestConfiguration
public class SessionCleanupCronDisabledForTestsConfig {

  private static final String CLEANUP_DISABLED_SENTINEL = "-";

  @Bean
  /* package */ static BeanPostProcessor sessionCleanupCronDisabler() {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessBeforeInitialization(final Object bean, final String beanName)
          throws BeansException {
        if (bean instanceof RedisIndexedSessionRepository repository) {
          repository.setCleanupCron(CLEANUP_DISABLED_SENTINEL);
        }
        return bean;
      }
    };
  }
}
