#!/usr/bin/env bash
# Full local verification: unit tests, catalog validation, certificate replay, lint and debug APK.
# Requires JAVA_HOME (JDK 17+) and ANDROID_HOME (or local.properties) to be set by the caller.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
mkdir -p logs
cleanup() {
    result=$?
    trap - EXIT
    if ! ./gradlew --stop >logs/gradle-stop.log 2>&1; then
        printf '%s\n' 'Failed to stop Gradle; see logs/gradle-stop.log' >&2
        if (( result == 0 )); then result=1; fi
    fi
    exit "$result"
}
trap cleanup EXIT
run_check() {
    local name=$1
    shift
    printf 'Running %s…\n' "$name"
    if "$@" >"logs/$name.log" 2>&1; then
        printf '%s: PASS (exit 0)\n' "$name"
    else
        local result=$?
        tail -70 "logs/$name.log" >&2
        printf '%s: FAIL (exit %s)\n' "$name" "$result" >&2
        return "$result"
    fi
}
run_check tests ./gradlew :core:test :leveltool:test :app:testDebugUnitTest --console=plain --max-workers=2
run_check content ./gradlew :leveltool:run --args='validate --catalog content/catalog.json --strict' --console=plain --max-workers=2
run_check certificates ./gradlew :leveltool:run --args='verify-certificates --catalog content/catalog.json' --console=plain --max-workers=2
run_check apk ./gradlew :app:lintDebug :app:assembleDebug --console=plain --max-workers=2
printf '%s\n' 'All checks passed. APK at app/build/outputs/apk/debug/app-debug.apk'
