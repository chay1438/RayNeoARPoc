# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

IndoorAR is an Android AR navigation app targeting RayNeo smart glasses, with a companion Ktor backend. Users survey an indoor space, upload a nav-graph to the server, then navigate using ARCore-tracked pose + A* pathfinding overlaid as a 2D mini-map. A barcode scanning mode fetches product info from the server.

---

## Build & Run Commands

### Android App
```bash
./gradlew :app:assembleDebug        # Build debug APK
./gradlew :app:installDebug         # Build and install on connected device/emulator
./gradlew :app:test                 # Run JVM unit tests
./gradlew :app:connectedAndroidTest # Run instrumented tests (requires device/emulator)
```

### Ktor Server
```bash
./gradlew :server:run               # Run server locally on port 8080
./gradlew :server:installDist       # Build a runnable distribution
./gradlew :server:jar               # Build fat JAR to server/build/libs/server-1.0.0.jar
```

### Shared KMP Module
```bash
./gradlew :shared:build             # Compile shared module for both Android and JVM targets
```

### Docker (server)
```bash
docker build -t indoorar-server .
docker run -p 8080:8080 indoorar-server
```

---

## Module Structure

```
IndoorAR/
├── app/          Android app (Jetpack Compose + ARCore + ML Kit)
├── server/       Ktor JVM server (Netty + Exposed ORM + PostgreSQL)
└── shared/       KMP library — models and pathfinding (targets: androidLibrary, jvm "server")
```

### `shared` module
- **`shared/src/commonMain/`** — compiled for both targets
  - `models/Models.kt` — all `@Serializable` data classes: `Vector3`, `Waypoint`, `NavNode`, `NavEdge`, `NavGraph`, `MapLocation`, `Product`
  - `pathfinding/AStarPathfinder.kt` — A* over `NavGraph`; snaps start/end to nearest node; returns `List<Vector3>`

### `app` module
- `ar/ARCoreSessionManager.kt` — converts ARCore quaternion pose → Euler angles, exposes `currentPose: StateFlow<Pose?>`. ARCore yaw is negated on storage: `rotation = Vector3(pitch, -yaw, roll)`.
- `ar/CameraProjection.kt` — software 3D-to-2D projection (60° FOV, yaw-only rotation). Returns `ScreenPoint(x, y, isVisible, depth)` in pixel coords.
- `ar/ARSessionManager.kt` — interface; `MockARSessionManager.kt` and `RealARSessionManager.kt` exist but the app currently uses `ARCoreSessionManager` directly.
- `domain/NavigationEngine.kt` — given current `Pose` + destination `Waypoint`, computes `NavigationState` (distance, turn angle, path points). Falls back to a straight-line path if no A* path is available.
- `domain/Pose.kt` — `data class Pose(position: Vector3, rotation: Vector3)` (rotation in degrees, yaw = Y component)
- `data/WaypointRepository.kt` — SharedPreferences-backed local persistence for waypoints (JSON via kotlinx.serialization).
- `network/NetworkManager.kt` — Ktor client singleton. `BASE_URL = "https://rayneoarpoc.onrender.com"`. Endpoints: `POST /maps/upload`, `GET /maps`, `GET /maps/{id}`, `GET /products/{id}`. Falls back to a mock product on error.
- `ui/main/MainScreen.kt` — single large `@Composable` containing all app UI and state. Uses `ARScene` from `io.github.sceneview:arsceneview:2.1.1` as the full-screen background.
- `IndoorARApplication.kt` — initialises the RayNeo `MercurySDK` (from `app/libs/MercuryAndroidSDK.aar`).

### `server` module
- `Application.kt` — Ktor + Netty entry point on port 8080.
- `database/DatabaseFactory.kt` — connects to a hardcoded NeonDB PostgreSQL instance and runs `SchemaUtils.create(...)`. Seeds a product table on first run.
- `database/Schema.kt` — Exposed `Table` objects: `MapLocations`, `NavNodes`, `NavEdges`, `Waypoints`, `Products`.
- `routes/Routes.kt` — REST routes: `/maps` (list), `/maps/upload` (POST), `/maps/{id}` (GET), `/products/{id}` (GET).

---

## Key Architecture Details

### Three App Modes (`AppMode` enum)
| Mode | Behaviour |
|---|---|
| **SURVEY** | Auto-records walkable `NavNode`s every ≥1 m (polling every 500 ms). Plane renderer enabled. User manually drops named `Waypoint` markers via hit-test on detected floor planes. Uploads complete `MapLocation` (walkable nodes + waypoints + connecting edges) to server. |
| **NAVIGATION** | Downloads first map from server, populates waypoints list. User picks a destination; `AStarPathfinder` computes path; 2D mini-map Canvas overlay + AR breadcrumb dots are drawn. Auto-clears destination and plays notification sound on arrival (< 1 m threshold). |
| **BARCODE** | Processes every ARCore camera frame with ML Kit's `BarcodeScanning` client (throttled to once per 2 s). On detection, fetches product from server and displays a card overlay. |

### Coordinate System
ARCore uses a right-handed system with **−Z forward**. `CameraProjection` negates the rotated Z so that positive depth means "in front of camera". Yaw is stored negated (`-yaw`) to match the heading convention used by `NavigationEngine` and the mini-map heading arrow.

### Mini-Map Overlay
Rendered in a full-screen Compose `Canvas`. Scales path points to fit the canvas by computing `(minX, maxX, minZ, maxZ)` bounds with 2 m padding, then uniformly scales and centres. The user's position dot and heading arrow use `pose.rotation.y` (yaw in degrees).

### NavGraph Construction (Survey Upload)
1. Walkable nodes are recorded continuously while walking.
2. Named waypoints are added manually.
3. Each object waypoint is connected to its nearest walkable node via a pair of bidirectional edges.
4. If no walkable nodes exist (user skipped walking), object nodes are connected linearly as a fallback.

### Navigation3 (Nav3 library)
The app uses **AndroidX Navigation3** (`androidx.navigation3:navigation3-runtime:1.0.1`), not the older NavController. The back-stack is `rememberNavBackStack(Main)` with a single `Main` destination; multi-screen flows would add more `@Serializable` NavKey objects in `NavigationKeys.kt`.

---

## Testing
- **Unit tests** live in `app/src/test/` — `MainScreenViewModelTest.kt` (JVM, uses `kotlinx-coroutines-test`).
- **Instrumented tests** live in `app/src/androidTest/` — `MainScreenTest.kt` (Compose UI test via `ui-test-junit4`).
- The `shared` module has a `commonTest` source set; add tests there for pathfinding logic.

---

## Deployment
The server is deployed on **Render** at `https://rayneoarpoc.onrender.com`. The database is a **NeonDB (PostgreSQL)** instance whose connection string is hardcoded in `DatabaseFactory.kt`. When testing on a physical device against a local server, change `NetworkManager.BASE_URL` to your machine's LAN IP (e.g. `http://192.168.x.x:8080`).
