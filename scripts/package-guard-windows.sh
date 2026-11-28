#!/usr/bin/env bash
# Produce the portable Windows x64 launcher with its bundled Java runtime.
set -euo pipefail
cd "$(dirname "$0")/.."

runtime_version='21.0.13.1'
runtime_build='2'
runtime_archive="OpenJDK21U-jre_x64_windows_hotspot_${runtime_version}_${runtime_build}.zip"
runtime_sha='e15ee1e54a5a3b38027a09cedfa87bc3348d7dd3ce50866dd3d9671d7fffc6c1'
runtime_url="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-${runtime_version}%2B${runtime_build}/${runtime_archive}"
stage="$(mktemp -d)"
trap 'rm -r -- "$stage"' EXIT
archive="${1:-$stage/$runtime_archive}"

if [[ $# -eq 0 ]]; then
  curl --fail --location --retry 3 --max-time 180 "$runtime_url" --output "$archive"
fi
printf '%s  %s\n' "$runtime_sha" "$archive" | sha256sum --check
unzip -q "$archive" -d "$stage"

java Build.java package
version="$(java -jar dist/cellar.jar --version)"
version="${version#Cellar Guard }"
launcher="Cellar-Guard-Windows-x64.exe"

"${MAKENSIS:-makensis}" -V2 \
  "-DVERSION=$version" \
  "-DOUTPUT=$PWD/dist/$launcher" \
  "-DAPP_JAR=$PWD/dist/cellar.jar" \
  "-DAPP_LICENSE=$PWD/LICENSE" \
  "-DRUNTIME=$stage/jdk-${runtime_version}+${runtime_build}-jre" \
  release/guard-portable.nsi
(cd dist && sha256sum "$launcher" > "SHA256SUMS-Windows.txt")
echo "Created dist/$launcher for Cellar Guard $version"
