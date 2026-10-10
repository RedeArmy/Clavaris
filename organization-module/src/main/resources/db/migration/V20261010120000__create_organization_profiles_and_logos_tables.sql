-- What an Organization says about itself, apart from its name: a description for its card on the
-- dashboard, the name of the consuming application it fronts, and its brand colour and logo.
--
-- A separate table, not more columns on organizations, so the heavily used Organization aggregate
-- and every query on it are untouched. A row exists only once someone has filled something in;
-- absence means "nothing set", which is the state every existing Organization starts in.
--
-- application_name and brand_color are the Organization-level defaults for the consuming
-- application's sign-in, consent and email branding. An OAuth client's own branding, when set, wins
-- over them; with neither, the pages and emails use the Organization's name.
--
-- logo_updated_at is kept here, not derived from organization_logos, so the dashboard's list (and
-- the cache-busting version of the logo's URL) never has to read the image itself.
CREATE TABLE organization_profiles (
    organization_id   uuid PRIMARY KEY REFERENCES organizations (id) ON DELETE CASCADE,
    description       varchar(500),
    application_name  varchar(100),
    brand_color       varchar(7),
    logo_updated_at   timestamptz,
    updated_at        timestamptz NOT NULL DEFAULT now()
);

-- The logo image itself, in its own table so listing profiles never loads it. Small by design (the
-- application caps it at 1 MB and accepts only PNG, JPEG, WebP and GIF), so it lives in the database
-- rather than in an external object store, and is served from the platform's own origin.
CREATE TABLE organization_logos (
    organization_id   uuid PRIMARY KEY REFERENCES organizations (id) ON DELETE CASCADE,
    content_type      varchar(50) NOT NULL,
    content           bytea NOT NULL
);
