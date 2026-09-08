#!/usr/bin/env bash
set -euo pipefail

BASE="http://localhost:4577/devstoreaccount1-keyvault"
# Array so curl receives each header as a single argument (unquoted $AUTH word-splits
# "Bearer x" into separate URL args).
AUTH=(-H "Authorization: Bearer x" -H "Content-Type: application/json")

b64url() { base64 | tr '+/' '-_' | tr -d '=\n'; }
digest_sha256() { openssl dgst -sha256 -binary | b64url; }

echo "==> Running native crypto smoke checks against $BASE..."

# The emulator resolves version-less crypto paths to the latest key version, so we
# address operations by key name — the returned kid is a *.vault.azure.net URL that
# does not route back to the emulator.

# 1. RSA-OAEP-256
curl -sf "${AUTH[@]}" -X POST "$BASE/keys/smoke-rsa/create?api-version=7.4" \
  -d '{"kty":"RSA","key_size":2048}' >/dev/null
pt=$(echo -n "hello" | b64url)
ct=$(curl -sf "${AUTH[@]}" -X POST "$BASE/keys/smoke-rsa/encrypt?api-version=7.4" \
  -d "{\"alg\":\"RSA-OAEP-256\",\"value\":\"$pt\"}" | jq -r '.value')
dec=$(curl -sf "${AUTH[@]}" -X POST "$BASE/keys/smoke-rsa/decrypt?api-version=7.4" \
  -d "{\"alg\":\"RSA-OAEP-256\",\"value\":\"$ct\"}" | jq -r '.value')
[ "$dec" = "$pt" ] || { echo "FAIL: RSA-OAEP-256 round-trip"; exit 1; }
echo "  ✓ RSA-OAEP-256 encrypt/decrypt passed"

# 2. RS256
dg=$(echo -n "smoke-msg" | digest_sha256)
sig=$(curl -sf "${AUTH[@]}" -X POST "$BASE/keys/smoke-rsa/sign?api-version=7.4" \
  -d "{\"alg\":\"RS256\",\"value\":\"$dg\"}" | jq -r '.value')
ok=$(curl -sf "${AUTH[@]}" -X POST "$BASE/keys/smoke-rsa/verify?api-version=7.4" \
  -d "{\"alg\":\"RS256\",\"digest\":\"$dg\",\"value\":\"$sig\"}" | jq -r '.value')
[ "$ok" = "true" ] || { echo "FAIL: RS256 verify"; exit 1; }
echo "  ✓ RS256 sign/verify passed"

# 3. PS256
sig=$(curl -sf "${AUTH[@]}" -X POST "$BASE/keys/smoke-rsa/sign?api-version=7.4" \
  -d "{\"alg\":\"PS256\",\"value\":\"$dg\"}" | jq -r '.value')
ok=$(curl -sf "${AUTH[@]}" -X POST "$BASE/keys/smoke-rsa/verify?api-version=7.4" \
  -d "{\"alg\":\"PS256\",\"digest\":\"$dg\",\"value\":\"$sig\"}" | jq -r '.value')
[ "$ok" = "true" ] || { echo "FAIL: PS256 verify"; exit 1; }
echo "  ✓ PS256 sign/verify passed"

# 4. ES256
curl -sf "${AUTH[@]}" -X POST "$BASE/keys/smoke-ec/create?api-version=7.4" \
  -d '{"kty":"EC","crv":"P-256"}' >/dev/null
sig=$(curl -sf "${AUTH[@]}" -X POST "$BASE/keys/smoke-ec/sign?api-version=7.4" \
  -d "{\"alg\":\"ES256\",\"value\":\"$dg\"}" | jq -r '.value')
ok=$(curl -sf "${AUTH[@]}" -X POST "$BASE/keys/smoke-ec/verify?api-version=7.4" \
  -d "{\"alg\":\"ES256\",\"digest\":\"$dg\",\"value\":\"$sig\"}" | jq -r '.value')
[ "$ok" = "true" ] || { echo "FAIL: ES256 verify"; exit 1; }
echo "  ✓ ES256 sign/verify passed"

# 5. AES-GCM
curl -sf "${AUTH[@]}" -X POST "$BASE/keys/smoke-oct/create?api-version=7.4" \
  -d '{"kty":"oct","key_size":256}' >/dev/null
resp=$(curl -sf "${AUTH[@]}" -X POST "$BASE/keys/smoke-oct/encrypt?api-version=7.4" \
  -d "{\"alg\":\"A256GCM\",\"value\":\"$pt\"}")
ct=$(echo "$resp" | jq -r '.value'); iv=$(echo "$resp" | jq -r '.iv'); tag=$(echo "$resp" | jq -r '.tag')
dec=$(curl -sf "${AUTH[@]}" -X POST "$BASE/keys/smoke-oct/decrypt?api-version=7.4" \
  -d "{\"alg\":\"A256GCM\",\"value\":\"$ct\",\"iv\":\"$iv\",\"tag\":\"$tag\"}" | jq -r '.value')
[ "$dec" = "$pt" ] || { echo "FAIL: AES-GCM round-trip"; exit 1; }
echo "  ✓ AES-GCM (A256GCM) encrypt/decrypt passed"

echo "ALL 5 NATIVE CRYPTO SMOKE CHECKS PASSED"
