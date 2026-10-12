#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p dist
for platform in windows darwin linux; do
  for arch in amd64 arm64; do
    extension=""
    if [ "$platform" = windows ]; then extension=".exe"; fi
    GOOS="$platform" GOARCH="$arch" CGO_ENABLED=0 go build -trimpath -o "dist/dsh-bridge-$platform-$arch$extension" ./cmd/dsh-bridge
  done
done
