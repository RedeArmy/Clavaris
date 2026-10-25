package com.clavaris.identity.infrastructure.config;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.identity.application.usecase.confirmnewdeviceloginalert.ConfirmNewDeviceLoginAlertService;
import com.clavaris.identity.application.usecase.confirmnewdeviceloginalert.ConfirmNewDeviceLoginAlertUseCase;
import com.clavaris.identity.application.usecase.getloginactivityforaccount.GetLoginActivityForAccountService;
import com.clavaris.identity.application.usecase.getloginactivityforaccount.GetLoginActivityForAccountUseCase;
import com.clavaris.identity.application.usecase.recordaccountlogindevice.KnownDeviceRepository;
import com.clavaris.identity.application.usecase.recordaccountlogindevice.RecordAccountLoginDeviceService;
import com.clavaris.identity.application.usecase.recordaccountlogindevice.RecordAccountLoginDeviceUseCase;
import com.clavaris.identity.application.usecase.recordloginevent.LoginEventRepository;
import com.clavaris.identity.application.usecase.recordloginevent.RecordLoginEventService;
import com.clavaris.identity.application.usecase.recordloginevent.RecordLoginEventUseCase;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.EventOutboxWriter;
import com.clavaris.identity.application.usecase.requestemailverification.MailSender;
import com.clavaris.identity.application.usecase.requestemailverification.VerificationTokenRepository;
import com.clavaris.identity.application.usecase.suspendaccount.SuspendAccountUseCase;
import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the identity use cases for: known devices, login activity and the new-device alert.
 *
 * <p>Deliberately kept out of {@code application/usecase} itself: a service's only Spring
 * dependency is {@code @Transactional} on its {@code handle} method
 * (first-vertical-slice-blueprint.md §2.3), so bean registration lives here, in infrastructure.
 * Split from one 868-line {@code IdentityUseCaseConfig} by area; beans reach each other through
 * ordinary constructor injection, so the split changes no wiring.
 */
// PMD.LongVariable: ports are named for what they are (verificationTokens, accountTokenRevoker), as
// in the services they are handed to.
@SuppressWarnings("PMD.LongVariable")
@Configuration
class KnownDeviceUseCaseConfig {

  // Written out explicitly for the same reason as Argon2PasswordHasher's own constructor
  // (see its comment) — only Spring's own component scan ever needs to instantiate this class.
  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ KnownDeviceUseCaseConfig() {
    // Intentionally empty — this class holds no state, only @Bean methods.
  }

  // TD-PERF-011: a small, bounded pool of daemon threads dedicated to the new-device-login
  // notification email — decouples RecordAccountLoginDeviceService's own outbound Resend call
  // from the request thread completing every unrecognized-device login, the same "don't block
  // the calling thread on a third-party HTTP call" pattern TD-PERF-004 already established for
  // webhook delivery (webhookDeliveryExecutor, WebhookUseCaseConfig). Small on purpose: this
  // fires only on unrecognized-device logins, not every login, nowhere near the concurrency a
  // batch webhook dispatch tick needs. destroyMethod="shutdown": same Spring-managed-lifecycle
  // discipline as that bean, so a repeatedly-started-and-stopped @SpringBootTest context doesn't
  // leak thread pools.
  //
  // TD-PERF-040: the queue is bounded. Executors.newFixedThreadPool queues without limit, so during
  // a mail-provider outage (the circuit breaker only shortens each send, it does not stop new ones
  // being queued) a burst of new-device logins would pile tasks, each holding an Account, in memory
  // indefinitely. Past the capacity a task is rejected, which RecordAccountLoginDeviceService
  // already
  // catches and logs: the login is unaffected, and so is the alert token, which is minted before
  // the task is queued. Dropping an informational email beats exhausting the heap.
  @Bean(destroyMethod = "shutdown")
  /* package */ ExecutorService newDeviceNotificationExecutor(
      @Value("${clavaris.known-device.notification-concurrency:4}") final int concurrency,
      @Value("${clavaris.known-device.notification-queue-capacity:1000}") final int queueCapacity) {
    final AtomicInteger threadCount = new AtomicInteger();
    final ThreadFactory namedThreads =
        runnable -> {
          final Thread thread =
              new Thread(runnable, "new-device-notification-" + threadCount.incrementAndGet());
          // Never blocks JVM shutdown on an in-flight notification send — same posture
          // webhookDeliveryExecutor's own identical thread factory already establishes.
          thread.setDaemon(true);
          return thread;
        };
    return new ThreadPoolExecutor(
        concurrency,
        concurrency,
        0L,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(queueCapacity),
        namedThreads,
        new ThreadPoolExecutor.AbortPolicy());
  }

  // New-device login email notification.
  @Bean
  // Code review finding (2026-09-01): the migration grandfather cutoff — see
  // RecordAccountLoginDeviceService's own Javadoc. Defaults to V20260831100000's own timestamp,
  // the actual moment the DeviceCookie mechanism replaced User-Agent fingerprinting; overridable
  // only in case this ever needs re-running against a differently-timed deployment.
  /* package */ RecordAccountLoginDeviceUseCase recordAccountLoginDeviceUseCase(
      final KnownDeviceRepository knownDevices,
      final AccountRepository accounts,
      final MailSender mailSender,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter outbox,
      @Value("${clavaris.known-device.migration-cutover-at:2026-08-31T10:00:00Z}")
          final Instant deviceCookieMigrationCutoverAt,
      final ExecutorService newDeviceNotificationExecutor,
      final VerificationTokenRepository verificationTokens) {
    return new RecordAccountLoginDeviceService(
        knownDevices,
        accounts,
        mailSender,
        auditEvents,
        outbox,
        deviceCookieMigrationCutoverAt,
        newDeviceNotificationExecutor,
        verificationTokens);
  }

  // TD-FUT-034, Clerk "View Profile" activity heatmap parity.
  @Bean
  /* package */ RecordLoginEventUseCase recordLoginEventUseCase(
      final LoginEventRepository loginEvents) {
    return new RecordLoginEventService(loginEvents);
  }

  // TD-FUT-034, Clerk "View Profile" activity heatmap parity.
  @Bean
  /* package */ GetLoginActivityForAccountUseCase getLoginActivityForAccountUseCase(
      final LoginEventRepository loginEvents) {
    return new GetLoginActivityForAccountService(loginEvents);
  }

  // TD-FUT-025: the "this wasn't me" half of the new-device login alert — see
  // ConfirmNewDeviceLoginAlertService's own Javadoc for why this delegates straight to the
  // already-tested suspendAccountUseCase bean above rather than duplicating its cascade.
  @Bean
  /* package */ ConfirmNewDeviceLoginAlertUseCase confirmNewDeviceLoginAlertUseCase(
      final VerificationTokenRepository verificationTokens,
      final SuspendAccountUseCase suspendAccountUseCase) {
    return new ConfirmNewDeviceLoginAlertService(verificationTokens, suspendAccountUseCase);
  }
}
