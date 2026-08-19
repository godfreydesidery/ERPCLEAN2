/// Extra root certificates the till trusts, on top of the public CAs.
///
/// WHY THIS EXISTS. The production box terminates TLS with Caddy's *internal*
/// CA (`tls internal` in `infra/prod/Caddyfile`) because it has no domain yet,
/// so its certificate chains to a private root that no client trusts. Dio
/// validates certificates by default, so a stock build cannot open a connection
/// at all — the Setup screen just reports "Could not reach the ERP at this
/// host." while the server is perfectly healthy.
///
/// Trusting this specific root fixes that WITHOUT weakening anything: hostname
/// and chain validation stay fully on, and the till accepts exactly one extra
/// issuer — the one we operate. That is the difference between this and
/// `POS_ALLOW_INSECURE_TLS` (see `insecure_tls.dart`), which turns validation
/// off entirely and must never ship on a real till.
///
/// A root certificate is a public key. There is nothing secret here, and it is
/// safe to commit and to ship inside the installer.
///
/// ONE TILL, MANY SERVERS. This list is deliberately not the only source of
/// trust, because a till is not tied to this one box:
///   * servers with a real domain + Let's Encrypt (the end state) validate
///     against the built-in public roots — nothing here is involved;
///   * dev and QA are plain HTTP, so TLS never enters the picture;
///   * every client install produced from `dist/` runs its OWN Caddy and mints
///     its OWN internal root, which we cannot know at build time.
/// So the compiled-in roots below are a convenience for servers we operate, and
/// the drop-in files in `trusted_ca_io.dart` (`erp-ca.pem`, the `certs/`
/// directory, `POS_ERP_CA_FILE`) are the general mechanism. Pointing one till at
/// several private-CA servers is a matter of copying a file per server.
///
/// WHEN THIS GOES STALE. Caddy generates its internal root once and keeps it in
/// the `erp-prod-caddy-data` volume; the leaf certificate rotates every 12 hours
/// underneath it, which does not affect us. But if that volume is ever lost or
/// recreated, Caddy mints a NEW root and every till pinned to the old one stops
/// connecting. The fix needs no rebuild — drop the new `root.crt` into `certs/`.
/// A real domain + Let's Encrypt on prod retires this entry for good.
library;

/// Caddy's internal root from the SAM Electronix production box
/// (`ec2-16-192-117-45.eu-north-1.compute.amazonaws.com`).
///
/// Fetched with `docker exec erp-prod-caddy cat
/// /data/caddy/pki/authorities/local/root.crt` on 2026-08-02.
///
///   subject/issuer : CN=Caddy Local Authority - 2026 ECC Root (self-signed)
///   valid          : 2026-06-21 .. 2036-04-29
///   SHA-256        : 5A:C3:5A:62:4F:C5:B7:72:EC:4E:B4:1E:33:DF:4B:DA:
///                    69:AF:FC:39:7F:3E:B0:92:7D:66:F5:09:49:60:C6:6C
///
/// Verified against the live host: with this root, OpenSSL reports
/// `Verify return code: 0 (ok)` including the hostname check; without it,
/// `unable to get local issuer certificate`.
const String kProdCaddyRootCa = '''
-----BEGIN CERTIFICATE-----
MIIBozCCAUqgAwIBAgIRAOTNY2i3QCxSCE7GWb4QvhIwCgYIKoZIzj0EAwIwMDEu
MCwGA1UEAxMlQ2FkZHkgTG9jYWwgQXV0aG9yaXR5IC0gMjAyNiBFQ0MgUm9vdDAe
Fw0yNjA2MjExMTUxMThaFw0zNjA0MjkxMTUxMThaMDAxLjAsBgNVBAMTJUNhZGR5
IExvY2FsIEF1dGhvcml0eSAtIDIwMjYgRUNDIFJvb3QwWTATBgcqhkjOPQIBBggq
hkjOPQMBBwNCAATjF10xfqSt/SEmlOEsivc7Tzl/CRqprq9GMnr5TWpMF9CpRrYo
VbaQtwARMB1kXUdci8JnJjWr3fP2wFFB5gV5o0UwQzAOBgNVHQ8BAf8EBAMCAQYw
EgYDVR0TAQH/BAgwBgEB/wIBATAdBgNVHQ4EFgQUFJAdrhPP2gFutaeY2KIrCONV
6oIwCgYIKoZIzj0EAwIDRwAwRAIgOMAAX7dDKEubHed83hsuIQwSkME6Mjk/LT8G
lm4fw0ACIHP+4TnRf7rds37nVYUtvcXHivxAIkUB1o5Ag7GdALjn
-----END CERTIFICATE-----
''';

/// Every root compiled into this build, in addition to the public CAs.
const List<String> kErpTrustedRootCas = <String>[kProdCaddyRootCa];
