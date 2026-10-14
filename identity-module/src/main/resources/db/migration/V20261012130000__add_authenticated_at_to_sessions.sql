-- OIDC Core 2: auth_time is when the End-User authenticated, which a refreshed ID token must keep
-- reporting unchanged. sessions.created_at is when the Session opened (at the code exchange, after
-- the login), so it cannot stand in for it; this column holds the real authentication instant.
-- Existing rows have no better value than created_at, which is what their refreshed ID tokens
-- already used, so they keep reporting exactly that.
ALTER TABLE sessions ADD COLUMN authenticated_at timestamptz;
UPDATE sessions SET authenticated_at = created_at;
ALTER TABLE sessions ALTER COLUMN authenticated_at SET NOT NULL;
-- The application always sets it; the default only covers a row inserted by hand.
ALTER TABLE sessions ALTER COLUMN authenticated_at SET DEFAULT now();
