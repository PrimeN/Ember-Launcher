#!/usr/bin/env bash
# Collects compiler and Gradle error lines from a log into one annotation, so they show on the
# run's summary page without opening the full log. Usage: gradle-errors.sh <log> <title>
log="$1"; title="${2:-Build errors}"
grep -E '^e: |What went wrong|^> |error:|AAPT|Could not resolve|Unresolved|FAILED$|Test.*> .* FAILED|AssertionError|expected:<|Dependency .* requires|is currently compiled against|minCompileSdk|recommended action' "$log" \
  | grep -v -E '^> Task .* (UP-TO-DATE|NO-SOURCE|SKIPPED|FROM-CACHE)$|^> Task [^ ]*$' \
  | sed -E 's#file://[^ ]*/app/src/(main|test)/java/app/ember/tv/##; s#file://[^ ]*/app/src/main/##' \
  | awk '!seen[$0]++' | head -120 > errors.txt || true
msg=$(sed -e ':a' -e 'N' -e '$!ba' -e 's/%/%25/g; s/\r/%0D/g; s/\n/%0A/g' errors.txt)
echo "::error title=${title}::${msg}"
