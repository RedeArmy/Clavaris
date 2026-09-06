package com.clavaris.clientregistry.application.usecase.verifyclientdomainownership;

import com.clavaris.clientregistry.domain.model.ClientDomainConfig;

/**
 * {@code config.verificationStatus()} is {@code VERIFIED} or {@code FAILED} — never an exception
 * for a failed lookup, see {@link DnsTxtRecordLookup}'s own Javadoc.
 */
public record VerifyClientDomainOwnershipResult(ClientDomainConfig config) {}
