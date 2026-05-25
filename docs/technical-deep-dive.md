# IndoorAR — Technical Deep Dive

This document explains the implementation details of the server, shared module, and key Android systems. For the high-level "what does it do" description, see `overview.md`.

---

## Kotlin & KMP Explained

### What is Kotlin?

Kotlin is a statically-typed language made by JetBrains. It compiles to JVM bytecode (same runtime as Java) and is the official language for Android development. Every `.kt` file in this project is Kotlin. Key properties relevant to this project:

- **Null safety** — types are non-null by default; nullable types are marked with `?`
- **Data classes** — `data class Foo(val x: Int)` auto-generates `equals`, `hashCode`, `copy`, `toString`
- **Coroutines** — `suspend fun` and `launch {}` provide structured concurrency without callbacks; used throughout for network calls and AR frame processing
- **`object`** — a singleton; `object NetworkManager` means there is exactly one instance for the app's lifetime
- **Extension functions** — add methods to existing classes without subclassing

### What is Kotlin Multiplatform (KMP)?

KMP lets you compile the **same Kotlin source files** to multiple targets. This project uses it to share code between the Android app and the JVM server.

```
shared/src/commonMain/kotlin/   ← compiled for BOTH targets
    models/Models.kt
    pathfinding/AStarPathfinder.kt
```

`shared/build.gradle.kts` declares two targets:

```kotlin
kotlin {
    androidLibrary { ... }   // produces an .aar consumed by :app
    jvm("server")            // produces a .jar consumed by :server
}
```

**The practical result:** `NavGraph`, `NavNode`, `NavEdge`, `Waypoint`, `MapLocation`, `Vector3`, and `Product` are defined exactly once. The Android app uses these classes to serialise JSON for upload; the server uses the exact same classes to deserialise that JSON. There is no duplication and no chance of a field name mismatch between client and server.

---

## Shared Module: Data Models

File: `shared/src/commonMain/kotlin/com/example/indoorar/shared/models/Models.kt`

All models are annotated with `@Serializable` (from `kotlinx.serialization`), which generates JSON encode/decode logic at compile time — no reflection, no runtime overhead.

```kotlin
@Serializable
data class Vector3(val x: Float, val y: Float, val z: Float) {
    fun distanceTo(other: Vector3): Float { ... }  // Euclidean distance
}

@Serializable
data class NavNode(val id: String, val position: Vector3)

@Serializable
data class NavEdge(val nodeAId: String, val nodeBId: String, val weight: Float)

@Serializable
data class NavGraph(
    val nodes: List<NavNode>,
    val edges: List<NavEdge>,
    val waypoints: List<Waypoint>
)

@Serializable
data class MapLocation(
    val id: String,
    val name: String,
    val cloudAnchorId: String,
    val navGraph: NavGraph
)

@Serializable
data class Product(
    val id: String,
    val title: String,
    val description: String?,
    val imageUrl: String?,
    val url: String?,
    val price: Double?,
    val discountedPrice: Double?,
    val discount: Double?
)
```

`MapLocation` is the root object exchanged between app and server. It contains everything: the spatial graph of a room plus all named destinations.

---

## Shared Module: A* Pathfinder

File: `shared/src/commonMain/kotlin/com/example/indoorar/shared/pathfinding/AStarPathfinder.kt`

A\* is a graph search algorithm that finds the shortest path between two nodes using a **heuristic** to guide the search toward the goal, making it faster than brute-force Dijkstra for spatial graphs.

### Construction

```kotlin
class AStarPathfinder(private val graph: NavGraph) {
    private val adjacencyList: Map<String, List<NavEdge>> = buildAdjacencyList()
    private val nodesById: Map<String, NavNode> = graph.nodes.associateBy { it.id }
```

On construction, two indexes are built:
- `adjacencyList` — for each node ID, the list of edges that leave it. Because the graph is undirected (you can walk either way along a recorded path), each edge stored in `NavGraph.edges` is added **twice**: once for `nodeA → nodeB` and once for the reverse `nodeB → nodeA`.
- `nodesById` — fast O(1) lookup of a node by its UUID string ID.

### `findPath(startPos, endPos)`

