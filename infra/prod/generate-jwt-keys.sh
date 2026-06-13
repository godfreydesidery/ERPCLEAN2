#!/bin/sh
# generate-jwt-keys.sh — generate an RSA 2048-bit key pair for ERPCLEAN2 JWT signing.
#
# Outputs two PEM files into infra/prod/jwt-keys/ (gitignored):
#   private.pem   — PKCS#8 private key (ERP_JWT_PRIVATE_KEY in .env)
#   public.pem    — SPKI public key    (ERP_JWT_PUBLIC_KEY  in .env)
#
# The pair is stable across container restarts (unlike dev-in-memory, which
# generates a new key per JVM start and invalidates all tokens).  Keep the private
# key secret — it can forge tokens for any user on this deployment.
#
# Run once per environment.  To rotate: generate a new pair, update .env paths,
# redeploy — all existing tokens will be invalidated on deploy (see docs/ops/jwt-keys.md).
#
# Requirements: openssl (present on all Linux/macOS hosts and in the alpine base image).
#
# Usage:
#   chmod +x infra/prod/generate-jwt-keys.sh
#   ./infra/prod/generate-jwt-keys.sh
#
# POSIX sh — no bashisms.

set -eu

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
KEY_DIR="${SCRIPT_DIR}/jwt-keys"

if [ ! -d "${KEY_DIR}" ]; then
  mkdir -p "${KEY_DIR}"
  echo "[jwt-keys] Created ${KEY_DIR}"
fi

PRIVATE_KEY="${KEY_DIR}/private.pem"
PUBLIC_KEY="${KEY_DIR}/public.pem"

if [ -f "${PRIVATE_KEY}" ] || [ -f "${PUBLIC_KEY}" ]; then
  echo "[jwt-keys] WARNING: key files already exist in ${KEY_DIR}."
  echo "[jwt-keys] Generating new keys will invalidate all existing tokens."
  echo "[jwt-keys] Proceeding in 5 seconds — Ctrl-C to abort."
  sleep 5
fi

echo "[jwt-keys] Generating RSA 2048-bit private key → ${PRIVATE_KEY}"
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
  -out "${PRIVATE_KEY}" 2>/dev/null

echo "[jwt-keys] Extracting public key → ${PUBLIC_KEY}"
openssl pkey -pubout -in "${PRIVATE_KEY}" -out "${PUBLIC_KEY}"

# Restrict permissions: private key readable only by owner.
chmod 600 "${PRIVATE_KEY}"
chmod 644 "${PUBLIC_KEY}"

echo "[jwt-keys] Key pair generated successfully."
echo ""
echo "  Private key : ${PRIVATE_KEY}"
echo "  Public key  : ${PUBLIC_KEY}"
echo ""
echo "Add to infra/prod/.env:"
echo "  ERP_JWT_PRIVATE_KEY=/run/secrets/jwt/private.pem"
echo "  ERP_JWT_PUBLIC_KEY=/run/secrets/jwt/public.pem"
echo ""
echo "The jwt-keys/ directory is bind-mounted read-only into the api container at"
echo "/run/secrets/jwt/ by the prod docker-compose.yml."
echo ""
echo "Keep private.pem SECRET.  Never commit it.  Back it up outside the repo."
