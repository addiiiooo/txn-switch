/* SPDX-License-Identifier: MIT */
package com.txnswitch.adapter.out.persistence;

import com.txnswitch.application.port.IdempotencyStore;
import com.txnswitch.domain.idempotency.IdempotencyRecord;
import com.txnswitch.domain.idempotency.IdempotencySnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class IdempotencyStoreAdapter implements IdempotencyStore {

  private final IdempotencyJpaRepository records;

  public IdempotencyStoreAdapter(IdempotencyJpaRepository records) {
    this.records = records;
  }

  @Override
  public Optional<IdempotencyRecord> claim(IdempotencyRecord candidate) {
    IdempotencySnapshot s = candidate.snapshot();
    int inserted =
        records.insertIfAbsent(
            s.id(),
            s.merchantId(),
            s.idempotencyKey(),
            s.requestFingerprint(),
            s.authorizationId(),
            s.leaseExpiresAt(),
            s.createdAt(),
            s.expiresAt());
    // The row we just wrote is the object we already hold; there is nothing to read back.
    return inserted == 1 ? Optional.of(candidate) : Optional.empty();
  }

  @Override
  public Optional<IdempotencyRecord> find(String merchantId, String idempotencyKey) {
    return records
        .findByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey)
        .map(PersistenceMapper::toDomain);
  }

  @Override
  public boolean takeOverLease(UUID recordId, Instant now, Duration leaseTtl) {
    return records.takeOverLease(recordId, now, now.plus(leaseTtl)) == 1;
  }

  @Override
  public void releaseLease(UUID recordId, Instant now, boolean downstreamAttempted) {
    records.releaseLease(recordId, now, downstreamAttempted);
  }

  @Override
  public void complete(UUID recordId, int httpStatus, String responseBody) {
    IdempotencyRecordEntity entity =
        records
            .findById(recordId)
            .orElseThrow(
                () -> new IllegalStateException("idempotency record " + recordId + " disappeared"));
    entity.complete(httpStatus, responseBody);
    records.save(entity);
  }

  @Override
  public int purgeExpired(Instant now, int limit) {
    return records.purgeExpired(now, limit);
  }

  @Override
  public long countUnresolved(Instant leaseLapsedBefore) {
    return records.countUnresolved(leaseLapsedBefore);
  }
}
