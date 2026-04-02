#!/bin/bash
set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR=$(ls "$SCRIPT_DIR"/nacosbase-*.jar 2>/dev/null | head -1)
if [ -z "$JAR" ]; then
    echo "Error: nacosbase jar not found in $SCRIPT_DIR" >&2
    exit 1
fi
exec java --enable-native-access=ALL-UNNAMED -jar "$JAR" "$@"
