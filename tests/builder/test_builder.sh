#!/usr/bin/env bash
# shellcheck disable=SC1090,SC2016,SC2034
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
BOOTSTRAP="$ROOT/build-aab.sh"
CONTAINER_SCRIPT="$ROOT/builder/build-in-container.sh"
DOCKERFILE="$ROOT/builder/Dockerfile"
WORKFLOW="$ROOT/.github/workflows/build-builder-image.yml"
GRADLE_WRAPPER="$ROOT/gradle/wrapper/gradle-wrapper.properties"
VERIFICATION_METADATA="$ROOT/gradle/verification-metadata.xml"

fail() {
    printf 'FAIL: %s\n' "$*" >&2
    exit 1
}

assert_eq() {
    local expected="$1" actual="$2" label="$3"
    [ "$expected" = "$actual" ] || fail "$label: expected '$expected', got '$actual'"
}

assert_file_contains() {
    local file="$1" pattern="$2" label="$3"
    grep -Eq -- "$pattern" "$file" || fail "$label: '$pattern' not found in $file"
}

[ -f "$BOOTSTRAP" ] || fail "missing public bootstrap"
[ -f "$CONTAINER_SCRIPT" ] || fail "missing container build script"
[ -f "$DOCKERFILE" ] || fail "missing builder Dockerfile"
[ -f "$WORKFLOW" ] || fail "missing GHCR publish workflow"

bash -n "$BOOTSTRAP"
bash -n "$CONTAINER_SCRIPT"

OAL_LIB_ONLY=1 source "$BOOTSTRAP"
validate_app_id com.example.openautolink || fail "valid application ID rejected"
validate_app_id io.github.user_name.oal || fail "valid underscored application ID rejected"
if validate_app_id com.openautolink.app; then fail "upstream application ID must be rejected"; fi
if validate_app_id Com.Example.App; then fail "uppercase application ID must be rejected"; fi
if validate_app_id com.example.bad-id; then fail "hyphenated application ID must be rejected"; fi
if validate_app_id singleword; then fail "single-segment application ID must be rejected"; fi
validate_country US || fail "valid country rejected"
if validate_country USA; then fail "three-letter country accepted"; fi
if validate_country us; then fail "lowercase country accepted"; fi
validate_version_code 0 || fail "new-app version baseline rejected"
validate_version_code 485 || fail "existing version baseline rejected"
if validate_version_code -1; then fail "negative version baseline accepted"; fi
if validate_version_code nope; then fail "non-numeric version baseline accepted"; fi

CONFIG_TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$CONFIG_TEST_DIR"' EXIT
OAL_BUILDER_STATE_DIR="$CONFIG_TEST_DIR"
mkdir -p "$OAL_BUILDER_STATE_DIR/config"
OAL_NONINTERACTIVE=1
OAL_APP_ID=com.example.openautolink
OAL_SIGNER_NAME='Builder Test'
OAL_COUNTRY=US
OAL_START_VERSION_CODE=485
configure
assert_eq com.example.openautolink "$(read_saved app-id)" "saved application ID"
assert_eq 'Builder Test' "$(read_saved signer-name)" "saved signer"
assert_eq US "$(read_saved country)" "saved country"
assert_eq 485 "$(read_saved starting-version-code)" "saved version baseline"
unset OAL_NONINTERACTIVE OAL_APP_ID OAL_SIGNER_NAME OAL_COUNTRY OAL_START_VERSION_CODE

IMPORT_SOURCE="$CONFIG_TEST_DIR/existing-upload.jks"
printf 'test-keystore' > "$IMPORT_SOURCE"
OAL_NONINTERACTIVE=1
OAL_EXISTING_KEYSTORE="$IMPORT_SOURCE"
OAL_STORE_PASSWORD='store-pass-123'
OAL_KEY_PASSWORD='key-pass-456'
OAL_KEY_ALIAS='existing'
prepare_key_import
assert_eq test-keystore "$(cat "$OAL_BUILDER_STATE_DIR/secrets/upload-key.jks")" "imported keystore"
assert_eq store-pass-123 "$(cat "$OAL_BUILDER_STATE_DIR/secrets/keystore-password")" "stored keystore password"
assert_eq key-pass-456 "$(cat "$OAL_BUILDER_STATE_DIR/secrets/key-password")" "stored key password"
assert_eq existing "$(read_saved key-alias)" "saved key alias"
unset OAL_NONINTERACTIVE OAL_EXISTING_KEYSTORE OAL_STORE_PASSWORD OAL_KEY_PASSWORD OAL_KEY_ALIAS

