package com.clavaris.common.infrastructure.adapter.out.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataAuditEventJpaRepository extends JpaRepository<AuditEventEntity, UUID> {}
