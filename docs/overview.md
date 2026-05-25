# IndoorAR — Project Overview

## What Is This?

IndoorAR is an augmented-reality indoor navigation and product-scanning application targeting **RayNeo smart glasses**. It consists of two things:

1. An **Android app** that uses the glasses camera + AR tracking to let users map a space, navigate through it, and scan product barcodes
2. A **cloud server** that stores the maps and product data the app needs

The app has three modes:

| Mode | What the user does |
|---|---|
| **Survey** | Walks through a space. The app silently records their path. They drop named markers at destinations (e.g. "Electronics Shelf"). Uploads the whole map to the server. |
| **Navigation** | Downloads a map, picks a destination, follows a live 2D mini-map drawn on screen. Guided by A\* pathfinding. |
| **Barcode** | Points the camera at a product barcode. The app fetches product info from the server and shows a card overlay. |

---

## Repository Structure

```
IndoorAR/
├── app/        Android app  (AR client)
├── server/     Backend server (REST API + database)
├── shared/     Shared code compiled for both app and server
└── docs/       This folder
```

The `shared/` module is the architectural centrepiece — data models and the pathfinding algorithm are written **once** and reused by both the Android app and the server. See `technical-deep-dive.md` for full detail.

---

## Android App

### Tech Used

| What | Technology |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose (Material 3) |
| Navigation | AndroidX Navigation3 |
| AR tracking | Google ARCore (via SceneView 2.1.1) |
| Barcode scanning | Google ML Kit |
| Smart glasses SDK | RayNeo MercurySDK (.aar) |
| HTTP client | Ktor Client (OkHttp engine) |
| JSON | kotlinx.serialization |
| Image loading | Coil |
| Local storage | SharedPreferences |

### How AR Works

The app uses **ARCore** — Google's augmented reality engine. ARCore tracks the device's position and orientation in 3D space using the camera + IMU sensors. Every frame it gives back a **pose**: the device's `(x, y, z)` position in metres and its `(qx, qy, qz, qw)` quaternion rotation.

The app converts that quaternion into yaw/pitch/roll angles and stores it as `Pose(position: Vector3, rotation: Vector3)`. Yaw (the heading — which direction you are facing horizontally) drives both the mini-map heading arrow and the navigation turn-angle calculation.

The AR camera view is provided by **SceneView** (`arsceneview:2.1.1`), which wraps ARCore in a Jetpack Compose-friendly `ARScene` composable. It handles session lifecycle, permission, and plane detection automatically.

### Survey Mode — How the Map is Built

While the user walks:
- Every **500 ms** the app checks the current pose
- If the user has moved ≥ **1 metre** since the last recorded point, a new `NavNode` is saved and connected to the previous one with a `NavEdge` weighted by the actual distance
- The user can tap a detected floor plane to drop a named `Waypoint` (e.g. "Aisle 3")
- On upload, all nodes + edges + waypoints are bundled into a `MapLocation` and sent to the server as JSON

### Navigation Mode — How Pathfinding Works

- The app downloads a map from the server
- The user picks a destination waypoint from a list
- **A\* pathfinding** finds the shortest route through the recorded walkable nodes
- The route is drawn as a **2D mini-map canvas overlay** — the entire path is scaled to fit the screen, the user's dot moves in real time, and a heading arrow rotates with their yaw
- When the user is within **1 metre** of the destination, arrival is detected and a sound plays

### Barcode Mode

- Every ARCore camera frame is processed by **ML Kit BarcodeScanning**, throttled to once every **2 seconds**
- On detection, the barcode value is used as an ID to call `GET /products/{id}` on the server
- A product card is shown: name, price, discounted price, product image, buy link

---

## Server

### Tech Used

| What | Technology |
|---|---|
| Language | Kotlin (JVM) |
| HTTP framework | Ktor |
| HTTP engine | Netty |
| Database ORM | JetBrains Exposed |
| Database | PostgreSQL (NeonDB serverless) |
| Serialization | kotlinx.serialization |
| Deployment | Render (Docker) |

### What the Server Stores

- All the surveyed indoor maps (nodes, edges, waypoints)
- A product catalogue seeded with real product data (matched to barcodes)

### REST API

| Method | URL | What it does |
|---|---|---|
| GET | `/` | Health check |
| GET | `/maps` | List all uploaded maps |
| POST | `/maps/upload` | Receive and store a full map from the app |
| GET | `/maps/{id}` | Return a full map (all nodes/edges/waypoints) |
| GET | `/products/{id}` | Return product info by barcode ID |

### Deployment

The server runs on **Render** (a cloud hosting platform) at `https://rayneoarpoc.onrender.com`. The database is **NeonDB** — a serverless PostgreSQL service hosted on AWS. The server is also Dockerised and can be run locally with `./gradlew :server:run`.

---

## Shared Module

The `shared/` module is compiled for both Android and JVM using **Kotlin Multiplatform (KMP)**. It contains:

- **All data models** — `NavGraph`, `NavNode`, `NavEdge`, `Waypoint`, `MapLocation`, `Vector3`, `Product`. These are the exact same classes used by the app to build JSON and by the server to parse it.
- **A\* Pathfinder** — takes a `NavGraph`, finds the shortest path between two positions, returns a list of 3D waypoints to follow.

---

## Build Commands

```bash
# Android app
./gradlew :app:assembleDebug
./gradlew :app:installDebug

# Server
./gradlew :server:run         # run locally on port 8080
./gradlew :server:jar         # build fat JAR

# Shared module
./gradlew :shared:build

# Docker
docker build -t indoorar-server .
docker run -p 8080:8080 indoorar-server
```