OAL_LIB_ONLY=1 source "$CONTAINER_SCRIPT"
assert_eq 1 "$(next_version_code 0)" "initial version"
assert_eq 486 "$(next_version_code 485)" "incremented version"
assert_eq 0.1.486 "$(version_name_for 486)" "version name"
assert_eq 'Jane Doe' "$(normalize_signer_name '  Jane   Doe  ')" "signer normalization"
assert_eq 'Doe\, Jane' "$(escape_dname_value 'Doe, Jane')" "DName comma escaping"
assert_eq 'R\+D\=Lab' "$(escape_dname_value 'R+D=Lab')" "DName metacharacter escaping"

assert_file_contains "$BOOTSTRAP" 'OAL_BUILDER_STATE_DIR' "state directory override"
assert_file_contains "$BOOTSTRAP" 'OAL_BUILDER_IMAGE' "image override"
assert_file_contains "$BOOTSTRAP" '/dev/tty' "pipe-safe interactive UI"
assert_file_contains "$BOOTSTRAP" 'Existing upload keystore' "existing-key import UI"
assert_file_contains "$BOOTSTRAP" 'docker pull' "builder image update"
assert_file_contains "$BOOTSTRAP" '--tmpfs /workspace/secrets' "unsigned build cannot read signing secrets"
assert_file_contains "$BOOTSTRAP" '--network none' "signing runs without network"
assert_file_contains "$BOOTSTRAP" '/workspace/secrets:ro' "signing key mounted read-only"
assert_file_contains "$CONTAINER_SCRIPT" 'git .*fetch' "source update"
assert_file_contains "$CONTAINER_SCRIPT" 'git .*clean -ffdx' "persistent checkout cleanup"
assert_file_contains "$CONTAINER_SCRIPT" 'expected_aasdk' "native dependency source binding"
assert_file_contains "$CONTAINER_SCRIPT" 'for abi in arm64-v8a x86_64' "both native dependency ABIs"
assert_file_contains "$CONTAINER_SCRIPT" 'jarsigner.*-verify' "AAB signature verification"
if grep -Eq 'jarsigner[[:space:]]+-verify[[:space:]]+-strict' "$CONTAINER_SCRIPT"; then
    fail "personal self-signed upload keys must not be rejected by jarsigner strict mode"
fi
assert_file_contains "$CONTAINER_SCRIPT" 'BUNDLETOOL_JAR.*validate' "Android bundle validation"
assert_file_contains "$CONTAINER_SCRIPT" 'BUNDLETOOL_JAR.*dump manifest' "AAB manifest extraction"
assert_file_contains "$CONTAINER_SCRIPT" 'xmllint' "AAB manifest parsing"
assert_file_contains "$CONTAINER_SCRIPT" 'keytool.*-printcert.*-jarfile' "AAB signer extraction"
assert_file_contains "$CONTAINER_SCRIPT" 'AAB signing certificate mismatch' "AAB signer comparison"
assert_file_contains "$BOOTSTRAP" 'flock' "same-state concurrency lock"
assert_file_contains "$CONTAINER_SCRIPT" 'sha256sum' "artifact checksum"
assert_file_contains "$CONTAINER_SCRIPT" 'cd "\$OUTPUT_DIR"' "portable checksum path"
assert_file_contains "$CONTAINER_SCRIPT" 'build-unsigned' "separate unsigned build action"
assert_file_contains "$CONTAINER_SCRIPT" 'sign' "separate signing action"
assert_file_contains "$DOCKERFILE" 'bundletool-all-1\.18\.3\.jar' "pinned bundletool"
assert_file_contains "$DOCKERFILE" 'a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29' "bundletool checksum"
assert_file_contains "$DOCKERFILE" 'build-tools;34\.0\.0' "AGP-required Build Tools"
assert_file_contains "$DOCKERFILE" 'ndk;28\.2\.13676358' "pinned NDK"
assert_file_contains "$DOCKERFILE" 'cmake;3\.22\.1' "pinned CMake"
assert_file_contains "$WORKFLOW" 'packages: write' "GHCR permission"
assert_file_contains "$WORKFLOW" 'ghcr\.io' "GHCR destination"
assert_file_contains "$GRADLE_WRAPPER" 'distributionSha256Sum=f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6' "Gradle distribution checksum"
assert_file_contains "$VERIFICATION_METADATA" '<verify-metadata>true</verify-metadata>' "Gradle metadata verification"
assert_file_contains "$VERIFICATION_METADATA" '<sha256 value=' "Gradle dependency checksums"
assert_file_contains "$CONTAINER_SCRIPT" '--dependency-verification=strict' "strict Gradle dependency verification"
assert_file_contains "$ROOT/app/build.gradle.kts" 'oalBuilderUnsigned' "unsigned builder Gradle mode"

if grep -Eq 'COPY .*secrets|ADD .*secrets' "$DOCKERFILE"; then
    fail "Dockerfile must not copy signing secrets"
fi

printf 'PASS: builder unit and contract tests\n'