```kotlin
fun findPath(startPos: Vector3, endPos: Vector3): List<Vector3>
```

1. **Snap to nearest node** — ARCore position is a continuous float in 3D space. The graph only has nodes at positions the user actually walked through. `findNearestNode()` scans all nodes and returns the one with the smallest `distanceTo(pos)`.
2. **Run A\* between snapped nodes** — classic open-set A\* with `gScore` (cost from start) and `fScore` (gScore + heuristic). The heuristic is Euclidean distance to the goal node.
3. **Reconstruct path** — follows the `cameFrom` map backwards from goal to start, reverses, returns as `List<NavNode>`.
4. **Prepend/append raw positions** — the actual start and end `Vector3` positions are added to the list so the path starts exactly where the user stands and ends exactly at the destination marker.

Fallback: if the graph is empty or no path is found (disconnected graph), returns `[startPos, endPos]` — a straight line.

---

## Server: Ktor Framework

**Ktor** (version `3.1.2`) is a JetBrains HTTP framework for Kotlin. It is asynchronous by design — every request handler is a coroutine (`suspend` function), so threads are never blocked waiting for database queries. It has no annotations, no reflection, no classpath scanning — everything is configured explicitly in code.

### Server Entry Point

File: `server/src/main/kotlin/com/example/indoorar/server/Application.kt`

```kotlin
fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) { json() }
    DatabaseFactory.init()
    configureRouting()
}
```

`Application.module` is an **extension function** on Ktor's `Application` type — it extends Ktor's own class from outside its source code to configure the server. Three things happen:

1. **`install(ContentNegotiation) { json() }`** — registers a plugin that automatically (de)serialises Kotlin objects to/from JSON on every request/response using `kotlinx.serialization`. After this, calling `call.receive<MapLocation>()` parses the JSON body into a `MapLocation` object with no manual parsing code.

2. **`DatabaseFactory.init()`** — opens the PostgreSQL connection and ensures all tables exist.

3. **`configureRouting()`** — registers all URL route handlers.

### What is Netty?

**Netty** is a Java networking library that implements a non-blocking event loop. Ktor does not implement raw TCP itself; it delegates to Netty to accept connections and read bytes off sockets. The `ktor-server-netty` dependency wires this in. From the developer's perspective, you never write Netty code — it is purely the plumbing beneath Ktor.

### Routing DSL

File: `server/src/main/kotlin/com/example/indoorar/server/routes/Routes.kt`

```kotlin
fun Application.configureRouting() {
    routing {
        get("/") {
            call.respondText("IndoorAR Navigation Server is running!")
        }

        route("/maps") {
            get { /* list all maps */ }
            post("/upload") { /* receive and store a map */ }
            get("/{id}") { /* fetch one map by ID */ }
        }

        route("/products") {
            get("/{id}") { /* fetch product by barcode ID */ }
        }
    }
}
```

`routing { }`, `get { }`, `post { }` are all Kotlin lambda functions passed to Ktor builder methods — this is **not** annotations like Spring's `@GetMapping`. The code is executed when the server starts to build an internal routing tree.

`call.parameters["id"]` reads URL path parameters. `call.receive<T>()` deserialises the JSON body. `call.respond(obj)` serialises a Kotlin object to JSON and sets the response.

---

## Server: Database Layer

### What is PostgreSQL?

PostgreSQL is a relational database. Data lives in tables; rows are inserted with `INSERT`, queried with `SELECT`, filtered with `WHERE`. It enforces foreign keys (e.g. a `nav_nodes` row cannot reference a `map_id` that does not exist in `map_locations`). In this project, PostgreSQL stores:

- Every `MapLocation` ever uploaded by the app
- All `NavNode`, `NavEdge`, and `Waypoint` rows belonging to each map
- The product catalogue

### What is NeonDB?

NeonDB is a managed, **serverless PostgreSQL** cloud service. The actual database engine is standard PostgreSQL — NeonDB just handles the infrastructure: provisioning, storage, backups, scaling to zero when idle. Access is via a standard JDBC connection string. The database is hosted on AWS `us-east-1`. The connection string uses `?sslmode=require`, meaning all data is TLS-encrypted in transit. The `-pooler` hostname routes through Neon's PgBouncer connection pooler, which maintains a pool of warm database connections so the server does not pay TCP handshake overhead on every query.

