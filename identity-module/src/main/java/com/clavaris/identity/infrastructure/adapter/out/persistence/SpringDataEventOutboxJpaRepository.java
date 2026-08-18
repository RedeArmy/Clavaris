package com.clavaris.identity.infrastructure.adapter.out.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataEventOutboxJpaRepository extends JpaRepository<EventOutboxEntity, UUID> {}
