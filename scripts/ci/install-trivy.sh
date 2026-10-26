#!/usr/bin/env bash
# Installs the pinned Trivy CLI from the official release (checksum-verified) into ./trivy.
# Why not aquasecurity/trivy-action: it pulls aquasecurity/setup-trivy by tag at run time and that tag no longer resolves
# ("Unable to resolve action aquasecurity/setup-trivy@v0.2.1"), which fails the job at "Set up job" before any step runs.
set -euo pipefail
V="${TRIVY_VERSION:-0.58.2}"
base="https://github.com/aquasecurity/trivy/releases/download/v${V}"
tmp="$(mktemp -d)"
curl -sSfL -o "$tmp/trivy.tgz" "$base/trivy_${V}_Linux-64bit.tar.gz"
curl -sSfL -o "$tmp/sums.txt" "$base/trivy_${V}_checksums.txt"
(cd "$tmp" && grep " trivy_${V}_Linux-64bit.tar.gz\$" sums.txt | sed 's/ .*/  trivy.tgz/' | sha256sum -c -)
tar -xzf "$tmp/trivy.tgz" -C "$tmp" trivy
install -m 0755 "$tmp/trivy" ./trivy
./trivy --version
