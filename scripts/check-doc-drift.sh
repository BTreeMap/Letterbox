#!/usr/bin/env bash
# check-doc-drift.sh — Detect documentation drift for key CI/workflow claims.
#
# Usage: ./scripts/check-doc-drift.sh
#
# Exits 0 if all checks pass, 1 if any drift is detected.
# Run this locally or in CI to prevent docs from silently going stale.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORKFLOWS_README="$REPO_ROOT/.github/workflows/README.md"
ROOT_README="$REPO_ROOT/README.md"
BUILD_YML="$REPO_ROOT/.github/workflows/build.yml"
UI_YML="$REPO_ROOT/.github/workflows/android-ui.yml"
CARGO_TOML="$REPO_ROOT/Cargo.toml"
ANDROID_TEST_DIR="$REPO_ROOT/app/src/androidTest/java/org/joefang/letterbox"

ERRORS=0

fail() {
  echo "❌ DRIFT: $1"
  ERRORS=$((ERRORS + 1))
}

pass() {
  echo "✅ $1"
}

echo "=== Documentation Drift Check ==="
echo ""

# --- 1. Workflow output names in workflows README must match build.yml ---
echo "--- Checking build.yml output names in workflows README ---"

for output_name in "test-artifact-name" "prod-release-artifact-name"; do
  if grep -qF "$output_name" "$BUILD_YML" && grep -qF "$output_name" "$WORKFLOWS_README"; then
    pass "Workflow output '$output_name' found in both build.yml and workflows README"
  elif ! grep -qF "$output_name" "$BUILD_YML"; then
    fail "Workflow output '$output_name' referenced in README but not in build.yml"
  else
    fail "Workflow output '$output_name' exists in build.yml but missing from workflows README"
  fi
done

# Check for stale output names that no longer exist in build.yml
# Use word-boundary-aware matching to avoid false positives from substrings
for stale_name in "debug-artifact-name" "release-artifact-name"; do
  # Match the stale name as a standalone backtick-delimited identifier (e.g. `debug-artifact-name`)
  if grep -qP '(?<!\w)'"$stale_name"'(?!\w)' "$WORKFLOWS_README" && ! grep -qP '(?<!\w)'"$stale_name"'(?!\w)' "$BUILD_YML"; then
    fail "Stale output name '$stale_name' found in workflows README but not in build.yml"
  fi
done

echo ""

# --- 2. UI test command in workflows README must match android-ui.yml ---
echo "--- Checking UI test command ---"

# Extract the Gradle task from android-ui.yml
UI_TASK=$(grep -oP '\./gradlew \K\S+' "$UI_YML" | head -1)
if [ -n "$UI_TASK" ]; then
  if grep -qF "$UI_TASK" "$WORKFLOWS_README"; then
    pass "UI test task '$UI_TASK' matches in workflows README"
  else
    fail "UI test task in android-ui.yml is '$UI_TASK' but not found in workflows README"
  fi
else
  fail "Could not extract UI test task from android-ui.yml"
fi

echo ""

# --- 3. Test file path in workflows README must use correct package ---
echo "--- Checking androidTest file path ---"

if [ -d "$ANDROID_TEST_DIR" ]; then
  if grep -qF "org/joefang/letterbox" "$WORKFLOWS_README"; then
    pass "Correct package path 'org/joefang/letterbox' in workflows README"
  else
    fail "Workflows README does not reference correct test path 'org/joefang/letterbox'"
  fi
  # Check for stale wrong package path
  if grep -qF "com/btreemap/letterbox" "$WORKFLOWS_README"; then
    fail "Stale package path 'com/btreemap/letterbox' found in workflows README"
  fi
else
  fail "Android test directory not found at expected path: $ANDROID_TEST_DIR"
fi

echo ""

# --- 4. All androidTest .kt files should be listed in workflows README ---
echo "--- Checking androidTest file listing ---"

if [ -d "$ANDROID_TEST_DIR" ]; then
  for kt_file in "$ANDROID_TEST_DIR"/*.kt; do
    kt_filename=$(basename "$kt_file")
    if grep -qF "$kt_filename" "$WORKFLOWS_README"; then
      pass "Test file '$kt_filename' listed in workflows README"
    else
      fail "Test file '$kt_filename' exists but is not listed in workflows README"
    fi
  done
fi

echo ""

# --- 5. Cargo workspace members in README must match Cargo.toml ---
echo "--- Checking Cargo workspace members in README ---"

# Extract workspace members from Cargo.toml
CARGO_MEMBERS=$(grep -oP '"rust/[^"]+"' "$CARGO_TOML" | tr -d '"')
for member in $CARGO_MEMBERS; do
  crate_name=$(basename "$member")
  if grep -qF "$member" "$ROOT_README"; then
    pass "Workspace member '$member' mentioned in README"
  else
    fail "Workspace member '$member' from Cargo.toml not mentioned in README"
  fi
done

echo ""

# --- Summary ---
echo "=== Summary ==="
if [ "$ERRORS" -eq 0 ]; then
  echo "All documentation checks passed! ✅"
  exit 0
else
  echo "$ERRORS drift issue(s) detected. ❌"
  echo "Please update the documentation to match the repo source of truth."
  exit 1
fi
