-- TD-FUT-053: a password nobody chose. A social sign-up (TD-FUT-030) and a registration without a
-- password both store a random, never-shown credential so that "forgot password" keeps working.
-- The person does not know it, so it cannot be used as proof of who is asking; the self-service
-- pages need to tell it apart from a password the person picked.
ALTER TABLE password_credentials ADD COLUMN generated boolean NOT NULL DEFAULT false;

-- Existing rows: a credential that belongs to an Account with a linked social identity and has not
-- changed since the Account was created is the generated one from its sign-up. Anything else
-- stays "chosen" (the safe default: it is then asked for, as before).
UPDATE password_credentials pc
   SET generated = true
  FROM accounts a
 WHERE a.id = pc.account_id
   AND pc.updated_at <= a.created_at + interval '5 seconds'
   AND EXISTS (SELECT 1 FROM social_identities si WHERE si.account_id = a.id);
