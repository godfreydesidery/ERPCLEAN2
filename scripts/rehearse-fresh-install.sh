#!/usr/bin/env bash
#
# B1 — fresh-install rehearsal harness.
#
# Runs the SHIPPED installer (dist/release/orbixerp-<v>-<arch>/install.sh) against a
# genuinely EMPTY database, on a throwaway compose stack, and then checks what the
# first boot actually produced. This is the path customer #2 takes and the path no
# environment has ever executed at 1.8.x.
#
#   ./rehearse-fresh-install.sh up <bundle-dir>   install + first boot
#   ./rehearse-fresh-install.sh verify            the assertions (PASS/FAIL, exit 1 on any FAIL)
#   ./rehearse-fresh-install.sh reboot            stop + start, then the boot-2 assertions
#   ./rehearse-fresh-install.sh down              destroy ONLY what `up` created
#
# Run it inside WSL / a real Linux shell, NOT Git Bash:
#   wsl -e bash /mnt/d/My_Works/ERP/ERPCLEAN2/scripts/rehearse-fresh-install.sh up <bundle>
#
# The install directory MUST be native ext4 (/opt/...), never /mnt/c or /mnt/d: install.sh
# chowns the JWT private key to uid 10001 and chown is a no-op on a DrvFs mount, so the
# application would fail to read its own signing key for a reason unrelated to the release.
#
# ---------------------------------------------------------------------------
# SAFETY. This machine also hosts a restored copy of the live customer's database
# (container erp-rehearsal-db, volume erp-rehearsal-data) and the local dev stack
# (project erp, volume erp-db-data). Neither may be touched.
#
#   * every docker call is pinned to the literal project name below
#   * teardown removes the volume BY LITERAL NAME
#   * `docker compose down -v` appears nowhere in this file
#   * `docker volume prune` / `docker system prune` appear nowhere in this file
#   * the stack name is refused if it collides with a real one
# ---------------------------------------------------------------------------

set -euo pipefail

# --- the throwaway namespace. Hard-coded on purpose: a typo must not reach anything real.
STACK="orbix-b1r"
HTTP_PORT="18080"
DIR="/opt/orbix-b1r"

API="${STACK}-api"
DB="${STACK}-db"
VOLUME="${STACK}-db-data"
NETWORK="${STACK}_default"

# --- guard 1: never adopt a namespace that belongs to something real.
case "$STACK" in
  orbixerp|erp|erpclean2|erp-rehearsal|erp-rehearsal-db|orbix|orbix-qa-local)
    echo "REFUSING: stack name '$STACK' collides with a real stack." >&2; exit 2 ;;
esac
case "$VOLUME" in
  erp-db-data|erp-rehearsal-data|erpclean2-dev-db-data|erpclean2_erp-db-data|orbixerp-db-data)
    echo "REFUSING: volume name '$VOLUME' collides with a durable volume." >&2; exit 2 ;;
esac

RED=$'\033[31m'; GRN=$'\033[32m'; YEL=$'\033[33m'; BLD=$'\033[1m'; OFF=$'\033[0m'
FAILS=0
pass() { printf '%sPASS%s  %s\n' "$GRN" "$OFF" "$*"; }
fail() { printf '%sFAIL%s  %s\n' "$RED" "$OFF" "$*"; FAILS=$((FAILS+1)); }
note() { printf '%snote%s  %s\n' "$YEL" "$OFF" "$*"; }
head1() { printf '\n%s== %s ==%s\n' "$BLD" "$*" "$OFF"; }

# assert <label> <expected> <actual>
assert_eq() {
  local label="$1" want="$2" got="$3"
  if [ "$want" = "$got" ]; then pass "$label  (= $got)"; else fail "$label  expected '$want', got '$got'"; fi
}
assert_gt0() {
  local label="$1" got="$2"
  if [ -n "$got" ] && [ "$got" -gt 0 ] 2>/dev/null; then pass "$label  (= $got)"
  else fail "$label  expected > 0, got '$got'"; fi
}
assert_nonempty() {
  local label="$1" got="$2"
  if [ -n "$got" ] && [ "$got" != "null" ]; then pass "$label  (= $got)"; else fail "$label  is empty/null"; fi
}

