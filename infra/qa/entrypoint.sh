#!/usr/bin/env bash
#
# Single-container QA entrypoint for ERPCLEAN2. Initialises PostgreSQL on first
# run, then hands off to supervisord which runs Postgres + the Spring Boot jar.

set -euo pipefail

PGBIN=/usr/lib/postgresql/15/bin
DATA_DIR=/var/lib/postgresql/data
MARKER="$DATA_DIR/.erpclean2-initialized"

DB_NAME="${DB_NAME:-erp}"
DB_USER="${DB_USER:-erp}"
DB_PASSWORD="${DB_PASSWORD:-erp}"

# Mounted volume comes up owned by root — hand the data dir to postgres.
mkdir -p "$DATA_DIR"
chown -R postgres:postgres "$DATA_DIR"
chmod 700 "$DATA_DIR"

if [ ! -f "$MARKER" ]; then
  echo "[entrypoint] First run — initialising PostgreSQL cluster in $DATA_DIR"
  su postgres -c "$PGBIN/initdb -D $DATA_DIR --auth-local=trust --auth-host=scram-sha-256 --encoding=UTF8" >/dev/null

  # Listen only on localhost (the API is in the same container).
  echo "listen_addresses = '127.0.0.1'" >> "$DATA_DIR/postgresql.conf"
  echo "host all all 127.0.0.1/32 scram-sha-256" >> "$DATA_DIR/pg_hba.conf"

  echo "[entrypoint] Starting postgres for bootstrap"
  su postgres -c "$PGBIN/pg_ctl -D $DATA_DIR -o '-c listen_addresses=127.0.0.1' -w start" >/dev/null

  echo "[entrypoint] Creating role '$DB_USER' and database '$DB_NAME'"
  su postgres -c "psql -v ON_ERROR_STOP=1 --username postgres" >/dev/null <<SQL
CREATE USER ${DB_USER} WITH PASSWORD '${DB_PASSWORD}';
CREATE DATABASE ${DB_NAME} OWNER ${DB_USER};
SQL

  echo "[entrypoint] Stopping bootstrap postgres"
  su postgres -c "$PGBIN/pg_ctl -D $DATA_DIR -w stop" >/dev/null

  touch "$MARKER"
  echo "[entrypoint] Init complete"
fi

# Wrapper supervisor calls for the API: wait for Postgres on 5432, then exec the jar.
cat >/opt/erpclean2/start-api.sh <<'SH'
#!/usr/bin/env bash
set -euo pipefail
for _ in $(seq 1 60); do
  if (echo > /dev/tcp/127.0.0.1/5432) >/dev/null 2>&1; then break; fi
  sleep 1
done
export ERP_DB_URL="jdbc:postgresql://127.0.0.1:5432/${DB_NAME:-erp}"
export ERP_DB_USER="${DB_USER:-erp}"
export ERP_DB_PASSWORD="${DB_PASSWORD:-erp}"
exec java \
  -XX:MaxRAMPercentage=70.0 \
  -Djava.security.egd=file:/dev/./urandom \
  -jar /opt/erpclean2/app.jar
SH
chmod +x /opt/erpclean2/start-api.sh

echo "[entrypoint] Handing off to supervisord"
exec /usr/bin/supervisord -c /etc/supervisor/supervisord.conf
