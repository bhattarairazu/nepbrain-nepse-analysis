#!/bin/bash
# Starts the dashboard + daily crawler from the project folder (http://localhost:8080).
# config/application.properties in this folder is picked up automatically.
set -euo pipefail
cd "$(dirname "$0")/.."

JAR=target/nepse-topturnover.jar
if [ ! -f "$JAR" ]; then
  mvn -q -B -DskipTests package
fi
mkdir -p logs
exec java -jar "$JAR" "$@"