psql1() { docker exec "$DB" psql -U erp -d erp -tAc "$1" 2>/dev/null | tr -d '\r'; }

# ---------------------------------------------------------------------------
cmd_up() {
  local bundle="${1:-}"
  [ -n "$bundle" ] || { echo "usage: $0 up <bundle-dir>" >&2; exit 2; }
  [ -f "$bundle/install.sh" ] || { echo "no install.sh in $bundle" >&2; exit 2; }

  # --- guard 2: refuse to reuse a half-built stack unless it is cleaned first.
  if docker volume inspect "$VOLUME" >/dev/null 2>&1; then
    echo "REFUSING: volume $VOLUME already exists. Run '$0 down' first (it is a throwaway)." >&2
    exit 2
  fi
  if docker ps -a --format '{{.Names}}' | grep -qx "$API\|$DB"; then
    echo "REFUSING: container $API/$DB already exists. Run '$0 down' first." >&2
    exit 2
  fi

  case "$DIR" in /mnt/*) echo "REFUSING: $DIR is a Windows mount; chown is a no-op there." >&2; exit 2 ;; esac

  head1 "Staging the bundle into $DIR"
  rm -rf "$DIR"; mkdir -p "$DIR"
  cp -a "$bundle"/. "$DIR"/
  chmod +x "$DIR/install.sh" "$DIR/orbixerp.sh"

  # THE ONLY THREE DEVIATIONS FROM A CUSTOMER'S DEFAULTS. They are applied to the
  # TEMPLATE, not to .env, so create_env's real `cp template .env` + `chmod 600` path
  # (install.sh:269-270) still executes exactly as it does for a customer.
  sed -i "s/^ERP_STACK_NAME=.*/ERP_STACK_NAME=$STACK/"   "$DIR/.env.example"
  sed -i "s/^ERP_HTTP_PORT=.*/ERP_HTTP_PORT=$HTTP_PORT/" "$DIR/.env.example"
  sed -i "s/^ERP_BIND_ADDR=.*/ERP_BIND_ADDR=127.0.0.1/"  "$DIR/.env.example"
  grep -qE '^ERP_STACK_NAME=' "$DIR/.env.example" || echo "ERP_STACK_NAME=$STACK"   >> "$DIR/.env.example"
  grep -qE '^ERP_HTTP_PORT='  "$DIR/.env.example" || echo "ERP_HTTP_PORT=$HTTP_PORT" >> "$DIR/.env.example"
  grep -qE '^ERP_BIND_ADDR='  "$DIR/.env.example" || echo "ERP_BIND_ADDR=127.0.0.1"  >> "$DIR/.env.example"
  grep -E '^(ERP_STACK_NAME|ERP_HTTP_PORT|ERP_BIND_ADDR)=' "$DIR/.env.example" | sed 's/^/  deviation: /'

  head1 "Running the shipped installer (unattended)"
  cd "$DIR"
  local t0 t1
  t0=$(date +%s)
  set +e
  ./install.sh --defaults 2>&1 | tee "$DIR/install.log"
  local rc=${PIPESTATUS[0]}
  set -e
  t1=$(date +%s)
  echo
  echo "install.sh exit code: $rc"
  echo "install.sh wall clock: $((t1 - t0))s"
  return $rc
}

