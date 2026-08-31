package com.clavaris.identity.application.usecase.recordaccountlogindevice;

import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.requestemailverification.MailDeliveryException;
import com.clavaris.identity.application.usecase.requestemailverification.MailSender;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.KnownDevice;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestration for {@link RecordAccountLoginDeviceUseCase}. Known device → {@link
 * KnownDevice#touch()} + save, done. Unknown device → persist the new {@link KnownDevice} row
 * first, then attempt the "new device login" notification email.
 *
 * <p><b>Never lets a mail failure propagate</b> — a deliberate departure from {@code
 * RequestEmailVerificationService}'s own style (which lets {@link MailDeliveryException}
 * propagate): there, a failed send *is* the whole point of that request and the caller needs to
 * know. Here, the caller is mid-login (this runs right after {@code
 * AuthenticatedSessionEstablisher#establish}/{@code establishViaSocialLogin} already succeeded) — a
 * Resend hiccup must never turn an otherwise-successful login into a failed request. The device row
 * is persisted before the send attempt specifically so a failed notification never also loses the
 * "we've now seen this device" bookkeeping — the next login from the same device won't re-notify
 * even if this attempt's email never arrived.
 */
public class RecordAccountLoginDeviceService implements RecordAccountLoginDeviceUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(RecordAccountLoginDeviceService.class);

  private final KnownDeviceRepository knownDevices;
  private final AccountRepository accounts;
  private final MailSender mailSender;

  public RecordAccountLoginDeviceService(
      final KnownDeviceRepository knownDevices,
      final AccountRepository accounts,
      final MailSender mailSender) {
    this.knownDevices = knownDevices;
    this.accounts = accounts;
    this.mailSender = mailSender;
  }

  // Three genuinely distinct outcomes (nothing to fingerprint / already-known device / newly-seen
  // device), each with its own exit — same "each outcome needs its own exit" rationale as e.g.
  // RegisterAccountController's own identical suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @Override
  public void handle(final RecordAccountLoginDeviceCommand command) {
    if (command.userAgent() == null || command.userAgent().isBlank()) {
      // Nothing to fingerprint — a non-browser client sent no User-Agent header at all. Not an
      // error: this feature simply has nothing meaningful to do for this login.
      return;
    }

    final Optional<KnownDevice> existing =
        knownDevices.findByAccountIdAndUserAgent(command.accountId(), command.userAgent());
    if (existing.isPresent()) {
      final KnownDevice device = existing.get();
      device.touch();
      knownDevices.save(device);
      return;
    }

    final KnownDevice device = KnownDevice.recognize(command.accountId(), command.userAgent());
    knownDevices.save(device);

    // Defensive only — a real Account is guaranteed to exist by the time this runs (this is only
    // ever called right after that same Account just authenticated); an unresolvable id here
    // would mean something upstream is already badly broken, not a case worth a notification.
    final Account account = accounts.findById(command.accountId()).orElse(null);
    if (account == null) {
      return;
    }

    try {
      mailSender.sendNewDeviceLoginNotification(
          account.email().value(),
          account.organizationId(),
          command.userAgent(),
          command.sourceIp(),
          device.firstSeenAt());
    } catch (final MailDeliveryException e) {
      // BR-DATA-01: status/event only, never the recipient address or any other PII.
      LOG.warn("event=new_device_notification_failed", e);
    }
  }
}
