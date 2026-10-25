-- The Organization users list pages with a keyset over (created_at, id) within one Organization
-- (AccountRepository#findKeysetPageByOrganizationId, TD-PERF-020). The only index that leads with
-- organization_id is the unique one on (organization_id, email), which cannot supply that order, so
-- every page was a scan and sort of the whole tenant. Measured on one Organization of 500,000
-- accounts: 77 ms (first page) and 82 ms (next page), against 0.06 ms and 0.19 ms with this index.
--
-- Both directions of the keyset (newest-first and the "previous page" oldest-first) read this one
-- index, forwards or backwards. Plain CREATE INDEX for the same reason as the sibling migration
-- for oauth2_authorization: Flyway is transactional here (TD-FUT-039) and the table is still small.
CREATE INDEX ix_accounts_organization_id_created_at_id
    ON accounts (organization_id, created_at DESC, id DESC);