# ---------------------------------------------------------------------------
# SINGLE-SHOT. Sections G and H write to the database (a customer, a probe user), so a
# second `verify` against the same stack fails C5 (app_users) and G2 legitimately. Run it
# once, immediately after `up`; to re-run, go round `down` -> `up` -> `verify` again.
cmd_verify() {
  FAILS=0

  head1 "A · Flyway on an empty schema"
  local applied failed maxv repeat
  applied="$(psql1 "select count(*) from flyway_schema_history where success")"
  failed="$(psql1 "select count(*) from flyway_schema_history where not success")"
  maxv="$(psql1 "select max(version::int) from flyway_schema_history where version is not null")"
  repeat="$(psql1 "select script||'|'||success from flyway_schema_history where version is null")"
  assert_eq  "A1 highest versioned migration is V103" "103" "$maxv"
  assert_eq  "A2 no failed migrations"                "0"   "$failed"
  assert_gt0 "A3 successful migration rows"                 "$applied"
  # psql renders boolean as 'true'/'false' here, not 't'/'f'.
  assert_eq  "A4 R__seed_permissions applied"  "R__seed_permissions.sql|true" "$repeat"
  echo "     (versioned applied: $(psql1 "select count(*) from flyway_schema_history where version is not null"))"

  head1 "B · The application, under the prod profile"
  local hstate
  hstate="$(docker inspect --format '{{.State.Health.Status}}' "$API" 2>/dev/null || echo missing)"
  assert_eq "B1 container health" "healthy" "$hstate"
  local ready
  ready="$(docker exec "$API" wget -qO- http://127.0.0.1:9090/actuator/health/readiness 2>/dev/null || echo '')"
  case "$ready" in *'"status":"UP"'*) pass "B2 actuator readiness UP" ;; *) fail "B2 actuator readiness: '$ready'" ;; esac
  local pub
  pub="$(curl -fsS "http://127.0.0.1:$HTTP_PORT/api/v1/health" 2>/dev/null || echo '')"
  case "$pub" in *'"UP"'*) pass "B3 public /api/v1/health UP" ;; *) fail "B3 public health: '$pub'" ;; esac
  local prof starts
  # The prod profile logs as JSON, so the quotes around "prod" arrive backslash-escaped.
  prof="$(docker logs "$API" 2>&1 | grep -cE 'profile is active: .{0,2}prod' || true)"
  starts="$(docker logs "$API" 2>&1 | grep -cE 'Started ErpApplication' || true)"
  assert_gt0 "B4 prod profile activated (application-prod.yml parsed)" "$prof"
  assert_eq  "B5 exactly one successful start (no crash-loop)" "1" "$starts"
  local errs
  errs="$(docker logs "$API" 2>&1 | grep -cE 'APPLICATION FAILED TO START|Caused by:' || true)"
  [ "$errs" = "0" ] && pass "B6 no startup exceptions in the log" || note "B6 log holds $errs exception lines — inspect"

  head1 "C · BootstrapRunner"
  assert_gt0 "C1 'Bootstrap complete' in the log" \
    "$(docker logs "$API" 2>&1 | grep -c 'Bootstrap complete' || true)"
  assert_eq "C2 organisations"  "1" "$(psql1 'select count(*) from organisations')"
  assert_eq "C3 companies"      "1" "$(psql1 'select count(*) from companies')"
  assert_eq "C4 branches"       "1" "$(psql1 'select count(*) from branches')"
  assert_eq "C5 app_users"      "1" "$(psql1 'select count(*) from app_users')"
  assert_nonempty "C6 organisation alias set by provisioning" "$(psql1 'select alias from organisations limit 1')"
  echo "     base currency: $(psql1 'select base_currency from companies limit 1')"
  echo "     org/company/branch: $(psql1 "select o.name||' / '||c.name||' / '||b.name from organisations o join companies c on c.organisation_id=o.id join branches b on b.company_id=c.id limit 1")"
  assert_eq "C7 rootadmin is_root"       "t" "$(psql1 "select is_root from app_users where username='rootadmin'")"
  assert_nonempty "C8 rootadmin organisation_id" "$(psql1 "select organisation_id from app_users where username='rootadmin'")"
  assert_eq "C9 user_branch rows"        "1" "$(psql1 'select count(*) from user_branch')"
  # psql prints a bare boolean column as t/f, but a boolean concatenated into text as true/false.
  assert_eq "C10 default branch flagged" "t" "$(psql1 'select is_default from user_branch limit 1')"
  # Version-dependent, so reported rather than asserted. P4-3 (post-1.8.3) added the ORG_ADMIN
  # grant to TenantProvisioningService; on 1.8.3 the bootstrap admin holds NO role at all and
  # works purely through the is_root short-circuit.
  local ur uc
  ur="$(psql1 'select count(*) from user_role')"
  uc="$(psql1 'select count(*) from user_company')"
  echo "     user_role rows (ORG_ADMIN grant; 0 = pre-P4-3 build): $ur"
  echo "     user_company rows (0 on boot 1 = runner-order inversion): $uc"

  head1 "D · TenancyReconciler"
  docker logs "$API" 2>&1 | grep -iE 'Tenancy reconcil|reconcil' | sed 's/^/     /' | tail -8 || true
  local recerr
  recerr="$(docker logs "$API" 2>&1 | grep -icE 'reconcil.*(error|failed|exception)' || true)"
  assert_eq "D1 no reconciler errors" "0" "$recerr"
  echo "     roles with organisation_id: $(psql1 'select count(*) from roles where organisation_id is not null')"
  echo "     roles global:               $(psql1 'select count(*) from roles where organisation_id is null')"

  head1 "E · The 23 company-scoped defaults"
  local t
  for t in chart_of_accounts gl_configs tax_rates units_of_measure fiscal_years fiscal_periods \
           cash_bank_accounts document_branding stock_locations leave_types notification_types \
           pipeline_stages dimensions sales_settings company_currency code_sequence \
           roles permissions petty_cash_funds; do
    assert_gt0 "E $t" "$(psql1 "select count(*) from $t")"
  done
  head1 "E' · What a fresh tenant does NOT get (readiness B3/B4/B5)"
  echo "     price_lists                : $(psql1 'select count(*) from price_lists')"
  echo "     pos_tills                  : $(psql1 'select count(*) from pos_tills')"
  echo "     customers (CASH_WALK_IN)   : $(psql1 "select count(*) from customers where customer_kind='CASH_WALK_IN'")"
  # Not seeded on 1.8.3 (13e721cb is post-bundle); created lazily by the first party of each kind.
  echo "     party_code_sequence        : $(psql1 'select count(*) from party_code_sequence')"

  head1 "F · Login"
  local pw tok body
  pw="$(grep -E '^ERP_BOOTSTRAP_ADMIN_PASSWORD=' "$DIR/.env" | tail -1 | cut -d= -f2- | tr -d '\r')"
  body="$(curl -fsS -X POST "http://127.0.0.1:$HTTP_PORT/api/v1/auth/login" \
            -H 'Content-Type: application/json' \
            -d "{\"username\":\"rootadmin\",\"password\":\"$pw\"}" 2>/dev/null || echo '')"
  tok="$(printf '%s' "$body" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')"
  assert_nonempty "F1 access token issued" "${tok:0:24}"
  case "$body" in *'"isRoot":true'*) pass "F2 isRoot true" ;; *) fail "F2 isRoot not true" ;; esac
  local abr
  abr="$(printf '%s' "$body" | sed -n 's/.*"activeBranchUid":"\([^"]*\)".*/\1/p')"
  assert_nonempty "F3 activeBranchUid (default branch landed)" "$abr"

  head1 "G · One real business action"
  if [ -n "$tok" ]; then
    local orguid coid cur
    cur="$(curl -fsS -H "Authorization: Bearer $tok" "http://127.0.0.1:$HTTP_PORT/api/v1/organisations/current" 2>/dev/null || echo '')"
    orguid="$(printf '%s' "$cur" | sed -n 's/.*"uid":"\([^"]*\)".*/\1/p' | head -1)"
    assert_nonempty "G1 /organisations/current returns a uid" "$orguid"
    coid="$(psql1 'select id from companies limit 1')"
    # INDIVIDUAL, not BUSINESS: a BUSINESS customer is correctly refused without a TIN.
    local created
    created="$(curl -sS -w '\n%{http_code}' -X POST "http://127.0.0.1:$HTTP_PORT/api/v1/customers" \
      -H "Authorization: Bearer $tok" -H 'Content-Type: application/json' \
      -d "{\"companyId\":\"$coid\",\"partyType\":\"INDIVIDUAL\",\"displayName\":\"B1 Rehearsal Customer\",\"customerKind\":\"CREDIT_ACCOUNT\"}" 2>/dev/null || echo 'ERR')"
    local code; code="$(printf '%s' "$created" | tail -1)"
    case "$code" in 200|201) pass "G2 POST /api/v1/customers -> $code" ;; *) fail "G2 POST /api/v1/customers -> $code : $(printf '%s' "$created" | head -c 300)" ;; esac
    echo "     customers now: $(psql1 'select count(*) from customers')  code: $(psql1 'select code from customers order by id desc limit 1')"
    echo "     party_code_sequence after the create (lazy): $(psql1 'select count(*) from party_code_sequence')"
    local coa
    coa="$(curl -sS -H "Authorization: Bearer $tok" "http://127.0.0.1:$HTTP_PORT/api/v1/gl/accounts?companyId=$coid" 2>/dev/null | head -c 600 || echo '')"
    case "$coa" in *'accountCode'*) pass "G3 chart of accounts readable via /api/v1/gl/accounts" ;; *) fail "G3 gl/accounts: $(printf '%s' "$coa" | head -c 200)" ;; esac
    local spa
    spa="$(curl -fsS "http://127.0.0.1:$HTTP_PORT/" 2>/dev/null | head -c 120 || echo '')"
    case "$spa" in *"<!doctype html>"*|*"<!DOCTYPE html>"*) pass "G4 web client served from the same port" ;; *) fail "G4 SPA not served: '$spa'" ;; esac

    # ---- H · DAY ONE: the first administrator makes the first real user. ----
    # This is the path that actually fails on a fresh install, and the reason this gate exists.
    head1 "H · Day-one administration (create user -> assign branch)"
    local ures uuid uname want
    want="b1.probe$$"
    ures="$(curl -sS -X POST "http://127.0.0.1:$HTTP_PORT/api/v1/users" \
      -H "Authorization: Bearer $tok" -H 'Content-Type: application/json' \
      -d "{\"username\":\"$want\",\"displayName\":\"B1 Probe\",\"password\":\"ProbePass12345\"}" 2>/dev/null || echo '')"
    uuid="$(printf '%s' "$ures" | sed -n 's/.*"uid":"\([^"]*\)".*/\1/p' | head -1)"
    uname="$(printf '%s' "$ures" | sed -n 's/.*"username":"\([^"]*\)".*/\1/p' | head -1)"
    assert_nonempty "H1 first real user created" "$uuid"
    if [ "$uname" != "$want" ]; then
      note "H2 username was rewritten: asked for '$want', stored as '$uname' (tenant alias suffix)"
    else
      pass "H2 username stored as requested"
    fi
    echo "     user_company after create-user: $(psql1 'select count(*) from user_company')"
    local buid bres bcode
    buid="$(psql1 'select uid from branches limit 1')"
    bres="$(curl -sS -w '\n%{http_code}' -X POST "http://127.0.0.1:$HTTP_PORT/api/v1/user-branches" \
      -H "Authorization: Bearer $tok" -H 'Content-Type: application/json' \
      -d "{\"userUid\":\"$uuid\",\"branchUid\":\"$buid\",\"isDefault\":true}" 2>/dev/null || echo 'ERR')"
    bcode="$(printf '%s' "$bres" | tail -1)"
    case "$bcode" in
      200|201) pass "H3 branch assigned to the new user -> $bcode" ;;
      *) fail "H3 branch assignment REFUSED -> $bcode : $(printf '%s' "$bres" | head -1 | head -c 200)"
         note "     the first admin is root, and UserServiceImpl skips company membership for root creators" ;;
    esac
  else
    fail "G skipped — no token"
  fi

  head1 "Result"
  if [ "$FAILS" -eq 0 ]; then printf '%sALL CHECKS PASSED%s\n' "$GRN" "$OFF"; else printf '%s%d CHECK(S) FAILED%s\n' "$RED" "$FAILS" "$OFF"; fi
  return $([ "$FAILS" -eq 0 ] && echo 0 || echo 1)
}

