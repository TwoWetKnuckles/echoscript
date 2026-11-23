#!/usr/bin/env bash

output_file="$(mktemp)"
trap 'rm -f "$output_file"' EXIT

if ./gradlew "$@" >"$output_file" 2>&1; then
  cat "$output_file"
  exit 0
else
  status=$?
  cat "$output_file"
  if ! grep -Fq "Plugin [id: 'org.jetbrains.kotlin.plugin.compose', version: '2.2.10'" "$output_file" ||
    ! grep -Fq 'was not found in any of the following sources' "$output_file"; then
    exit "$status"
  fi
fi

echo "Kotlin Compose plugin resolution failed; retrying once with refreshed dependencies."
if ./gradlew "$@" --refresh-dependencies >"$output_file" 2>&1; then
  cat "$output_file"
else
  status=$?
  cat "$output_file"
  exit "$status"
fi