### What is JetBrains Exposed?

**Exposed** (version `0.60.0`) is a Kotlin SQL library. It lets you define tables as Kotlin objects and write queries in a type-safe Kotlin DSL instead of raw SQL strings. The project uses the **DSL layer** (not the `Entity/DAO` layer).

#### Table Definitions — `Schema.kt`

Each table is declared as a Kotlin `object` extending `Table`:

```kotlin
object MapLocations : Table() {
    val id            = varchar("id", 50)
    val name          = varchar("name", 100)
    val cloudAnchorId = varchar("cloud_anchor_id", 100)
    override val primaryKey = PrimaryKey(id)
}

object NavNodes : Table() {
    val id    = varchar("id", 50)
    val mapId = varchar("map_id", 50) references MapLocations.id  // FK
    val x     = float("x")
    val y     = float("y")
    val z     = float("z")
    override val primaryKey = PrimaryKey(id)
}

object NavEdges : Table() {
    val id      = integer("id").autoIncrement()
    val mapId   = varchar("map_id", 50) references MapLocations.id
    val nodeAId = varchar("node_a_id", 50) references NavNodes.id
    val nodeBId = varchar("node_b_id", 50) references NavNodes.id
    val weight  = float("weight")
    override val primaryKey = PrimaryKey(id)
}

object Waypoints : Table() {
    val id    = varchar("id", 50)
    val mapId = varchar("map_id", 50) references MapLocations.id
    val name  = varchar("name", 100)
    val x     = float("x")
    val y     = float("y")
    val z     = float("z")
    override val primaryKey = PrimaryKey(id)
}

object Products : Table() {
    val id              = varchar("id", 100)
    val title           = text("title")
    val description     = text("description").nullable()
    val imageUrl        = varchar("image_url", 500).nullable()
    val url             = varchar("url", 500).nullable()
    val price           = double("price").nullable()
    val discountedPrice = double("discounted_price").nullable()
    val discount        = double("discount").nullable()
    override val primaryKey = PrimaryKey(id)
}
```

These objects serve two purposes simultaneously: they describe the schema (used by `SchemaUtils.create()` to generate `CREATE TABLE` SQL), and they are the handles used in every query (`NavNodes.selectAll()`, `NavNodes.insert { }`, etc.).

#### `DatabaseFactory.init()`

```kotlin
val database = Database.connect(jdbcURL, driverClassName, user, password)

transaction(database) {
    SchemaUtils.create(MapLocations, NavNodes, NavEdges, Waypoints, Products)

    if (Products.selectAll().empty()) {
        // insert 8 seeded product rows
    }
}
```

- `Database.connect(...)` — opens a JDBC connection pool to NeonDB. Does not execute any SQL yet.
- `SchemaUtils.create(...)` — Exposed inspects each Table object and generates `CREATE TABLE IF NOT EXISTS ...` SQL for each. On first deployment all 5 tables are created; on subsequent startups the `IF NOT EXISTS` clause means it is a no-op.
- The `if (Products.selectAll().empty())` block seeds the product catalogue on the very first run. It inserts 8 rows of real product data (robot vacuums, phones, appliances, etc.) with UUIDs as IDs that match barcodes the app can scan.

#### `transaction { }` Block

Every database operation in Exposed must be inside a `transaction { }` block. This maps to a SQL transaction — all statements inside either all succeed (and commit), or all fail together (and roll back). For the map upload route this means: if inserting node 47 out of 200 throws an exception, the entire partial map is rolled back and nothing is left in the database.

#### Writing (POST /maps/upload)

