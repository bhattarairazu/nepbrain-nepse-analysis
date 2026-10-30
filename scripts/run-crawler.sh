#!/bin/bash
# Runs the crawler from the project folder. Builds the jar first if it is missing.
# Usage: scripts/run-crawler.sh [crawl|daemon|report|init-db|import-sectors] [options]
set -euo pipefail
cd "$(dirname "$0")/.."

JAR=target/nepse-crawler.jar
if [ ! -f "$JAR" ]; then
  mvn -q -B -DskipTests package
fi
exec java -jar "$JAR" "$@"
