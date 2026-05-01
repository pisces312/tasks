#!/usr/bin/env bash
# Tasks.org Build Script (Git Bash / Linux / macOS)
# Usage: ./build.sh [debug|release] [flavor] [abi]
#
# Examples:
#   ./build.sh                          # Build generic release, arm64-v8a only (default)
#   ./build.sh debug                    # Build generic debug, arm64-v8a only
#   ./build.sh release generic          # Build generic release, arm64-v8a only
#   ./build.sh release googleplay       # Build googleplay release, arm64-v8a only
#   ./build.sh release generic all      # Build generic release, all ABIs
#   ./build.sh release generic armeabi-v7a  # Build generic release, armeabi-v7a only

set -e

BUILD_TYPE="${1:-release}"
FLAVOR="${2:-generic}"
ABI="${3:-arm64-v8a}"

# Validate
case "$BUILD_TYPE" in
    debug|release) ;;
    *) echo "Error: build type must be 'debug' or 'release', got '$BUILD_TYPE'"; exit 1 ;;
esac
case "$FLAVOR" in
    generic|googleplay) ;;
    *) echo "Error: flavor must be 'generic' or 'googleplay', got '$FLAVOR'"; exit 1 ;;
esac

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
echo -e "  Flavor     : ${YELLOW}${FLAVOR}${NC}"
echo -e "  ABI        : ${YELLOW}${ABI}${NC}"
echo ""

# Signing: reads from Windows user environment variables
# KEY_STORE_LOCATION, KEY_ALIAS, KEY_STORE_PASSWORD, KEY_PASSWORD
# Git Bash doesn't inherit Windows env vars, so import them
if [ -z "$KEY_STORE_LOCATION" ]; then
    KEY_STORE_LOCATION=$(powershell.exe -NoProfile -Command "[System.Environment]::GetEnvironmentVariable('KEY_STORE_LOCATION','User')" 2>/dev/null | tr -d '\r')
    KEY_ALIAS=$(powershell.exe -NoProfile -Command "[System.Environment]::GetEnvironmentVariable('KEY_ALIAS','User')" 2>/dev/null | tr -d '\r')
    KEY_STORE_PASSWORD=$(powershell.exe -NoProfile -Command "[System.Environment]::GetEnvironmentVariable('KEY_STORE_PASSWORD','User')" 2>/dev/null | tr -d '\r')
    KEY_PASSWORD=$(powershell.exe -NoProfile -Command "[System.Environment]::GetEnvironmentVariable('KEY_PASSWORD','User')" 2>/dev/null | tr -d '\r')
    export KEY_STORE_LOCATION KEY_ALIAS KEY_STORE_PASSWORD KEY_PASSWORD
fi

export GRADLE_OPTS="-Xmx4G -XX:+UseParallelGC -Dfile.encoding=UTF-8"

# Detect Java
if [ -d "/d/nili/dev/AndroidStudio/jbr" ]; then
    export JAVA_HOME="/d/nili/dev/AndroidStudio/jbr"
elif [ -d "/c/Program Files/Eclipse Adoptium/jdk-21" ]; then
    export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21"
fi

if [ -n "$JAVA_HOME" ]; then
    echo -e "  Java       : ${YELLOW}${JAVA_HOME}${NC}"
else
    echo -e "  Java       : ${YELLOW}system default${NC}"
fi

# Capitalize first letter for Gradle task name
TASK="assemble$(echo "$FLAVOR" | sed 's/\b./\u&/')$(echo "$BUILD_TYPE" | sed 's/\b./\u&/')"

echo -e "  Gradle Task: ${YELLOW}${TASK}${NC}"
echo ""

# ABI parameter: pass -PbuildAbi to Gradle for splits.abi (configured in build.gradle.kts)
ABI_PARAM=""
if [ "$ABI" != "all" ]; then
    ABI_PARAM="-PbuildAbi=$ABI"
fi

# Skip Crashlytics upload for generic flavor (no network access in China)
SKIP_TASK=""
if [ "$FLAVOR" = "generic" ] && [ "$BUILD_TYPE" = "release" ]; then
    SKIP_TASK="-x uploadCrashlyticsMappingFileGenericRelease"
fi

echo -e "${GREEN}[BUILD] Starting...${NC}"
echo ""

# Run build
if [ -f "./gradlew" ]; then
    chmod +x ./gradlew
    ./gradlew --no-daemon "$TASK" $SKIP_TASK $ABI_PARAM
else
    gradle --no-daemon "$TASK" $SKIP_TASK $ABI_PARAM
fi

EXIT_CODE=$?

if [ $EXIT_CODE -ne 0 ]; then
    echo ""
    echo -e "${RED}[FAIL] Build failed with exit code ${EXIT_CODE}${NC}"
    exit $EXIT_CODE
fi

echo ""
echo -e "${GREEN}[OK] Build successful!${NC}"
echo ""

# Find APK
APK_DIR="app/build/outputs/apk/${FLAVOR}/${BUILD_TYPE}"
APK=""
if [ -d "$APK_DIR" ]; then
    APK=$(find "$APK_DIR" -name "*.apk" -type f 2>/dev/null | head -1)
fi

if [ -z "$APK" ]; then
    echo -e "  ${YELLOW}APK not found in ${APK_DIR}${NC}"
    exit 1
fi

SIZE=$(du -h "$APK" | cut -f1)
echo -e "  APK : ${CYAN}${APK}${NC}"
echo -e "  Size: ${CYAN}${SIZE}${NC}"
echo -e "  ABI : ${CYAN}${ABI}${NC}"