```kotlin
transaction {
    MapLocations.insert {
        it[id]            = mapLocation.id
        it[name]          = mapLocation.name
        it[cloudAnchorId] = mapLocation.cloudAnchorId
    }
    mapLocation.navGraph.nodes.forEach { node ->
        NavNodes.insert {
            it[id]    = node.id
            it[mapId] = mapLocation.id
            it[x]     = node.position.x
            it[y]     = node.position.y
            it[z]     = node.position.z
        }
    }
    mapLocation.navGraph.edges.forEach { edge ->
        NavEdges.insert {
            it[mapId]   = mapLocation.id
            it[nodeAId] = edge.nodeAId
            it[nodeBId] = edge.nodeBId
            it[weight]  = edge.weight
        }
    }
    mapLocation.navGraph.waypoints.forEach { wp ->
        Waypoints.insert {
            it[id]    = wp.id
            it[mapId] = mapLocation.id
            it[name]  = wp.name
            it[x]     = wp.position.x
            it[y]     = wp.position.y
            it[z]     = wp.position.z
        }
    }
}
```

A single surveyed map might have 50–200 `NavNode` rows, 49–199 `NavEdge` rows, and a handful of `Waypoint` rows — all inserted in one atomic transaction.

#### Reading (GET /maps/{id})

```kotlin
val nodes = NavNodes.selectAll()
    .where { NavNodes.mapId eq id }
    .map { row ->
        NavNode(
            id       = row[NavNodes.id],
            position = Vector3(row[NavNodes.x], row[NavNodes.y], row[NavNodes.z])
        )
    }
```

`NavNodes.selectAll().where { NavNodes.mapId eq id }` compiles to `SELECT * FROM nav_nodes WHERE map_id = ?` with `id` as a prepared statement parameter (SQL injection-safe). The result set is mapped row-by-row into `NavNode` data class instances. The same pattern is repeated for edges and waypoints, then all three lists are assembled into a `NavGraph` and returned inside a `MapLocation`.

---

## Android: ARCore Pose Pipeline

### Quaternion → Euler Conversion

File: `app/src/main/java/com/example/indoorar/ar/ARCoreSessionManager.kt`

ARCore returns rotation as a **quaternion** `(qx, qy, qz, qw)` — a 4D representation of orientation with no gimbal lock. Navigation and the mini-map need **yaw** (the horizontal heading angle) as a plain degree value. `ARCoreSessionManager.updatePose()` does this conversion manually using the standard aerospace Euler decomposition formulas:

```kotlin
val roll  = atan2(2*(qw*qx + qy*qz), 1 - 2*(qx*qx + qy*qy))
val pitch = 2*atan2(sqrt(1 + 2*(qw*qy - qx*qz)), sqrt(1 - 2*(qw*qy - qx*qz))) - PI/2
val yaw   = atan2(2*(qw*qz + qx*qy), 1 - 2*(qy*qy + qz*qz))
```

Yaw is stored **negated** (`rotation = Vector3(pitch, -yaw, roll)`) because ARCore's yaw convention is opposite to the heading convention used in `NavigationEngine` and the mini-map arrow.

### Software 3D Projection

File: `app/src/main/java/com/example/indoorar/ar/CameraProjection.kt`

`CameraProjection.projectToScreen()` projects a 3D world coordinate into 2D screen pixels. This is used to draw AR breadcrumb dots at world positions on top of the camera view.

Steps:
1. **Translate** — compute vector from camera position to the world point: `(dx, dy, dz)`
2. **Rotate** — apply the camera's yaw-only rotation matrix to get the point in camera space: `rx`, `ry`, `rz`
3. **Depth check** — if `rz < 0.1` (behind or too close to camera) mark as not visible and skip
4. **Perspective divide** — `projectedX = (rx / rz) * focalLength / aspectRatio`, `projectedY = (ry / rz) * focalLength`. This is the pinhole camera model: objects further away (`rz` larger) appear smaller. `focalLength = 1 / tan(FOV/2)` with FOV = 60°.
5. **NDC → screen pixels** — map from `[-1, 1]` normalised device coordinates to `[0, screenWidth] × [0, screenHeight]`, inverting Y because screen Y increases downward.

### Navigation Engine

File: `app/src/main/java/com/example/indoorar/domain/NavigationEngine.kt`

`calculateNavigationState()` takes the current `Pose`, the destination `Waypoint`, and an optional A\* path, and returns a `NavigationState`:

