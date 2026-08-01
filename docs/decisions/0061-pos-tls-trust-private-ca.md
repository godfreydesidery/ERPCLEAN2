# ADR-0061: The POS trusts the ERP's own certificate authority, instead of disabling TLS validation

- **Status:** Accepted (2026-08-02) — owner-decided ("bundle the Caddy root CA into the POS"), taken
  after the SAM Electronix till could not reach production.
- **Deciders:** Owner + Solutions Architect
- **Effort:** S. **Migration:** none — POS client only, no schema, no API, no permission code.
- **Related:** ADR-0044 (POS supermarket readiness), the `POS_ALLOW_INSECURE_TLS` build flag added
  2026-07-15 in PR #269 (the stopgap this supersedes for field use), `infra/prod/Caddyfile`
  (`tls internal`), `dist/` (the client distribution package, whose installs each mint their own CA).

## Context

The production box was unreachable from the POS while being entirely healthy. Measured against the
live host on 2026-08-02:

| Probe | Result |
|---|---|
| `GET /api/v1/health`, certificate validation **off** | `200 {"status":"UP","service":"erp-api"}` |
| `GET /api/v1/health`, certificate validation **on** | connection fails — `unable to get local issuer certificate` |

The API, the route and the payload were all correct. The till never got that far, because Dio
validates certificates by default and production terminates TLS with Caddy's **internal** CA
(`tls internal`, chosen in `infra/prod/Caddyfile` because the box has no domain — only an EC2
hostname, and public CAs will not issue for one). The leaf chains to `CN=Caddy Local Authority -
2026 ECC Root`, which no client trusts. The Setup screen reported the generic *"Could not reach the
ERP at this host."*, which reads as a network or server fault and sent debugging in the wrong
direction.

There was no way around it. Port 8081 is closed off-box by the security group (correctly); port 80
`301`-redirects into the same TLS wall; the certificate's only SAN is the EC2 hostname, so the bare
IP fails too. Exactly one URL works, and only for a client that skips validation.

Three further constraints shaped the decision:

1. **A till is not tied to one server.** Dev is `localhost:8081` and QA is `http://16.170.11.41` —
   both plain HTTP, no TLS at all. Production is private-CA HTTPS. And every client install produced
   from `dist/` runs its own Caddy and generates its **own** internal root, which cannot be known
   when the POS is built. Any fix that assumes a single server is wrong on arrival.
2. **This is a cash register.** It carries a bearer token and posts finalised sales. Its transport is
   not a place to accept a downgrade.
3. **The end state is known.** A real domain plus Let's Encrypt on the prod Caddy removes the whole
   problem — the fix must not become an obstacle to that, or quietly outlive its need.

## Decision

**The POS trusts the ERP's own root CA in addition to the public ones. Certificate validation stays
fully on.**

`applyErpTls(Dio)` is now the single place TLS policy is applied, and all three Dio instances (the
authenticated `ApiClient`, `TokenManager`'s bare refresh client, and the Setup screen's probe) go
through it. It builds one shared `SecurityContext(withTrustedRoots: true)` — public roots **kept** —
and adds our roots to it, from three additive sources:

| Source | Purpose |
|---|---|
| `kErpTrustedRootCas` (compiled in) | Servers we operate. Currently one: the prod Caddy root, valid to 2036-04-29. |
| `certs/*.pem` beside the executable | **Several servers, one till.** One file per server, no rebuild. |
| `erp-ca.pem` beside the executable, or `POS_ERP_CA_FILE` (multi-path) | Single drop-in / scripted installs. |

Sources are read synchronously at startup — the Dio instances are built during startup, so an async
lookup would race the first request. A root that is unparseable, unreadable or already present is
skipped rather than fatal: a till must start.

### Why not the alternatives

- **Ship with `POS_ALLOW_INSECURE_TLS=true`.** Fastest, and already built. Rejected: it disables
  certificate validation *entirely*, so any party on the path can present any certificate and read or
  alter a session that carries a bearer token and posts sales. The flag stays for bench testing, and
  `applyErpTls` deliberately applies it **last** so it still overrides — but it must not reach a till.
- **Install the root in the Windows certificate store.** Rejected: it is manual per machine, invisible
  when it silently fails, and Dart ships its own root bundle rather than reading the OS store, so it
  may not even take effect.
- **Wait for a real domain + Let's Encrypt.** The right end state, but it needs a domain the client
  does not have yet, and the till is blocked now. This decision is forward-compatible with it —
  `withTrustedRoots: true` means a publicly-trusted certificate validates through the built-in roots
  and the bundled entry simply stops being consulted.
- **Certificate pinning (leaf or SPKI).** Rejected: Caddy rotates the leaf every 12 hours. Pinning the
  root is the stable layer.

## Consequences

- The till reaches production with chain, expiry **and** hostname validation intact. Verified live:
  with the root, `Verify return code: 0 (ok)`; without it, `unable to get local issuer certificate`.
  `test/trusted_ca_test.dart` asserts all three legs against the real host — a stock client is
  rejected, the pinned client gets `200 UP`, and the **same box reached by IP is still refused**,
  which is what distinguishes this from a bypass.
- One till can serve several private-CA servers by copying a file per server into `certs/`.
- **The bundled root is not a secret** (it is a public key) and is safe to commit and ship.
- **Failure mode to know:** Caddy keeps its internal root in the `erp-prod-caddy-data` volume. If that
  volume is ever lost or recreated, Caddy mints a new root and every till pinned to the old one stops
  connecting. Recovery needs no rebuild — drop the new `root.crt` into `certs/`. This is one more
  reason the durable-volume rule matters.
- Trust is broadened by exactly one issuer that we operate. The residual risk is that whoever holds
  the prod Caddy CA key can mint certificates the till accepts — acceptable, since that party already
  operates the server the till talks to.

## Follow-ups

1. **Real domain + Let's Encrypt on prod** (retires this ADR's compiled-in entry, and the browser
   warning with it). Two-line change to `infra/prod/Caddyfile` once DNS points at the box.
2. **Have the `dist/` installer emit its Caddy root** into a tills-facing folder, so a client install
   is POS-connectable without a manual `docker exec`.
3. **Improve the Setup screen's diagnosis** — distinguish "certificate not trusted" from "host
   unreachable". The single generic message is what made this cost a debugging session.