# ---------------------------------------------------------------------------
# Boot twice. BootstrapRunner carries no @Order, so it runs LAST on boot 1 —
# after TenancyReconciler and UserCompanyBackfill have already run against an
# empty database. Whatever those two would have done for this tenant therefore
# happens for the first time on boot 2.
cmd_reboot() {
  head1 "Boot 1 state (before restart)"
  local uc_before fw_before
  uc_before="$(psql1 'select count(*) from user_company')"
  fw_before="$(psql1 'select count(*) from flyway_schema_history')"
  echo "     user_company            : $uc_before"
  echo "     flyway_schema_history   : $fw_before"

  head1 "Restarting"
  cd "$DIR"
  local t0 t1; t0=$(date +%s)
  ./orbixerp.sh restart 2>&1 | tail -20
  t1=$(date +%s)
  echo "restart wall clock: $((t1 - t0))s"

  head1 "Boot 2 state"
  local uc_after fw_after starts
  uc_after="$(psql1 'select count(*) from user_company')"
  fw_after="$(psql1 'select count(*) from flyway_schema_history')"
  starts="$(docker logs "$API" 2>&1 | grep -cE 'Started ErpApplication' || true)"
  echo "     user_company            : $uc_after"
  echo "     flyway_schema_history   : $fw_after"
  assert_eq "R1 no migrations re-applied" "$fw_before" "$fw_after"
  assert_eq "R2 exactly one start on the new container (no crash-loop)" "1" "$starts"
  local hs; hs="$(docker inspect --format '{{.State.Health.Status}}' "$API")"
  assert_eq "R3 healthy after restart" "healthy" "$hs"
  docker logs "$API" 2>&1 | grep -iE 'Bootstrap|reconcil|backfill' | sed 's/^/     /' | tail -12 || true
  if [ "$uc_before" = "0" ] && [ "$uc_after" != "0" ]; then
    note "RUNNER-ORDER INVERSION CONFIRMED: user_company was empty after boot 1 and populated after boot 2."
  fi
  return $([ "$FAILS" -eq 0 ] && echo 0 || echo 1)
}

