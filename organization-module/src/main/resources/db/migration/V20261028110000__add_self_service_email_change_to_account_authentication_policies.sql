-- TD-FUT-052: lets an Organization switch off the End-User's own email change. A consuming
-- application that keys its records on the email may want to be the only place it can change.
-- On by default: turning the feature on for existing Organizations is the shipped behaviour.
ALTER TABLE account_authentication_policies
    ADD COLUMN self_service_email_change_enabled boolean NOT NULL DEFAULT true;
