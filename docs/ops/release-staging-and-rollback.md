# Releasing to a customer: staging, verification, rollback

*Written 2026-08-15 (ADR-0062 P8-8), after two releases in one day took a live installation down.*

This is not a process document written in the abstract. Every rule below exists because something
specific went wrong, and each one names it.

## Why the existing gates are not enough

CI runs pre-merge. It is thorough — 1,377 unit tests, ArchUnit boundary gates, a two-organisation
isolation harness — and on **2026-08-15 it passed, twice, on releases that would not start**.

The reason is structural, not carelessness:

| Path | Why no test environment exercises it |
|---|---|
| `BootstrapRunner` | runs only when `organisations.count() == 0`. QA has an organisation, so it never executes there |
| `TenancyReconciler`'s stamping loop | QA's roles were already stamped, so the loop had no work and its trigger never fired |
| `application-prod.yml` | loaded by the `prod` profile only. Local uses `dev`, QA uses `qa` — **the customer is the first to parse it** |

Each is a path that only production takes. Tests cannot cover what the test environment cannot reach.

## The two failures, concretely

**1.8.0 — a migration.** V102 added a trigger enforcing that a tenant role may not reuse a global
role's code. In a `BEFORE UPDATE` trigger the table still holds the old row, so a role moving from
global to tenant-scoped matched *itself* and was refused. `TenancyReconciler` performs exactly that
update on every boot. The application crash-looped 12 times. Fixed by hand on the customer's database
to restore service, then shipped properly as V103.

**1.8.2 — one line of config, no migration.** A second top-level `spring:` key was appended to
`application-prod.yml`. Duplicate keys are a hard parse error; the application died before reading a
single property. Rolled back to 1.8.1.

The second is the more instructive: **"no migration" is not the same as "low risk"**. It was a
one-line change to a file that no test environment loads.

## The gates, in order

### 1 · Fast suite, plus the two-organisation harness

`mvn -B clean test` and `TwoOrganisationIsolationIT`. Necessary, not sufficient — see above.

### 2 · Boot under the `prod` profile

```bash
mvn -o -B spring-boot:run -Dspring-boot.run.profiles=prod
```

Under a minute, and it catches every `application-prod.yml` fault outright. `ApplicationYamlParsesTest`
now covers the parse specifically, but the boot covers what a parse test cannot: property resolution,
bean wiring, and anything else the profile changes.

### 3 · Boot against a restored copy of the customer's database

Not a fresh database — **theirs**. A fresh one has nothing for the reconciler to stamp, no legacy
roles, no unattributed rows, and none of the shape that has actually broken. Running SQL against the
copy is not enough either: 1.8.0's defect was in what the *application* does at startup, and the DDL
rehearsal passed.

### 4 · Boot the shipped artefact

Load the image from the bundle and run it, rather than the source tree:

```bash
docker load -i images/orbixerp-api-<version>-amd64.tar.gz
docker run --rm -e SPRING_PROFILES_ACTIVE=prod -e SPRING_DATASOURCE_URL=... orbixerp-api:<version>
```

Ninety seconds. It is the last thing between a bundle and a customer, and it tests the thing they
will actually run.

### 5 · QA, then a human

Deploy `develop` to QA, **verify the SHA on the box** (a deploy script has silently deployed the
wrong branch before and exited 0), then have someone open the product. Two of three releases today
shipped defects that every automated check passed; the thing that caught both was a person running
the software.

## Rollback

**Know which kind of release you are shipping** — it decides what rollback means.

| Release contains | Rollback |
|---|---|
| No migration | Reinstall the previous bundle. The schema is untouched, so the older application runs against it unchanged |
| A migration | Restore the safety backup `update` prints. **Reinstalling the old version will not work** — Flyway finds applied migrations it does not ship and refuses to start |
| A one-way migration (a dropped constraint) | Restore only. Note it in the release notes *before* shipping |

`./orbixerp.sh update` takes its own safety backup and stops if it fails. Note the filename it
prints; restoring it is the only way to undo a schema change.

```bash
ssh -tt -i <key> ubuntu@<host>      # -tt: restore reads /dev/tty and hangs without it
cd /opt/orbixerp && ./orbixerp.sh restore backups/<file>
```

## Blast radius

There is one production installation, so there is no canary and staged rollout is not available.
While that holds, the compensating controls are the gates above and the fact that a customer's
working day is the detection mechanism — which is why **releases go out when someone is available to
watch and to roll back**, not at the end of a session.

`main` is no longer a pre-tenancy rollback artefact: PR #312 merged the tenancy work into it on
2026-08-15. Rolling back past V99 now means restoring a database backup taken before 1.8.0.

## The rule under all of it

Every failure in this document is the same shape: **the thing that broke was the thing no environment
except the customer's had ever executed.** Before shipping, ask which paths this change touches that
QA cannot reach — bootstrap, the reconciler, the `prod` profile, a first-use path, a migration against
existing data — and go and run that one deliberately.
