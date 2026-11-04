-- Run once as the Cloud SQL admin, before the first start. Flyway then runs as the owner role and the
-- application connects as the restricted role, which can never UPDATE or DELETE the audit trail.
CREATE ROLE autopilot_owner LOGIN PASSWORD :'owner_password';
CREATE ROLE autopilot_app   LOGIN PASSWORD :'app_password';
CREATE DATABASE autopilot OWNER autopilot_owner;
\connect autopilot
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT ALL ON SCHEMA public TO autopilot_owner;
GRANT USAGE ON SCHEMA public TO autopilot_app;
