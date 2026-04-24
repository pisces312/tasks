#!/usr/bin/env bash
# Tasks.org Build Script (Git Bash / Linux / macOS)
# Usage: ./build.sh <debug|release> [flavor]
#
# Examples:
#   ./build.sh debug           # Build fdroid debug
#   ./build.sh release         # Build fdroid release
#   ./build.sh debug googleplay # Build googleplay debug

set -e

BUILD_TYPE="${1:-debug}"
FLAVOR="${2:-fdroid}"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

echo -e "${CYAN}========================================${NC}"
echo -e "${CYAN}  Tasks.org Build Script${NC}"
echo -e "${CYAN}========================================${NC}"
echo ""
echo -e "  Build Type : ${YELLOW}${BUILD_TYPE}${NC}"
echo -e "  Flavor    : ${YELLOW}${FLAVOR}${NC}"
echo ""

# JVM args - 8GB heap, parallel GC
export GRADLE_OPTS="-Xmx8G -XX:+UseParallelGC -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8"

# Detect Java
if [ -d "/d/nili/dev/AndroidStudio/jbr" ]; then
    export JAVA_HOME="/d/nili/dev/AndroidStudio/jbr"
elif [ -d "/c/Program Files/Eclipse Adoptium/jdk-21" ]; then
    export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21"
fi

if [ -n "$JAVA_HOME" ]; then
    echo -e "  Java      : ${YELLOW}${JAVA_HOME}${NC}"
else
    echo -e "  Java      : ${YELLOW}system default${NC}"
fi

# Find gradle
GRADLE="./gradlew"
if [ ! -f "$GRADLE" ]; then
    GRADLE="gradle"
fi

TASK="assemble$(echo "$FLAVOR" | sed 's/.*/\u&/')$(echo "$BUILD_TYPE" | sed 's/.*/\u&/')"

echo -e "  Task      : ${YELLOW}${TASK}${NC}"
echo ""
echo -e "${GREEN}[BUILD] Starting...${NC}"

# Run build
if [ -f "./gradlew" ]; then
    chmod +x ./gradlew
    ./gradlew --no-daemon "$TASK"
else
    gradle --no-daemon "$TASK"
fi

EXIT_CODE=$?

if [ $EXIT_CODE -eq 0 ]; then
    echo ""
    echo -e "${GREEN}[OK] Build successful!${NC}"
    echo ""

    # Find APK
    APK_DIR="composeApp/build/outputs/apk/${FLAVOR}/${BUILD_TYPE}"
    if [ -d "$APK_DIR" ]; then
        APK=$(find "$APK_DIR" -name "*.apk" -type f 2>/dev/null | head -1)
        if [ -n "$APK" ]; then
            SIZE=$(du -h "$APK" | cut -f1)
            echo -e "  APK: ${CYAN}${APK}${NC}"
            echo -e "  Size: ${CYAN}${SIZE}${NC}"
        fi
    fi
else
    echo ""
    echo -e "${RED}[FAIL] Build failed with exit code ${EXIT_CODE}${NC}"
fi

exit $EXIT_CODE
