# Security Sweep Runbook

**ADR:** 0038-production-hardening D-11
**Owner:** devops-engineer / security-engineer
**Last reviewed:** 2026-06-13

---

## Overview

This runbook covers two complementary dependency-CVE sweeps:

1. **Java backend** — OWASP dependency-check-maven (bound to the `security-scan` Maven profile)
2. **Angular frontend** — `npm audit`

Neither is run on every build. The Maven scan requires NVD database access (rate-limited without an
API key) and takes 3–10 minutes. The npm audit runs locally and in CI (see below).

---

## 1. Java Backend — OWASP Dependency Check

### One-time setup (optional but recommended)

Register for a free NVD API key at <https://nvd.nist.gov/developers/request-an-api-key>.
Without a key the tool still works but is throttled to 2 requests per 30 seconds; a full scan
takes significantly longer.

### Running the scan locally

```bash
cd backend

# Without NVD API key (slow — allow 10-20 min):
mvn -P security-scan dependency-check:aggregate

# With NVD API key (recommended, much faster):
mvn -P security-scan dependency-check:aggregate -DnvdApiKey=<your-key>

# With key from environment variable:
NVD_API_KEY=<your-key> mvn -P security-scan dependency-check:aggregate
```

Reports are written to `backend/target/dependency-check-report.html` (open in browser)
and `backend/target/dependency-check-report.json`.

The build fails if any dependency has a CVSS score >= 7 (High or Critical).

### Suppressing false positives

If a finding is a confirmed false positive or not exploitable in this context:

1. Add a `<suppress>` entry to `backend/dependency-check-suppressions.xml`.
2. Include the CVE identifier, the specific package URL, and a written rationale with reviewer name
   and date. **Never suppress by package name alone without a CVE.**
3. Commit the suppression file with the rationale in the commit message.

### Triage procedure for real findings

For each CVSS-7+ finding:

1. Check if the transitive path includes a version that is already patched (the scan sometimes
   resolves an old version from a transitive dep that is overridden at runtime — verify with
   `mvn dependency:tree`).
2. If a patched version exists and Dependabot has not already opened a PR, open one manually.
3. If no fix exists: assess exploitability in the ERP context, document the rationale, suppress
   with `<notes>`, and log in `docs/testing/ISSUES-REGISTER.md`.
4. Do **not** bump transitive dependencies manually without testing — let Dependabot handle it via
   the weekly PRs to `develop`.

---

## 2. Angular Frontend — npm audit

### Running locally

```bash
cd web

# Audit runtime dependencies only (omit devDependencies):
npm audit --omit=dev

# Full audit including devDependencies:
npm audit

# Fix automatically (non-breaking semver bumps only):
npm audit fix
```

### CI gate

`npm audit --audit-level=high` runs in the `build-and-test` job of `.github/workflows/web-ci.yml`
after `npm ci`. This mirrors the Maven CVSS-7 threshold (High = CVSS 7+). The build fails if any
high or critical vulnerability is found in production dependencies.

To suppress a false positive in CI, use `.nsprc` or an npm audit suppression file and document
the rationale.

---

## 3. CI-Automated Weekly Scan (FENCED)

A weekly CI job running `mvn -P security-scan dependency-check:aggregate` is **not yet enabled**
because it requires an `NVD_API_KEY` GitHub secret. The job skeleton below is ready to enable
once the secret is provisioned.

**To enable:**

1. Provision an NVD API key (see above).
2. Add it as a repository secret named `NVD_API_KEY` in GitHub Settings > Secrets and variables > Actions.
3. Create `.github/workflows/security-scan.yml` with the content below.

```yaml
name: Weekly Security Scan

on:
  schedule:
    - cron: '0 6 * * 1'   # Every Monday at 06:00 UTC
  workflow_dispatch:        # Allow manual trigger

defaults:
  run:
    working-directory: backend

jobs:
  dependency-check:
    name: OWASP dependency-check (Maven)
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: temurin
          cache: maven
      - name: Run OWASP dependency-check
        run: mvn -P security-scan dependency-check:aggregate -DnvdApiKey=${{ secrets.NVD_API_KEY }}
      - name: Upload report
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: dependency-check-report
          path: backend/target/dependency-check-report.html
          retention-days: 30

  npm-audit:
    name: npm audit (web)
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: web
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: '22'
          cache: npm
          cache-dependency-path: web/package-lock.json
      - run: npm ci
      - run: npm audit --omit=dev --audit-level=high
```

---

## 4. Spring Boot Version Note

`backend/pom.xml` parent is `3.3.5`. Bumping to the latest `3.3.x` patch release is the first
recommended action after this ADR merges:

```xml
<!-- in backend/pom.xml -->
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.X</version>  <!-- replace with latest 3.3.x from https://spring.io/projects/spring-boot -->
    <relativePath/>
</parent>
```

After bumping: `mvn clean verify` must pass. Dependabot will open these PRs automatically once
the `.github/dependabot.yml` is active.

---

## 5. CVE Triage Log

Record findings that required action or a suppress decision here.

| Date | CVE | Component | CVSS | Action | Reviewer |
|------|-----|-----------|------|--------|----------|
| — | — | — | — | No findings yet — first scan pending | — |
