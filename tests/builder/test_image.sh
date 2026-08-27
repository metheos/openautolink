#!/usr/bin/env bash
set -euo pipefail

IMAGE="${1:?usage: test_image.sh IMAGE}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/work" "$TMP/key1" "$TMP/key2"

docker run --rm --network none \
    --user "$(id -u):$(id -g)" \
    --entrypoint bash \
    --volume "$TMP:/test" \
    "$IMAGE" -lc '
        set -e
        for n in 1 2; do
            keytool -genkeypair -keystore "/test/key$n/key.jks" -storepass testpass \
                -keypass testpass -alias upload -keyalg RSA -keysize 2048 -validity 30 \
                -dname "CN=Image Test $n, O=OpenAutoLink, C=US" -noprompt >/dev/null 2>&1
        done
        cd /test/work
        printf "signed content\n" > payload.txt
        jar --create --file unsigned.jar payload.txt
        cp unsigned.jar signed.jar
        jarsigner -keystore /test/key1/key.jks -storepass testpass -keypass testpass \
            signed.jar upload >/dev/null
        cp signed.jar partial.jar
        printf "unsigned addition\n" > extra.txt
        jar --update --file partial.jar extra.txt
    '

run_verify() {
    local jar="$1" key="$2"
    docker run --rm --network none --read-only \
        --entrypoint bash \
        --tmpfs /tmp:rw,noexec,nosuid,size=16m \
        --volume "$ROOT/builder/build-in-container.sh:/test/build-in-container.sh:ro" \
        --volume "$jar:/test/input.jar:ro" \
        --volume "$key:/test/key.jks:ro" \
        "$IMAGE" -lc '
            set -e
            export OAL_LIB_ONLY=1
            source /test/build-in-container.sh
            report="$(jarsigner -verify /test/input.jar 2>&1)" || exit 10
            case "$report" in *"jar verified."*) ;; *) exit 11 ;; esac
            case "$report" in *unsigned*) exit 12 ;; esac
            expected="$(keytool -list -v -keystore /test/key.jks -storepass testpass -alias upload | sed -n "s/^[[:space:]]*SHA256: //p" | head -1)"
            actual="$(keytool -printcert -jarfile /test/input.jar | sed -n "s/^[[:space:]]*SHA256: //p" | head -1)"
            [ -n "$actual" ] && [ "$actual" = "$expected" ]
        '
}

run_verify "$TMP/work/signed.jar" "$TMP/key1/key.jks"
if run_verify "$TMP/work/unsigned.jar" "$TMP/key1/key.jks"; then
    printf 'FAIL: unsigned archive accepted\n' >&2
    exit 1
fi
if run_verify "$TMP/work/partial.jar" "$TMP/key1/key.jks"; then
    printf 'FAIL: partially signed archive accepted\n' >&2
    exit 1
fi
if run_verify "$TMP/work/signed.jar" "$TMP/key2/key.jks"; then
    printf 'FAIL: wrong signing key accepted\n' >&2
    exit 1
fi

docker run --rm --entrypoint bash "$IMAGE" -lc '
    set -e
    command -v git curl jq java keytool jarsigner xmllint flock
    test -f "$BUNDLETOOL_JAR"
    test -f "$ANDROID_NDK_HOME/source.properties"
    test -x /opt/android-sdk-linux/cmake/3.22.1/bin/cmake
    test -x /opt/android-sdk-linux/build-tools/34.0.0/aapt2
' >/dev/null

printf 'PASS: builder image smoke and adversarial signature tests\n'
