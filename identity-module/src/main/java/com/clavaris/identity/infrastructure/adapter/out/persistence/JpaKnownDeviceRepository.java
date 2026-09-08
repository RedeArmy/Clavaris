package com.clavaris.identity.infrastructure.adapter.out.persistence;

import com.clavaris.identity.application.usecase.recordaccountlogindevice.KnownDeviceRepository;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.KnownDevice;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements the outbound port; maps between {@code domain.model.KnownDevice} (framework-free) and
 * {@link KnownDeviceEntity}.
 *
 * <p>TD-PERF-019: {@code insert} calls {@link EntityManager#persist} directly, not {@code
 * SpringDataKnownDeviceJpaRepository#save} — see {@code KnownDeviceRepository#insert}'s own Javadoc
 * for which call site that's safe for and why {@code save} itself is unchanged.
 */
@Repository
class JpaKnownDeviceRepository implements KnownDeviceRepository {

  private final SpringDataKnownDeviceJpaRepository devices;
  private final EntityManager entityManager;

  /* package */ JpaKnownDeviceRepository(
      final SpringDataKnownDeviceJpaRepository devices, final EntityManager entityManager) {
    this.devices = devices;
    this.entityManager = entityManager;
  }

  @Override
  public Optional<KnownDevice> findByAccountIdAndDeviceTokenHash(
      final AccountId accountId, final String deviceTokenHash) {
    return devices
        .findByAccountIdAndDeviceTokenHash(accountId.value(), deviceTokenHash)
        .map(this::toDomain);
  }

  @Override
  public boolean existsByAccountId(final AccountId accountId) {
    return devices.existsByAccountId(accountId.value());
  }

  @Override
  public void save(final KnownDevice device) {
    // saveAndFlush, not save: the unique constraint on known_devices.(account_id,
    // device_token_hash) must throw synchronously, right here, so RecordAccountLoginDeviceService's
    // own try/catch around this call actually catches it — same "plain save() only stages the
    // insert in the persistence context, deferring execution past the caller's own try/catch"
    // rationale JpaAccountRepository's own identical saveAndFlush already documents.
    devices.saveAndFlush(toEntity(device));
  }

  @Override
  @Transactional
  public void insert(final KnownDevice device) {
    // persist + explicit flush, not just persist: same synchronous-throw contract save()'s own
    // saveAndFlush documents above — the collision catch this call site wraps around it depends
    // on the DataIntegrityViolationException surfacing before this method returns.
    entityManager.persist(toEntity(device));
    entityManager.flush();
  }

  private KnownDeviceEntity toEntity(final KnownDevice device) {
    return new KnownDeviceEntity(
        device.id(),
        device.accountId().value(),
        device.userAgent(),
        device.deviceTokenHash(),
        device.firstSeenAt(),
        device.lastSeenAt());
  }

  private KnownDevice toDomain(final KnownDeviceEntity entity) {
    return KnownDevice.reconstitute(
        entity.getId(),
        new AccountId(entity.getAccountId()),
        entity.getUserAgent(),
        entity.getDeviceTokenHash(),
        entity.getFirstSeenAt(),
        entity.getLastSeenAt());
  }
}
