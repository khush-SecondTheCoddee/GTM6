#!/bin/sh
# Binary wrapper JARs cannot be stored in this source distribution. CI installs
# Gradle with gradle/actions/setup-gradle; developers can install Gradle 8.14+
# or use Android Studio's bundled Gradle.
set -eu

if ! command -v gradle >/dev/null 2>&1; then
  echo "Gradle 8.14+ is required. Install Gradle or run this project in Android Studio." >&2
  exit 1
fi

exec gradle "$@"
