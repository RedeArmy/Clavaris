package com.clavaris.organization.infrastructure.adapter.out.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataOrganizationProfileJpaRepository
    extends JpaRepository<OrganizationProfileEntity, UUID> {}
