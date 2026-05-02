# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Build with build.sh (recommended on Windows with Git Bash)
./build.sh                           # generic release, arm64-v8a
./build.sh debug                    # generic debug
./build.sh release googleplay        # googleplay release
./build.sh release generic all      # all ABIs

# Or directly with Gradle
./gradlew assembleGenericDebug
./gradlew assembleGoogleplayRelease -PbuildAbi=arm64-v8a
./gradlew assembleGenericDebugArm64-v8a

# Run tests
./gradlew test                                    # unit tests
./gradlew testDebugUnitTest                       # unit tests (debug)
./gradlew connectedDebugAndroidTest              # instrumented tests (requires emulator)
./gradlew testDebugUnitTest --tests "*.TaskTest" # single test class
```

## Architecture

**Multi-module Android project** with Kotlin Multiplatform capabilities (composeApp for desktop/iOS).

| Module | Purpose |
|--------|---------|
| `app/` | Main Android application |
| `data/` | Data layer (Room DB, DAOs) |
| `composeApp/` | Kotlin Multiplatform Compose UI (desktop/iOS) |
| `kmp/` | Kotlin Multiplatform shared code |
| `icons/` | App icon resources |
| `wear/` | Android Wear app |
| `wear-datalayer/` | Wear data layer (gRPC) |

**Build flavors:** `generic`, `googleplay` (default)

**Key technologies:**
- DI: Hilt/Dagger
- Database: Room
- UI: Compose + ViewBinding/DataBinding hybrid
- Networking: Ktor + OkHttp
- Sync: JGit (Git sync), Google Tasks API, CalDAV, Etebase

**Source sets per flavor:**
- `main/` - shared code
- `generic/` - generic flavor only
- `googleplay/` - Google Play flavor only (Play Services, billing, maps)

## Development Notes

**Release signing:** Requires env vars `KEY_STORE_LOCATION`, `KEY_ALIAS`, `KEY_STORE_PASSWORD`, `KEY_PASSWORD`. Debug uses default debug keystore.

**Mapbox/Google APIs:** Add to `gradle.properties`:
```
tasks_mapbox_key_debug="<key>"
tasks_google_key_debug="<key>"
```

**ABI splits:** Use `-PbuildAbi=arm64-v8a` to build single ABI (faster), omit for all.

**Linting:** `./gradlew lint` (configured in app/lint.xml)

**Java:** Requires JDK 17+ (configured to JBR at `D:\nili\dev\AndroidStudio\jbr\`)