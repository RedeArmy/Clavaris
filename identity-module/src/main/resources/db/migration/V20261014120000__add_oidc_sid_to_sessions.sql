-- OIDC Core 12.2: an ID token issued by a refresh must repeat the original's `sid`, which Spring
-- Authorization Server derives from the browser's HttpSession when the code is exchanged (and
-- compares against an ID token hint at logout). Nothing kept it, so a refreshed ID token carried
-- this Session's own id instead and an RP that sent its latest ID token as the logout hint got a
-- 400. Nullable: a Session opened before this column has no sid to repeat, and its refreshed ID
-- tokens keep reporting the Session id exactly as they did.
ALTER TABLE sessions ADD COLUMN oidc_sid varchar(128);