```kotlin
data class NavigationState(
    val destination: Waypoint?,
    val distanceMeters: Float,
    val angleDegreesToTurn: Float,
    val isReached: Boolean,
    val pathPoints: List<Vector3>
)
```

- **Distance** — direct 3D Euclidean distance from current position to destination
- **Turn angle** — `atan2(dz, dx)` gives the absolute compass bearing to the destination; subtracting the current yaw gives the relative turn angle. Clamped to `[-180, +180]`.
- **Arrival** — `distance <= 1.0f` metres
- **Path points** — if A\* returned a path, those nodes are used as-is. Otherwise falls back to a straight two-point line `[currentPos, destinationPos]`.

### Mini-Map Canvas Rendering

The mini-map is a full-screen Compose `Canvas` overlay. Rendering steps each frame:

1. **Collect all path point coordinates** `(x, z)` — Y is ignored (vertical, not relevant to floor navigation)
2. **Compute bounds** — `minX`, `maxX`, `minZ`, `maxZ` with 2 m padding on each side
3. **Compute uniform scale** — `scale = min(canvasWidth / rangeX, canvasHeight / rangeZ)`. Uniform so the map is not distorted.
4. **Centre offset** — translate so the map is centred in the canvas
5. **Draw path lines** — connect each consecutive pair of path nodes with a line in the scaled coordinate space
6. **Draw user dot** — at current position, scaled the same way
7. **Draw heading arrow** — rotated by `pose.rotation.y` (yaw in degrees) using `canvas.rotate(yaw)`

---

## Android: Barcode Scanning Pipeline

```
ARCore camera frame (ImageProxy)
  └─ throttle: skip if < 2 seconds since last scan
  └─ ML Kit BarcodeScanning.process(image)
      └─ on success: barcodes[0].rawValue → product ID
          └─ NetworkManager.getProduct(id)
              └─ GET /products/{id}
              └─ on 200 OK: deserialise Product JSON → show card overlay
              └─ on error: show hardcoded mock product
```

The throttle (2-second minimum gap) prevents the ML Kit queue from being flooded with frames while a scan is already in flight. `isProcessingFrame` is a Compose state boolean that gates frame submission.

---

## Networking: Ktor Client

File: `app/src/main/java/com/example/indoorar/network/NetworkManager.kt`

```kotlin
object NetworkManager {
    private const val BASE_URL = "https://rayneoarpoc.onrender.com"

    private val client = HttpClient(OkHttp) {
        engine {
            config {
                connectTimeout(60, TimeUnit.SECONDS)
                readTimeout(60, TimeUnit.SECONDS)
                writeTimeout(60, TimeUnit.SECONDS)
            }
        }
        install(ContentNegotiation) {
            json(Json { isLenient = true; ignoreUnknownKeys = true })
        }
    }
}
```

- **`OkHttp` engine** — Ktor Client is engine-agnostic; OkHttp is used here because it is mature, widely used on Android, and supports HTTP/2
- **60-second timeouts** — necessary because Render's free tier **cold-starts** the server after inactivity (~30 s). Without this the request would time out before the server is ready.
- **`ignoreUnknownKeys = true`** — if the server adds a new field in future, old app versions won't crash deserialising the response
- **`isLenient = true`** — tolerates minor JSON quirks

The `NetworkManager` object is a Kotlin singleton. Its `HttpClient` is shared across all requests (connection pooling, thread safety handled by OkHttp internally).

---

## Build System

The project uses **Gradle with Kotlin DSL** (`build.gradle.kts` files). All dependency versions are centralised in `gradle/libs.versions.toml` (Gradle's Version Catalog feature). Key versions:

| Library | Version |
|---|---|
| Kotlin | 2.3.20 |
| Ktor | 3.1.2 |
| Exposed | 0.60.0 |
| PostgreSQL JDBC | 42.7.5 |
| Compose BOM | 2026.03.01 |
| SceneView / ARCore | 2.1.1 |
| ML Kit Barcode | 17.3.0 |
| Coil | 2.6.0 |

The three Gradle modules (`:app`, `:server`, `:shared`) are included in `settings.gradle.kts`. `:app` depends on `:shared` (gets the Android `.aar`). `:server` depends on `:shared` (gets the JVM `.jar`).
