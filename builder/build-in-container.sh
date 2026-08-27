#!/usr/bin/env bash
set -euo pipefail

WORKSPACE="${OAL_WORKSPACE:-/workspace}"
SOURCE_DIR="$WORKSPACE/source"
CONFIG_DIR="$WORKSPACE/config"
SECRETS_DIR="$WORKSPACE/secrets"
CACHE_DIR="$WORKSPACE/cache"
STAGING_DIR="$WORKSPACE/staging"
OUTPUT_DIR="$WORKSPACE/output"
KEYSTORE="$SECRETS_DIR/upload-key.jks"
PASSWORD_FILE="$SECRETS_DIR/keystore-password"
KEY_PASSWORD_FILE="$SECRETS_DIR/key-password"
VERSION_FILE="$CONFIG_DIR/build-state.properties"
KEY_ALIAS="${OAL_KEY_ALIAS:-upload}"
NATIVE_RELEASE_API="https://api.github.com/repos/mossyhub/openautolink/releases/tags/native-deps"

say() { printf '[builder] %s\n' "$*"; }
fail() { printf '[builder] ERROR: %s\n' "$*" >&2; exit 1; }

next_version_code() {
    local current="${1:-0}"
    [[ "$current" =~ ^[0-9]+$ ]] || return 1
    [ "$current" -lt 2100000000 ] || return 1
    printf '%s' "$((current + 1))"
}

version_name_for() {
    local code="$1"
    [[ "$code" =~ ^[0-9]+$ ]] || return 1
    printf '0.1.%s' "$code"
}

normalize_signer_name() {
    printf '%s' "${1:-}" | tr '\t\r\n' '   ' | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//; s/[[:space:]]+/ /g'
}

