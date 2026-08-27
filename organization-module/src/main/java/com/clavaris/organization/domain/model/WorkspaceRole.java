package com.clavaris.organization.domain.model;

/**
 * BR-WS-05: Clavaris-internal workspace roles only — deliberately two values, not the
 * originally-documented {@code OWNER/ADMIN/MEMBER} (ADR-0010 §3's addendum, 2026-08-27, supersedes
 * that design for v1). {@code ADMIN} can manage the workspace's own membership (add/remove members,
 * change roles); {@code MEMBER} cannot. Any business/product-domain role (e.g. "recruiter",
 * "candidate") is explicitly out of scope here — that differentiation belongs entirely to the
 * consuming application (e.g. JobSeeker), never to Clavaris.
 */
public enum WorkspaceRole {
  ADMIN,
  MEMBER
}
