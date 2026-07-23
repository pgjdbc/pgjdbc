#!/usr/bin/env bash
#
# Trigger a Fedora Copr build for this commit (or PR) and wait for it to finish.
# On failure, print the Copr build log so the error shows in the CI step output.
#
# This runs in GitHub Actions (it is NOT the script Copr runs; that one is
# `build-script` in this directory). It reimplements the submit-and-poll that
# copr-ci-tooling's copr-gh-actions-submit does over the Copr webhook and REST
# API, so the workflow does not fetch a remote script whose content can change.
#
# Required environment:
#   COPR_PR_WEBHOOK    custom webhook URL for PR builds (public)
#   COPR_PUSH_WEBHOOK  custom webhook URL for push builds (secret)
#
# Usage: submit-copr.sh [PR_NUMBER]

set -uo pipefail

pr_id=${1:-}

if [ -n "$pr_id" ]; then
  # actions/checkout leaves a merge ref checked out; fetch the PR head so the
  # webhook payload carries the commit Copr should build.
  ref=refs/pull/$pr_id
  git fetch --depth=1 origin "+pull/$pr_id/head:$ref" \
    || { echo "::error::cannot fetch PR #$pr_id"; exit 1; }
  webhook=${COPR_PR_WEBHOOK:-}
  payload=$(printf '{"type":"PR","pr_id":"%s","git_hash":"%s"}' "$pr_id" "$(git rev-parse "$ref")")
else
  webhook=${COPR_PUSH_WEBHOOK:-}
  payload=$(printf '{"type":"PUSH","git_hash":"%s"}' "$(git rev-parse HEAD)")
fi

if [ -z "$webhook" ]; then
  echo "::error::Copr webhook URL is not set"
  exit 1
fi

echo "Submitting Copr build: $payload"
build_id=$(curl -fsS -X POST -H 'Content-Type: application/json' --data "$payload" "$webhook" | grep -oE '[0-9]+' | head -1)
if [ -z "$build_id" ]; then
  echo "::error::Copr build submission failed"
  exit 1
fi
echo "Submitted build: https://copr.fedorainfracloud.org/coprs/build/$build_id/"

# Poll until the build reaches a terminal state; tolerate transient API errors.
# Counted (not while-true) so an unexpected or stuck state cannot hang the job.
api="https://copr.fedorainfracloud.org/api_3/build/$build_id"
state=""
for ((i = 0; i < 240; i++)); do  # up to ~2h at 30s intervals
  sleep 30
  state=$(curl -fsS "$api" | jq -r '.state') || { state=""; continue; }
  echo "build $build_id state: $state"
  case $state in
    succeeded)         exit 0 ;;
    failed | canceled) break ;;
    pending | starting | running | importing | waiting | imported | "" | null) ;;
    *) echo "::error::unexpected Copr build state: $state"; exit 1 ;;
  esac
done

# Reached on failed/canceled (loop broke) or on timeout (loop exhausted).
case $state in
  failed | canceled) ;;  # fall through to the failure log below
  *) echo "::error::Copr build $build_id did not finish in time (last state: ${state:-unknown})"; exit 1 ;;
esac

echo "::error title=Copr build $build_id failed::https://copr.fedorainfracloud.org/coprs/build/$build_id/"
build_json=$(curl -fsS "$api")
repo_url=$(printf '%s' "$build_json" | jq -r '.repo_url // empty')
srpm_version=$(printf '%s' "$build_json" | jq -r '.source_package.version // empty')
padded=$(printf '%08d' "$build_id")

# High-signal error markers across dnf, rpmbuild, Maven and Gradle output.
err_pattern='What went wrong|BUILD FAILURE|FAILURE: Build failed|BUILD FAILED|COMPILATION ERROR|\[ERROR\]|cannot find symbol|error:|No match for argument|Failed to resolve|Could not resolve|Tests run: .*Failures: [1-9]'

# Print the first error lines with context; the first failure is usually the root cause.
# grep --color highlights the matched marker (GitHub renders ANSI).
show() { # $1: label, $2: .log.gz URL, $3: "group" to collapse the output
  [ "${3:-}" = group ] && echo "::group::$1" || echo "=== $1 ==="
  echo "log: $2"
  if curl -sSf "$2" 2>/dev/null | gunzip -c > log.txt; then
    local matches
    matches=$(grep --color=always -n -m 40 -iE -B2 -A5 "$err_pattern" log.txt) || true
    if [ -n "$matches" ]; then
      printf '%s\n' "$matches" | head -200
    else
      echo "(no known error markers; tail of the log)"
      tail -n 20 log.txt
    fi
  else
    echo "(no log at $2)"
  fi
  [ "${3:-}" = group ] && echo "::endgroup::"
}

# Show the SRPM log only when the SRPM stage itself failed (no source version built).
# When the SRPM succeeds, the failures are in the per-chroot RPM builds below.
if [ -z "$srpm_version" ]; then
  show "SRPM build log" "$repo_url/srpm-builds/$padded/builder-live.log.gz"
fi

# Per-chroot RPM build logs, for chroots that got far enough to produce one.
# Collapsed, since several chroots can fail at once.
curl -fsS "https://copr.fedorainfracloud.org/api_3/build-chroot/list?build_id=$build_id" \
  | jq -r '.items[] | select(.state == "failed" and .result_url != null) | "\(.name) \(.result_url)"' \
  | while read -r name url; do
      show "chroot $name" "${url%/}/builder-live.log.gz" group
    done

exit 1