escape_dname_value() {
    local value="${1:-}" output="" char index
    for ((index = 0; index < ${#value}; index++)); do
        char="${value:index:1}"
        case "$char" in
            ','|'+'|'"'|\\|'<'|'>'|';'|'='|'#') output+="\\$char" ;;
            *) output+="$char" ;;
        esac
    done
    printf '%s' "$output"
}

property_value() {
    local file="$1" key="$2"
    [ -f "$file" ] || return 0
    sed -n -E "s/^${key}=//p" "$file" | tail -1
}

prepare_source() {
    mkdir -p "$SOURCE_DIR" "$CACHE_DIR/native" "$STAGING_DIR" "$OUTPUT_DIR" "$CONFIG_DIR" "$GRADLE_USER_HOME"

    if [ ! -d "$SOURCE_DIR/.git" ]; then
        say "Cloning $OAL_REPO_URL"
        rm -rf "$SOURCE_DIR"
        git clone --filter=blob:none "$OAL_REPO_URL" "$SOURCE_DIR"
    else
        # Remove stale generated, ignored, and untracked build inputs before fetching.
        git -C "$SOURCE_DIR" reset --hard
        git -C "$SOURCE_DIR" clean -ffdx
    fi

    git -C "$SOURCE_DIR" remote set-url origin "$OAL_REPO_URL"
    say "Fetching $OAL_REF"
    git -C "$SOURCE_DIR" fetch --prune origin "$OAL_REF"
    git -C "$SOURCE_DIR" checkout --detach FETCH_HEAD
    git -C "$SOURCE_DIR" reset --hard FETCH_HEAD
    git -C "$SOURCE_DIR" clean -ffdx
    git -C "$SOURCE_DIR" submodule sync -- external/opencardev-aasdk
    git -C "$SOURCE_DIR" submodule update --init --depth 1 external/opencardev-aasdk
}

validate_native_archive() {
    local archive="$1" abi="$2" entry
    while IFS= read -r entry; do
        case "$entry" in
            /*|../*|*/../*) fail "unsafe path in native archive: $entry" ;;
        esac
        case "$entry" in
            "third_party/openssl/$abi/"*|"third_party/aasdk/$abi/"*|third_party/boost/include/*) ;;
            *) fail "unexpected path in native archive: $entry" ;;
        esac
    done < <(tar tzf "$archive")
}

download_native_deps() {
    local metadata="$CACHE_DIR/native/release.json"
    curl --fail --silent --show-error --location "$NATIVE_RELEASE_API" -o "$metadata.tmp"
    mv "$metadata.tmp" "$metadata"

    local expected_aasdk release_body
    # Existing release metadata contains at least the first seven SHA characters;
    # newer native releases publish the full gitlink SHA.
    expected_aasdk="$(git -C "$SOURCE_DIR" rev-parse HEAD:external/opencardev-aasdk | cut -c1-7)"
    release_body="$(jq -er '.body' "$metadata")"
    case "$release_body" in
        *"$expected_aasdk"*) ;;
        *) fail "native-deps release does not match aasdk $expected_aasdk from the checked-out source" ;;
    esac

    local abi name url digest expected archive stamp
    for abi in arm64-v8a x86_64; do
        name="native-deps-${abi}.tar.gz"
        url="$(jq -er --arg name "$name" '.assets[] | select(.name == $name) | .browser_download_url' "$metadata")"
        digest="$(jq -er --arg name "$name" '.assets[] | select(.name == $name) | .digest' "$metadata")"
        expected="${digest#sha256:}"
        [ "$expected" != "$digest" ] || fail "GitHub did not provide a SHA-256 digest for $name"
        archive="$CACHE_DIR/native/${expected}-${name}"
        stamp="$SOURCE_DIR/app/src/main/cpp/third_party/.${abi}-${expected}.stamp"

        if [ ! -f "$archive" ] || ! printf '%s  %s\n' "$expected" "$archive" | sha256sum --check --status; then
            say "Downloading $name"
            rm -f "$archive"
            curl --fail --location --retry 3 --retry-delay 2 "$url" -o "$archive.tmp"
            printf '%s  %s\n' "$expected" "$archive.tmp" | sha256sum --check --status || fail "checksum failed for $name"
            mv "$archive.tmp" "$archive"
        fi

        validate_native_archive "$archive" "$abi"
        if [ ! -f "$stamp" ]; then
            say "Extracting verified $name"
            mkdir -p "$SOURCE_DIR/app/src/main/cpp/third_party"
            tar xzf "$archive" -C "$SOURCE_DIR/app/src/main/cpp/"
            rm -f "$SOURCE_DIR/app/src/main/cpp/third_party/.${abi}-"*.stamp
            touch "$stamp"
        fi
    done
}

prepare_key() {
    mkdir -p "$SECRETS_DIR"
    if [ -f "$KEYSTORE" ] && [ ! -f "$PASSWORD_FILE" ]; then
        fail "an imported keystore requires the saved keystore password"
    fi
    if [ ! -f "$KEYSTORE" ] && [ ! -f "$PASSWORD_FILE" ]; then
        umask 077
        openssl rand -hex 24 > "$PASSWORD_FILE"
        cp "$PASSWORD_FILE" "$KEY_PASSWORD_FILE"
        chmod 600 "$PASSWORD_FILE" "$KEY_PASSWORD_FILE"
    fi

    local password key_password signer dname
    password="$(tr -d '\r\n' < "$PASSWORD_FILE")"
    if [ -f "$KEY_PASSWORD_FILE" ]; then
        key_password="$(tr -d '\r\n' < "$KEY_PASSWORD_FILE")"
    else
        key_password="$password"
    fi
    [ "${#password}" -ge 6 ] || fail "stored signing password is invalid"
    [ -n "$key_password" ] || fail "stored key password is invalid"
    signer="$(normalize_signer_name "$OAL_SIGNER_NAME")"
    [ -n "$signer" ] || fail "signer name is empty"
    dname="CN=$(escape_dname_value "$signer"), OU=Personal Build, O=OpenAutoLink Community, C=${OAL_COUNTRY}"

    if [ ! -f "$KEYSTORE" ]; then
        say "Creating a new signing key"
        keytool -genkeypair \
            -keystore "$KEYSTORE" \
            -storetype PKCS12 \
            -storepass "$password" \
            -alias "$KEY_ALIAS" \
            -keypass "$key_password" \
            -keyalg RSA \
            -keysize 4096 \
            -validity 9125 \
            -dname "$dname" \
            -noprompt
        chmod 600 "$KEYSTORE"
        cat > "$SECRETS_DIR/BACK-UP-THIS-DIRECTORY.txt" <<'EOF'
Back up this entire directory before uploading your first AAB to Google Play.
The keystore and password are required to publish every future update.
Do not commit, email, or publicly share these files.
EOF
        chmod 600 "$SECRETS_DIR/BACK-UP-THIS-DIRECTORY.txt"
    fi

    keytool -list -keystore "$KEYSTORE" -storepass "$password" -alias "$KEY_ALIAS" >/dev/null \
        || fail "stored signing key or alias could not be opened"
}

build_unsigned() {
    local last_code saved_app_id next_code version_name source_sha short_sha
    last_code="$(property_value "$VERSION_FILE" lastVersionCode)"
    saved_app_id="$(property_value "$VERSION_FILE" appId)"
    last_code="${last_code:-${OAL_START_VERSION_CODE:-0}}"
    if [ -n "$saved_app_id" ] && [ "$saved_app_id" != "$OAL_APP_ID" ]; then
        say "Package ID changed; starting from the configured version baseline"
        last_code="${OAL_START_VERSION_CODE:-0}"
    fi
    next_code="$(next_version_code "$last_code")" || fail "invalid or exhausted saved version code"
    version_name="$(version_name_for "$next_code")"
    source_sha="$(git -C "$SOURCE_DIR" rev-parse HEAD)"
    short_sha="$(git -C "$SOURCE_DIR" rev-parse --short=8 HEAD)"

    say "Building unsigned $OAL_APP_ID version $version_name ($next_code) from $short_sha"
    (
        cd "$SOURCE_DIR"
        chmod +x gradlew
        ./gradlew :app:bundleRelease --no-daemon --dependency-verification=strict \
            -PoalBuilderUnsigned=true \
            "-PappId=$OAL_APP_ID" \
            "-PoalVersionCode=$next_code" \
            "-PoalVersionName=$version_name"
    )

    local built="$SOURCE_DIR/app/build/outputs/bundle/release/app-release.aab"
    [ -s "$built" ] || fail "Gradle completed without producing app-release.aab"
    java -jar "$BUNDLETOOL_JAR" validate --bundle="$built" >/dev/null \
        || fail "unsigned Android App Bundle validation failed"
    cp "$built" "$STAGING_DIR/unsigned.aab.tmp"
    mv "$STAGING_DIR/unsigned.aab.tmp" "$STAGING_DIR/unsigned.aab"
    jq -n \
        --arg applicationId "$OAL_APP_ID" \
        --arg versionName "$version_name" \
        --argjson versionCode "$next_code" \
        --arg sourceCommit "$source_sha" \
        --arg sourceRef "$OAL_REF" \
        --arg shortSourceCommit "$short_sha" \
        '{applicationId:$applicationId,versionName:$versionName,versionCode:$versionCode,sourceCommit:$sourceCommit,sourceRef:$sourceRef,shortSourceCommit:$shortSourceCommit}' \
        > "$STAGING_DIR/build.json.tmp"
    mv "$STAGING_DIR/build.json.tmp" "$STAGING_DIR/build.json"
}

verify_signed_bundle() {
    local built="$1" expected_id="$2" expected_code="$3" expected_name="$4"
    java -jar "$BUNDLETOOL_JAR" validate --bundle="$built" >/dev/null \
        || fail "Android App Bundle validation failed"

    local signature_report
    signature_report="$(jarsigner -verify "$built" 2>&1)" || fail "AAB signature verification failed"
    case "$signature_report" in
        *"jar verified."*) ;;
        *) fail "jarsigner did not confirm a verified signed bundle" ;;
    esac
    case "$signature_report" in
        *unsigned*) fail "AAB contains unsigned entries" ;;
    esac

    local manifest_dump="/tmp/verified-aab-manifest.xml"
    java -jar "$BUNDLETOOL_JAR" dump manifest --bundle="$built" > "$manifest_dump"
    local actual_id actual_code actual_name
    actual_id="$(xmllint --xpath 'string(/*[local-name()="manifest"]/@package)' "$manifest_dump")"
    actual_code="$(xmllint --xpath 'string(/*[local-name()="manifest"]/@*[local-name()="versionCode"])' "$manifest_dump")"
    actual_name="$(xmllint --xpath 'string(/*[local-name()="manifest"]/@*[local-name()="versionName"])' "$manifest_dump")"
    [ "$actual_id" = "$expected_id" ] || fail "AAB package mismatch: expected $expected_id, got $actual_id"
    [ "$actual_code" = "$expected_code" ] || fail "AAB versionCode mismatch: expected $expected_code, got $actual_code"
    [ "$actual_name" = "$expected_name" ] || fail "AAB versionName mismatch: expected $expected_name, got $actual_name"
}

sign_bundle() {
    local pending="$STAGING_DIR/build.json" unsigned="$STAGING_DIR/unsigned.aab"
    [ -s "$pending" ] || fail "unsigned build metadata is missing"
    [ -s "$unsigned" ] || fail "unsigned AAB is missing"
    [ -r "$KEYSTORE" ] || fail "signing keystore is missing"

    local app_id version_name version_code source_sha source_ref short_sha password key_password
    app_id="$(jq -er .applicationId "$pending")"
    version_name="$(jq -er .versionName "$pending")"
    version_code="$(jq -er .versionCode "$pending")"
    source_sha="$(jq -er .sourceCommit "$pending")"
    source_ref="$(jq -er .sourceRef "$pending")"
    short_sha="$(jq -er .shortSourceCommit "$pending")"
    password="$(tr -d '\r\n' < "$PASSWORD_FILE")"
    if [ -f "$KEY_PASSWORD_FILE" ]; then
        key_password="$(tr -d '\r\n' < "$KEY_PASSWORD_FILE")"
    else
        key_password="$password"
    fi

    local artifact="$OUTPUT_DIR/openautolink-car-${version_name}-${short_sha}.aab"
    local metadata="${artifact%.aab}.json"
    cp "$unsigned" "$artifact.tmp"
    jarsigner \
        -keystore "$KEYSTORE" \
        -storepass "$password" \
        -keypass "$key_password" \
        "$artifact.tmp" "$KEY_ALIAS" >/dev/null

    say "Verifying AAB structure, signature, package ID, version, and signer"
    verify_signed_bundle "$artifact.tmp" "$app_id" "$version_code" "$version_name"

    local fingerprint aab_fingerprint
    fingerprint="$(keytool -list -v -keystore "$KEYSTORE" -storepass "$password" -alias "$KEY_ALIAS" | sed -n 's/^[[:space:]]*SHA256: //p' | head -1)"
    aab_fingerprint="$(keytool -printcert -jarfile "$artifact.tmp" | sed -n 's/^[[:space:]]*SHA256: //p' | head -1)"
    [ -n "$fingerprint" ] || fail "could not read signing certificate fingerprint"
    [ -n "$aab_fingerprint" ] || fail "could not extract the AAB signing certificate"
    [ "$aab_fingerprint" = "$fingerprint" ] || fail "AAB signing certificate mismatch"

    mv "$artifact.tmp" "$artifact"
    (
        cd "$OUTPUT_DIR"
        sha256sum "$(basename "$artifact")" > "$(basename "$artifact").sha256"
    )
    jq -n \
        --arg applicationId "$app_id" \
        --arg versionName "$version_name" \
        --argjson versionCode "$version_code" \
        --arg sourceCommit "$source_sha" \
        --arg sourceRef "$source_ref" \
        --arg signingCertificateSha256 "$fingerprint" \
        --arg artifact "$(basename "$artifact")" \
        '{applicationId:$applicationId,versionName:$versionName,versionCode:$versionCode,sourceCommit:$sourceCommit,sourceRef:$sourceRef,signingCertificateSha256:$signingCertificateSha256,artifact:$artifact}' \
        > "$metadata.tmp"
    mv "$metadata.tmp" "$metadata"

    cat > "$VERSION_FILE.tmp" <<EOF
appId=${app_id}
lastVersionCode=${version_code}
lastVersionName=${version_name}
lastSourceCommit=${source_sha}
EOF
    mv "$VERSION_FILE.tmp" "$VERSION_FILE"
    ln -sfn "$(basename "$artifact")" "$OUTPUT_DIR/latest.aab"
    ln -sfn "$(basename "$metadata")" "$OUTPUT_DIR/latest.json"

    say "SUCCESS: $artifact"
    say "SHA-256: $(cut -d' ' -f1 "$artifact.sha256")"
    say "Signing certificate SHA-256: $fingerprint"
}

main() {
    local action="${1:-}"
    case "$action" in
        prepare-key)
            [ -n "${OAL_SIGNER_NAME:-}" ] || fail "OAL_SIGNER_NAME is required"
            [[ "${OAL_COUNTRY:-}" =~ ^[A-Z]{2}$ ]] || fail "OAL_COUNTRY must be two uppercase letters"
            prepare_key
            ;;
        build-unsigned)
            [ -n "${OAL_APP_ID:-}" ] || fail "OAL_APP_ID is required"
            [[ "${OAL_START_VERSION_CODE:-}" =~ ^[0-9]+$ ]] || fail "OAL_START_VERSION_CODE must be non-negative"
            [ -n "${OAL_REPO_URL:-}" ] || fail "OAL_REPO_URL is required"
            [ -n "${OAL_REF:-}" ] || fail "OAL_REF is required"
            prepare_source
            download_native_deps
            build_unsigned
            ;;
        sign)
            sign_bundle
            ;;
        *) fail "unknown action: $action" ;;
    esac
}

if [ "${OAL_LIB_ONLY:-0}" != "1" ]; then
    main "$@"
fi
