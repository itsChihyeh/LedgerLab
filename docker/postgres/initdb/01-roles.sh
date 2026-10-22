#!/bin/sh
# Creates the database roles (ADR-0014) when the PostgreSQL data directory is first initialised.
# Run by the postgres image's entrypoint from /docker-entrypoint-initdb.d, both by Docker Compose
# and by the Testcontainers setup in the tests, so every environment gets the same roles.
#
#   migrator  owns the database and every schema and table; Flyway runs as this role.
#   app       the application; gets only the grants each migration writes for it.
#   readonly  investigation and the read-only MCP; SELECT on views only.
#   simulator owns the sim_* schemas (fake Stripe, simulated bank, chaos relay, ground truth),
#             which the main application roles cannot see.
#
# Passwords come from the environment; there are no defaults, so a missing one fails loudly.
set -eu
: "${LEDGERLAB_MIGRATOR_PASSWORD:?}" "${LEDGERLAB_APP_PASSWORD:?}" "${LEDGERLAB_READONLY_PASSWORD:?}" "${LEDGERLAB_SIMULATOR_PASSWORD:?}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v db="$POSTGRES_DB" \
  -v migrator_pw="$LEDGERLAB_MIGRATOR_PASSWORD" \
  -v app_pw="$LEDGERLAB_APP_PASSWORD" \
  -v readonly_pw="$LEDGERLAB_READONLY_PASSWORD" \
  -v simulator_pw="$LEDGERLAB_SIMULATOR_PASSWORD" <<'SQL'
CREATE ROLE migrator LOGIN PASSWORD :'migrator_pw';
CREATE ROLE app LOGIN PASSWORD :'app_pw';
CREATE ROLE readonly LOGIN PASSWORD :'readonly_pw';
CREATE ROLE simulator LOGIN PASSWORD :'simulator_pw';

ALTER DATABASE :"db" OWNER TO migrator;
-- No implicit CONNECT or TEMP for every role; each role gets exactly what it needs.
REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"db" TO app, readonly, simulator;
GRANT CONNECT, CREATE, TEMPORARY ON DATABASE :"db" TO migrator;
-- Nothing lives in public; make sure nobody can create anything there either.
REVOKE ALL ON SCHEMA public FROM PUBLIC;

CREATE SCHEMA sim_processor AUTHORIZATION simulator;
CREATE SCHEMA sim_bank AUTHORIZATION simulator;
CREATE SCHEMA sim_chaos AUTHORIZATION simulator;
CREATE SCHEMA sim_truth AUTHORIZATION simulator;
SQL
