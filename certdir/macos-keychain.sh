#!/usr/bin/env bash
#
# Import the pgjdbc test client certificate into a throwaway macOS keychain so
# the Apple "KeychainStore" provider exposes it to the JVM.
#
# No system or login-keychain password is required: a dedicated keychain is
# created with a random password we choose, the certificate is built on the fly
# from the checked-in test key and certificate with another random password, and
# the private-key ACL is pre-authorised non-interactively. The user login
# keychain is never modified; only the keychain search list is touched, and
# "teardown" restores it.
#
#   ./certdir/macos-keychain.sh setup
#   PGJDBC_KEYCHAIN_IMPORTED=true ./gradlew :postgresql:test --tests '*KeychainCertTest'
#   ./certdir/macos-keychain.sh teardown
#
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
KC="${PGJDBC_TEST_KEYCHAIN:-${TMPDIR:-/tmp}/pgjdbc-test.keychain}"
SAVED="$KC.search-list"

case "${1:-}" in
  setup)
    kcpw=$(openssl rand -hex 16)
    p12pw=$(openssl rand -hex 16)
    p12="${KC%.keychain}.p12"
    openssl pkcs12 -export -inkey "$DIR/goodclient.key" -in "$DIR/goodclient.crt" \
      -passout "pass:$p12pw" -out "$p12"

    security delete-keychain "$KC" 2>/dev/null || true
    security create-keychain -p "$kcpw" "$KC"
    security unlock-keychain -p "$kcpw" "$KC"
    security set-keychain-settings "$KC"
    security import "$p12" -k "$KC" -P "$p12pw" -A -f pkcs12
    # Pre-authorise non-interactive access to the imported private key.
    security set-key-partition-list -S apple-tool:,apple:,unsigned: -k "$kcpw" "$KC" >/dev/null
    rm -f "$p12"

    # Remember the current search list, then prepend the test keychain so the
    # Apple KeychainStore provider can see the imported certificate.
    security list-keychains -d user | sed -e 's/^[[:space:]]*//' -e 's/"//g' > "$SAVED"
    # shellcheck disable=SC2046
    security list-keychains -d user -s "$KC" $(cat "$SAVED")
    echo "Keychain ready: $KC"
    ;;
  teardown)
    if [ -f "$SAVED" ]; then
      # shellcheck disable=SC2046
      security list-keychains -d user -s $(cat "$SAVED")
      rm -f "$SAVED"
    fi
    security delete-keychain "$KC" 2>/dev/null || true
    echo "Keychain removed, search list restored"
    ;;
  *)
    echo "usage: $0 {setup|teardown}" >&2
    exit 2
    ;;
esac
