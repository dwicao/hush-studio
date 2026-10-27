#!/usr/bin/env bash
# Generates a release keystore from the public AOSP testkey for CI test builds.
# The AOSP testkey is PUBLIC and INSECURE. Never use it for Google Play or
# production: APKs signed with it cannot update Play-signed installs and must
# be distributed as clearly-marked test builds only.
#
# Usage: ./scripts/make-testkey-keystore.sh [output-dir]
# Writes: testkey.jks + keystore.properties (both gitignored) into output-dir.
set -euo pipefail

OUT_DIR="${1:-$PWD}"
ALIAS="android"
PASSWORD="android"
MIRRORS=(
  "https://raw.githubusercontent.com/aosp-mirror/platform_build/master/target/product/security"
  "https://raw.githubusercontent.com/LineageOS/android_build/master/target/product/security"
)

mkdir -p "$OUT_DIR"
cd "$OUT_DIR"

downloaded=""
for base in "${MIRRORS[@]}"; do
  echo "Trying $base ..."
  if curl -fsSL --retry 2 -o testkey.x509.pem "$base/testkey.x509.pem" \
  && curl -fsSL --retry 2 -o testkey.pk8 "$base/testkey.pk8"; then
    downloaded="$base"
    break
  fi
done
if [ -z "$downloaded" ]; then
  echo "ERROR: could not download AOSP testkey from any mirror." >&2
  exit 1
fi
echo "Downloaded testkey from $downloaded"

# DER PKCS#8 -> PEM key, then bundle cert+key into PKCS#12, then convert to JKS
# (the app's signing config reads a JKS by default).
openssl pkcs8 -inform DER -in testkey.pk8 -out testkey.key -nocrypt
openssl pkcs12 -export -in testkey.x509.pem -inkey testkey.key \
  -out testkey.p12 -name "$ALIAS" -password "pass:$PASSWORD"
keytool -importkeystore -noprompt \
  -deststorepass "$PASSWORD" -destkeypass "$PASSWORD" -destkeystore testkey.jks \
  -srckeystore testkey.p12 -srcstoretype PKCS12 -srcstorepass "$PASSWORD" -alias "$ALIAS"
rm -f testkey.key testkey.p12 testkey.pk8

cat > keystore.properties <<EOF
storeFile=$OUT_DIR/testkey.jks
storePassword=$PASSWORD
keyAlias=$ALIAS
keyPassword=$PASSWORD
EOF

echo "Wrote $OUT_DIR/testkey.jks and $OUT_DIR/keystore.properties (test-signed, NOT for Play)."