# ---------------------------------------------------------------------------
# Destroys ONLY the orbix-b1r stack. No -v. No prune. Literal names only.
cmd_down() {
  head1 "Tearing down $STACK (and nothing else)"
  if [ -d "$DIR" ]; then
    ( cd "$DIR" && docker compose -p "$STACK" -f docker-compose.yml -f docker-compose.db-docker.yml down ) || true
  fi
  docker rm -f "$API" "$DB" 2>/dev/null || true
  docker volume rm "$VOLUME" 2>/dev/null || true
  docker network rm "$NETWORK" 2>/dev/null || true
  rm -rf "$DIR"

  head1 "Post-teardown safety check — the durable volumes must still exist"
  local v
  for v in erp-db-data erp-rehearsal-data; do
    if docker volume inspect "$v" >/dev/null 2>&1; then pass "$v still present"; else fail "$v IS GONE"; fi
  done
  for v in erp-rehearsal-db erp-api erp-db; do
    if docker ps --format '{{.Names}}' | grep -qx "$v"; then pass "$v still running"; else note "$v not running (check whether it was before)"; fi
  done
  return $([ "$FAILS" -eq 0 ] && echo 0 || echo 1)
}

case "${1:-}" in
  up)     shift; cmd_up "$@" ;;
  verify) cmd_verify ;;
  reboot) cmd_reboot ;;
  down)   cmd_down ;;
  *) echo "usage: $0 {up <bundle-dir>|verify|reboot|down}" >&2; exit 2 ;;
esac
