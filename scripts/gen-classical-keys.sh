#!/usr/bin/env bash
# Generates a minimal CA + server + client certificate chain for the "before"
# (classical) configuration: ECDSA P-256 signatures. The TLS 1.3 key-exchange
# group (X25519 by default in current JDKs) is negotiated at handshake time,
# not baked into the keystore, so it needs no special keytool handling here.
set -euo pipefail
cd "$(dirname "$0")/.."

KEYS_DIR="keys/classical"
PASS="changeit"
VALIDITY_DAYS=30

mkdir -p "$KEYS_DIR"

if [ -f "$KEYS_DIR/server.jks" ] && [ -f "$KEYS_DIR/client.jks" ] && [ -f "$KEYS_DIR/truststore.jks" ]; then
  echo "[gen-classical-keys] keystores already exist in $KEYS_DIR, skipping (delete the dir to regenerate)"
  exit 0
fi

rm -f "$KEYS_DIR"/*.jks "$KEYS_DIR"/*.crt "$KEYS_DIR"/*.csr

echo "[gen-classical-keys] generating CA (ECDSA P-256)"
keytool -genkeypair -alias ca -keyalg EC -groupname secp256r1 -sigalg SHA256withECDSA \
  -dname "CN=Latticejack Test CA,O=Latticejack,C=US" -validity "$VALIDITY_DAYS" \
  -keystore "$KEYS_DIR/ca.jks" -storepass "$PASS" -keypass "$PASS" -ext bc:c

keytool -exportcert -alias ca -keystore "$KEYS_DIR/ca.jks" -storepass "$PASS" \
  -rfc -file "$KEYS_DIR/ca.crt"

gen_leaf() {
  local name="$1" cn="$2"
  echo "[gen-classical-keys] generating $name (ECDSA P-256), signing with CA"
  keytool -genkeypair -alias "$name" -keyalg EC -groupname secp256r1 -sigalg SHA256withECDSA \
    -dname "CN=$cn,O=Latticejack,C=US" -validity "$VALIDITY_DAYS" \
    -keystore "$KEYS_DIR/$name.jks" -storepass "$PASS" -keypass "$PASS"

  keytool -certreq -alias "$name" -keystore "$KEYS_DIR/$name.jks" -storepass "$PASS" \
    -file "$KEYS_DIR/$name.csr"

  keytool -gencert -alias ca -keystore "$KEYS_DIR/ca.jks" -storepass "$PASS" \
    -infile "$KEYS_DIR/$name.csr" -outfile "$KEYS_DIR/$name.crt" \
    -validity "$VALIDITY_DAYS" -rfc

  keytool -importcert -alias ca -keystore "$KEYS_DIR/$name.jks" -storepass "$PASS" \
    -file "$KEYS_DIR/ca.crt" -noprompt
  keytool -importcert -alias "$name" -keystore "$KEYS_DIR/$name.jks" -storepass "$PASS" \
    -file "$KEYS_DIR/$name.crt" -noprompt
}

gen_leaf server localhost
gen_leaf client latticejack-client

echo "[gen-classical-keys] building shared truststore (CA cert only)"
keytool -importcert -alias ca -keystore "$KEYS_DIR/truststore.jks" -storepass "$PASS" \
  -file "$KEYS_DIR/ca.crt" -noprompt

echo "[gen-classical-keys] done -> $KEYS_DIR"
