package com.clavaris.identity.infrastructure.adapter.in.web;

/**
 * TD-FUT-034: request body for {@code UpdateAccountMetadataController}. Every field is raw JSON
 * text, {@code null}/blank legal (clears that tier) — see {@code UpdateAccountMetadataCommand}'s
 * own Javadoc. Always a full replace of all three tiers, never a partial update.
 */
public record UpdateAccountMetadataRequest(
    String publicMetadata, String privateMetadata, String unsafeMetadata) {}
