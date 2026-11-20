-- TD-SEC-090: how many stored secrets are still in the format written before the envelope
-- (`v1.<keyId>.<...>`), one row per store. A value is old exactly when EnvelopeCipher.isLegacy says so:
-- it does not start with `v1.`. This file is the single source of the queries: check-legacy-envelope.sh
-- runs it on a host, and LegacyEnvelopeQueriesIntegrationTest runs it against the real migrated schema
-- with rows written by the real cipher, so a renamed column or a changed format fails a build and not
-- a flip of CRYPTO_ACCEPT_LEGACY_ENVELOPE in production.
SELECT 'totp_credentials.secret_encrypted' AS store, count(*) AS legacy_values
  FROM totp_credentials
 WHERE secret_encrypted NOT LIKE 'v1.%'
UNION ALL
SELECT 'webhook_endpoints.current_or_previous_secret', count(*)
  FROM webhook_endpoints
 WHERE current_secret_encrypted NOT LIKE 'v1.%'
    OR (previous_secret_encrypted IS NOT NULL AND previous_secret_encrypted NOT LIKE 'v1.%')
UNION ALL
SELECT 'organization_social_credentials.client_secret_encrypted', count(*)
  FROM organization_social_credentials
 WHERE client_secret_encrypted NOT LIKE 'v1.%';
