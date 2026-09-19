#!/usr/bin/env bash
# Smoke test for a running auth-server: discovery, JWKS, client credentials,
# introspection, revocation and a negative credential check. Tokens are held in
# shell variables only and never printed or written to disk.
set -euo pipefail

BASE_URL="${1:-${AUTH_SERVER_URL:-http://localhost:8083}}"
CLIENT_ID="${AUTH_MACHINE_CLIENT_ID:-auth-machine}"
CLIENT_SECRET="${AUTH_MACHINE_CLIENT_SECRET:-auth-machine-secret}"
SCOPE="${AUTH_SMOKE_SCOPE:-weather:read}"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

pass() {
  printf 'ok: %s\n' "$1"
}

command -v curl >/dev/null 2>&1 || fail "curl is required"
command -v jq >/dev/null 2>&1 || fail "jq is required"

discovery="$(curl -fsS --max-time 10 "$BASE_URL/.well-known/oauth-authorization-server")" \
  || fail "discovery endpoint is not reachable at $BASE_URL"
issuer="$(printf '%s' "$discovery" | jq -r '.issuer // empty')"
[ "$issuer" = "$BASE_URL" ] || fail "issuer mismatch: discovery says '$issuer', expected '$BASE_URL'"
pass "discovery issuer matches ($issuer)"

jwks="$(curl -fsS --max-time 10 "$BASE_URL/oauth2/jwks")" || fail "jwks endpoint is not reachable"
[ "$(printf '%s' "$jwks" | jq -r '.keys | length')" = "1" ] || fail "jwks must publish exactly one key"
[ "$(printf '%s' "$jwks" | jq -r '.keys[0].kty')" = "RSA" ] || fail "jwks key is not RSA"
[ "$(printf '%s' "$jwks" | jq -r '.keys[0].d // empty')" = "" ] || fail "jwks must not expose private key material"
pass "jwks publishes one RSA public key"

token_response="$(curl -fsS --max-time 10 -XPOST "$BASE_URL/oauth2/token" \
  --user "$CLIENT_ID:$CLIENT_SECRET" \
  -d grant_type=client_credentials -d "scope=$SCOPE")" \
  || fail "client credentials token request failed"
access_token="$(printf '%s' "$token_response" | jq -r '.access_token // empty')"
[ -n "$access_token" ] || fail "token response has no access_token"
pass "client credentials token issued for scope $SCOPE"

introspection="$(curl -fsS --max-time 10 -XPOST "$BASE_URL/oauth2/introspect" \
  --user "$CLIENT_ID:$CLIENT_SECRET" -d "token=$access_token")" \
  || fail "introspection request failed"
[ "$(printf '%s' "$introspection" | jq -r '.active')" = "true" ] || fail "introspection reports the token as inactive"
pass "introspection reports an active token"

curl -fsS --max-time 10 -o /dev/null -XPOST "$BASE_URL/oauth2/revoke" \
  --user "$CLIENT_ID:$CLIENT_SECRET" -d "token=$access_token" \
  || fail "revocation request failed"
after_revocation="$(curl -fsS --max-time 10 -XPOST "$BASE_URL/oauth2/introspect" \
  --user "$CLIENT_ID:$CLIENT_SECRET" -d "token=$access_token")" \
  || fail "introspection after revocation failed"
[ "$(printf '%s' "$after_revocation" | jq -r '.active')" = "false" ] || fail "revoked token still reports active"
pass "revoked token is inactive"

status="$(curl -s --max-time 10 -o /dev/null -w '%{http_code}' -XPOST "$BASE_URL/oauth2/token" \
  --user "$CLIENT_ID:definitely-wrong-secret" -d grant_type=client_credentials)" \
  || fail "negative credential check did not complete"
[ "$status" = "401" ] || fail "wrong client secret must return 401, got $status"
pass "wrong client secret is rejected with 401"

printf 'smoke test passed for %s\n' "$BASE_URL"
