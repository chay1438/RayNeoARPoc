package com.example.indoorar.ui.main

import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.animation.core.animateFloat
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon

import androidx.compose.foundation.border
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import com.example.indoorar.ar.CameraProjection
import com.example.indoorar.ar.MockARSessionManager
import com.example.indoorar.data.WaypointRepository
import com.example.indoorar.domain.NavigationEngine
import com.example.indoorar.shared.models.Vector3
import com.example.indoorar.shared.models.Waypoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

enum class AppMode { SURVEY, NAVIGATION, BARCODE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(repository: WaypointRepository, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            hasCameraPermission = isGranted
        }
    )

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (hasCameraPermission) {
        ARSessionScreen(repository = repository, modifier = modifier)
    } else {
        Column(
            modifier = modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Camera permission is required for AR Navigation.")
            Button(
                onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                modifier = Modifier.padding(top = 16.dp)
            ) {
                Text("Request Permission")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ARSessionScreen(repository: WaypointRepository, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val arSessionManager = remember { com.example.indoorar.ar.ARCoreSessionManager() }
    val navigationEngine = remember { NavigationEngine() }
    
    val currentPose by arSessionManager.currentPose.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    
    val waypoints = remember { mutableStateListOf<Waypoint>() }
    val walkableNodes = remember { mutableStateListOf<com.example.indoorar.shared.models.NavNode>() }
    val walkableEdges = remember { mutableStateListOf<com.example.indoorar.shared.models.NavEdge>() }
    var screenSize by remember { mutableStateOf(IntSize.Zero) }
    var activeDestination by remember { mutableStateOf<Waypoint?>(null) }
    var activeMap by remember { mutableStateOf<com.example.indoorar.shared.models.MapLocation?>(null) }
    
    var isUploading by remember { mutableStateOf(false) }
    var uploadStatusText by remember { mutableStateOf("") }   // live status shown on the button
    var isDownloading by remember { mutableStateOf(false) }
    
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var showBottomSheet by remember { mutableStateOf(false) }

    var currentMode by remember { mutableStateOf(AppMode.NAVIGATION) }

    var targetedPosition by remember { mutableStateOf<com.example.indoorar.shared.models.Vector3?>(null) }
    
    var isProcessingFrame by remember { mutableStateOf(false) }
    var isFetchingProduct by remember { mutableStateOf(false) }
    var scannedProduct by remember { mutableStateOf<com.example.indoorar.shared.models.Product?>(null) }
    var lastScannedTime by remember { mutableStateOf(0L) }
    val barcodeScanner = remember { com.google.mlkit.vision.barcode.BarcodeScanning.getClient() }

    // Survey Mode — live environment detection state
    // floorY: Y coordinate of the nearest tracked floor plane (used to snap nodes/waypoints to ground level)
    var floorY by remember { mutableStateOf<Float?>(null) }
    var trackedFloorCount by remember { mutableStateOf(0) }
    var trackedWallCount by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        waypoints.addAll(repository.loadWaypoints())
    }

    // Auto-record walkable path while in SURVEY mode
    LaunchedEffect(currentMode) {
        if (currentMode == AppMode.SURVEY) {
            while (true) {
                val pose = currentPose
                if (pose != null) {
                    val rawPos = pose.position
                    // Snap Y to the nearest tracked floor plane so every walkable node sits
                    // exactly on the ground surface instead of at camera / chest height.
                    // This makes distance calculations and mini-map rendering accurate.
                    val pos = com.example.indoorar.shared.models.Vector3(
                        x = rawPos.x,
                        y = floorY ?: (rawPos.y - 1.5f),
                        z = rawPos.z
                    )
                    val lastNode = walkableNodes.lastOrNull()
                    if (lastNode == null || pos.distanceTo(lastNode.position) >= 1.0f) {
                        val newNode = com.example.indoorar.shared.models.NavNode(
                            id = "walkable_${java.util.UUID.randomUUID().toString()}",
                            position = pos
                        )
                        walkableNodes.add(newNode)
                        if (lastNode != null) {
                            walkableEdges.add(
                                com.example.indoorar.shared.models.NavEdge(
                                    nodeAId = lastNode.id,
                                    nodeBId = newNode.id,
                                    weight = pos.distanceTo(lastNode.position)
                                )
                            )
                        }
                    }
                }
                delay(500)
            }
        }
    }

    DisposableEffect(arSessionManager) {
        scope.launch {
            arSessionManager.startSession()
        }
        onDispose {
            arSessionManager.stopSession()
        }
    }

    val aStarPathfinder = remember(activeMap) {
        if (activeMap != null) com.example.indoorar.shared.pathfinding.AStarPathfinder(activeMap!!.navGraph) else null
    }

    val navState by remember(currentPose, activeDestination, activeMap, aStarPathfinder) {
        derivedStateOf {
            val pose = currentPose
            if (pose != null && activeDestination != null) {
                var aStarPath: List<Vector3>? = null
                if (aStarPathfinder != null) {
                    val userPos = Vector3(pose.position.x, pose.position.y, pose.position.z)
                    aStarPath = aStarPathfinder.findPath(userPos, activeDestination!!.position)
                }
                navigationEngine.calculateNavigationState(pose, activeDestination, aStarPath)
            } else null
        }
    }

    // Auto-stop navigation if reached
    LaunchedEffect(navState?.isReached) {
        if (navState?.isReached == true) {
            android.widget.Toast.makeText(context, "Destination Reached!", android.widget.Toast.LENGTH_LONG).show()
            // Play arrival sound
            try {
                val notification = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
                val r = android.media.RingtoneManager.getRingtone(context, notification)
                r.play()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            // Wait a moment before clearing, so user sees "Reached"
            delay(2000)
            activeDestination = null
        }
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showBottomSheet = true }) {
                Text("☰", fontSize = 24.sp, modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .onSizeChanged { screenSize = it }
        ) {
            // ARCore Sceneview - Full screen
            io.github.sceneview.ar.ARScene(
                modifier = Modifier.fillMaxSize(),
                planeRenderer = (currentMode == AppMode.SURVEY),
                onSessionUpdated = { session, frame ->
                    // Update the pose from the ARCore camera frame
                    val camera = frame.camera
                    val arPose = camera.pose
                    
                    arSessionManager.updatePose(
                        tx = arPose.tx(),
                        ty = arPose.ty(),
                        tz = arPose.tz(),
                        qx = arPose.qx(),
                        qy = arPose.qy(),
                        qz = arPose.qz(),
                        qw = arPose.qw()
                    )

                    // ── Survey Mode: environment plane detection ─────────────────────────
                    // ARCore tracks three plane types:
                    //   HORIZONTAL_UPWARD_FACING  → floors / flat surfaces the user walks on
                    //   HORIZONTAL_DOWNWARD_FACING → ceilings (ignored here)
                    //   VERTICAL                  → walls / large vertical surfaces
                    // We use this every frame to:
                    //   1. Count how many floors + walls ARCore has found (HUD quality signal)
                    //   2. Snap recorded NavNode Y and fallback Waypoint Y to real floor level
                    //      instead of relying on a fixed "camera - 1.5 m" estimate
                    if (currentMode == AppMode.SURVEY) {
                        val allPlanes = session.getAllTrackables(com.google.ar.core.Plane::class.java)

                        val floors = allPlanes.filter {
                            it.type == com.google.ar.core.Plane.Type.HORIZONTAL_UPWARD_FACING &&
                            it.trackingState == com.google.ar.core.TrackingState.TRACKING
                        }
                        val walls = allPlanes.filter {
                            it.type == com.google.ar.core.Plane.Type.VERTICAL &&
                            it.trackingState == com.google.ar.core.TrackingState.TRACKING
                        }

                        trackedFloorCount = floors.size
                        trackedWallCount  = walls.size

                        // Find the floor plane whose centre is closest to the camera's XZ position.
                        // Its centre Y is the real ground level at the user's current location.
                        val nearestFloor = floors.minByOrNull { plane ->
                            val c  = plane.centerPose
                            val dx = c.tx() - arPose.tx()
                            val dz = c.tz() - arPose.tz()
                            dx * dx + dz * dz          // squared distance is fine for comparison
                        }
                        // Fallback: if no floor has been detected yet, estimate floor as
                        // 1.5 m below the camera (average eye/chest height when holding a phone).
                        floorY = nearestFloor?.centerPose?.ty() ?: (arPose.ty() - 1.5f)
                    }
                    // ────────────────────────────────────────────────────────────────────

                    // Perform hit testing from the center of the screen to find the floor
                    if (screenSize.width > 0 && screenSize.height > 0) {
                        val hits = frame.hitTest(screenSize.width / 2f, screenSize.height / 2f)
                        val bestHit = hits.firstOrNull { hitResult -> 
                            val trackable = hitResult.trackable
                            trackable is com.google.ar.core.Plane
                        } ?: hits.firstOrNull()
                        
                        if (bestHit != null) {
                            val hitPose = bestHit.hitPose
                            targetedPosition = com.example.indoorar.shared.models.Vector3(
                                x = hitPose.tx(),
                                y = hitPose.ty(),
                                z = hitPose.tz()
                            )
                        } else {
                            targetedPosition = null
                        }
                    }
                    
                    // ML Kit Barcode Scanning
                    if (currentMode == AppMode.BARCODE && !isProcessingFrame && System.currentTimeMillis() - lastScannedTime > 2000L) {
                        try {
                            val image = frame.acquireCameraImage()
                            isProcessingFrame = true
                            val display = (context.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay
                            val rotationDegrees = when (display.rotation) {
                                android.view.Surface.ROTATION_0 -> 90
                                android.view.Surface.ROTATION_90 -> 0
                                android.view.Surface.ROTATION_180 -> 270
                                android.view.Surface.ROTATION_270 -> 180
                                else -> 90
                            }
                            
                            val inputImage = com.google.mlkit.vision.common.InputImage.fromMediaImage(
                                image,
                                rotationDegrees
                            )
                            barcodeScanner.process(inputImage)
                                .addOnSuccessListener { barcodes ->
                                    val barcode = barcodes.firstOrNull()?.rawValue
                                    if (barcode != null) {
                                        lastScannedTime = System.currentTimeMillis()

                                        scope.launch {
                                            isFetchingProduct = true
                                            val product = com.example.indoorar.network.NetworkManager.getProduct(barcode)
                                            isFetchingProduct = false
                                            if (product != null) {
                                                scannedProduct = product
                                            } else {
                                                android.widget.Toast.makeText(context, "Product not found for ID: $barcode", android.widget.Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                }
                                .addOnFailureListener { e ->
                                    android.util.Log.e("BarcodeScanner", "Failed to scan", e)
                                }
                                .addOnCompleteListener {
                                    image.close()
                                    isProcessingFrame = false
                                }
                        } catch (e: Exception) {
                            // Ignored (e.g. NotYetAvailableException)
                            isProcessingFrame = false
                        }
                    }
                }
            )
            
            // Center Crosshair (Reticle)
            if (currentMode != AppMode.BARCODE) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(20.dp)
                        .background(if (targetedPosition != null) Color.Green.copy(alpha = 0.5f) else Color.Red.copy(alpha = 0.5f), androidx.compose.foundation.shape.CircleShape)
                )
            }
            

            // Overlay for projecting waypoints
            val poseForOverlay = currentPose
            if (poseForOverlay != null && screenSize.width > 0 && screenSize.height > 0) {
                val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "bounce")
                val bounceOffset by infiniteTransition.animateFloat(
                    initialValue = -10f,
                    targetValue = 10f,
                    animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                        animation = androidx.compose.animation.core.tween(800, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                        repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
                    ),
                    label = "bounceOffset"
                )

                if (navState != null && activeDestination != null && !navState!!.isReached) {
                    // Draw AR Path Points
                    navState!!.pathPoints.forEachIndexed { index, point ->
                        val isDestination = (index == navState!!.pathPoints.size - 1)
                        val projection = CameraProjection.projectToScreen(
                            worldPoint = point,
                            cameraPose = poseForOverlay,
                            screenWidth = screenSize.width.toFloat(),
                            screenHeight = screenSize.height.toFloat()
                        )
                        
                        if (projection.isVisible) {
                            Box(
                                modifier = Modifier
                                    .offset(
                                        x = projection.x.dp, 
                                        y = (projection.y + bounceOffset).dp
                                    )
                                    .background(Color.White.copy(alpha = 0.8f), RoundedCornerShape(50))
                                    .padding(if (isDestination) 12.dp else 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isDestination) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("🏠", fontSize = 32.sp)
                                        Text(activeDestination!!.name, color = Color.Black, fontWeight = FontWeight.Bold)
                                        Text("${"%.1f".format(projection.depth)}m", color = Color.DarkGray, fontSize = 12.sp)
                                    }
                                } else {
                                    Text("🚶", fontSize = 24.sp)
                                }
                            }
                        }
                    }
                } else {
                    // Draw Regular Waypoints
                    waypoints.forEach { waypoint ->
                        val projection = CameraProjection.projectToScreen(
                            worldPoint = waypoint.position,
                            cameraPose = poseForOverlay,
                            screenWidth = screenSize.width.toFloat(),
                            screenHeight = screenSize.height.toFloat()
                        )

                        if (projection.isVisible) {
                            Box(
                                modifier = Modifier
                                    .offset(
                                        x = projection.x.dp, 
                                        y = projection.y.dp
                                    )
                                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                                    .padding(8.dp)
                            ) {
                                Column {
                                    Text(text = waypoint.name, color = Color.White, style = MaterialTheme.typography.labelLarge)
                                    Text(text = "${"%.1f".format(projection.depth)}m", color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }

            // AR Breadcrumb Path
            val poseForBreadcrumbs = currentPose
            if (navState != null && activeDestination != null && !navState!!.isReached && poseForBreadcrumbs != null && screenSize.width > 0 && screenSize.height > 0) {
                val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "pulse")
                val pulseAlpha by infiniteTransition.animateFloat(
                    initialValue = 0.3f,
                    targetValue = 0.8f,
                    animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                        animation = androidx.compose.animation.core.tween(1000, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                        repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
                    ),
                    label = "pulseAlpha"
                )

                // Mini-Map Overlay
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 180.dp, bottom = 150.dp, start = 32.dp, end = 32.dp)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
                        .padding(24.dp)
                ) {
                    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                        val pathPoints = navState!!.pathPoints
                        if (pathPoints.isEmpty()) return@Canvas
                        
                        var minX = Float.MAX_VALUE
                        var maxX = -Float.MAX_VALUE
                        var minZ = Float.MAX_VALUE
                        var maxZ = -Float.MAX_VALUE
                        
                        for (p in pathPoints) {
                            if (p.x < minX) minX = p.x
                            if (p.x > maxX) maxX = p.x
                            if (p.z < minZ) minZ = p.z
                            if (p.z > maxZ) maxZ = p.z
                        }
                        
                        val padding = 2f
                        minX -= padding; maxX += padding
                        minZ -= padding; maxZ += padding
                        
                        val mapWidth = maxX - minX
                        val mapHeight = maxZ - minZ
                        
                        if (mapWidth <= 0f || mapHeight <= 0f) return@Canvas
                        
                        val scaleX = size.width / mapWidth
                        val scaleY = size.height / mapHeight
                        val scale = kotlin.math.min(scaleX, scaleY)
                        
                        val offsetX = (size.width - (mapWidth * scale)) / 2f
                        val offsetY = (size.height - (mapHeight * scale)) / 2f
                        
                        fun mapTo2D(x: Float, z: Float): androidx.compose.ui.geometry.Offset {
                            val px = (x - minX) * scale + offsetX
                            val py = (z - minZ) * scale + offsetY
                            return androidx.compose.ui.geometry.Offset(px, py)
                        }
                        
                        // Draw Path
                        val path = androidx.compose.ui.graphics.Path()
                        for (i in pathPoints.indices) {
                            val pt = mapTo2D(pathPoints[i].x, pathPoints[i].z)
                            if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
                        }
                        drawPath(
                            path = path,
                            color = Color.Cyan.copy(alpha = pulseAlpha),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 16f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
                        )
                        
                        // Draw User Position and Orientation
                        val userPos2D = mapTo2D(poseForBreadcrumbs.position.x, poseForBreadcrumbs.position.z)
                        drawCircle(color = Color.Blue, radius = 24f, center = userPos2D)
                        drawCircle(color = Color.White, radius = 8f, center = userPos2D)
                        
                        // Calculate User Heading Arrow
                        val yawRad = Math.toRadians(poseForBreadcrumbs.rotation.y.toDouble())
                        val dx = kotlin.math.sin(yawRad).toFloat()
                        val dz = -kotlin.math.cos(yawRad).toFloat()
                        val userDirX = userPos2D.x + dx * 40f
                        val userDirY = userPos2D.y + dz * 40f
                        drawLine(color = Color.Yellow, start = userPos2D, end = androidx.compose.ui.geometry.Offset(userDirX, userDirY), strokeWidth = 12f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                        
                        // Draw Destination
                        val destPos2D = mapTo2D(activeDestination!!.position.x, activeDestination!!.position.z)
                        drawCircle(color = Color.Green, radius = 30f, center = destPos2D)
                    }
                }
            }

            // Arrival Notification
            if (navState?.isReached == true) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.Center)
                        .background(Color.Green.copy(alpha = 0.9f))
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Destination Reached!",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp
                    )
                }
            }

            // HUD & Waypoint Manager
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top
            ) {
                // Mode Toggle
                Row(
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
                        .padding(4.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { 
                            currentMode = AppMode.SURVEY
                            activeDestination = null 
                        },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = if (currentMode == AppMode.SURVEY) Color.Cyan.copy(alpha = 0.8f) else Color.Transparent,
                            contentColor = if (currentMode == AppMode.SURVEY) Color.Black else Color.White
                        ),
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)
                    ) {
                        Text("Survey")
                    }
                    Button(
                        onClick = { currentMode = AppMode.NAVIGATION },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = if (currentMode == AppMode.NAVIGATION) Color.Cyan.copy(alpha = 0.8f) else Color.Transparent,
                            contentColor = if (currentMode == AppMode.NAVIGATION) Color.Black else Color.White
                        ),
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)
                    ) {
                        Text("Navigate")
                    }
                    Button(
                        onClick = { currentMode = AppMode.BARCODE },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = if (currentMode == AppMode.BARCODE) Color.Cyan.copy(alpha = 0.8f) else Color.Transparent,
                            contentColor = if (currentMode == AppMode.BARCODE) Color.Black else Color.White
                        ),
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)
                    ) {
                        Text("Scan")
                    }
                }

                if (currentMode == AppMode.SURVEY) {
                    Text(
                        "Survey Mode Active: Map your surroundings",
                        color = Color.Yellow,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )

                    // ── Environment Detection HUD ────────────────────────────────────────
                    // Shows live counts of ARCore-tracked floor and wall planes so the user
                    // knows how well the environment is understood before walking the path.
                    //   Green  = 2+ floors tracked  (good precision)
                    //   Orange = 1 floor tracked    (limited — keep moving slowly)
                    //   Red    = no floor detected  (poor — point camera at the floor)
                    val envTrackingColor = when {
                        trackedFloorCount >= 2 -> Color.Green
                        trackedFloorCount == 1 -> Color(0xFFFFA500)
                        else                   -> Color.Red
                    }
                    val envTrackingLabel = when {
                        trackedFloorCount >= 2 -> "Good"
                        trackedFloorCount == 1 -> "Limited"
                        else                   -> "Searching…"
                    }

                    Row(
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(14.dp))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Floor planes
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("🟩", fontSize = 15.sp)
                            Text("Floors", color = Color.White, fontSize = 10.sp)
                            Text(
                                "$trackedFloorCount",
                                color = envTrackingColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                        Box(modifier = Modifier
                            .width(1.dp)
                            .height(36.dp)
                            .background(Color.White.copy(alpha = 0.25f)))
                        // Wall planes
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("🟦", fontSize = 15.sp)
                            Text("Walls", color = Color.White, fontSize = 10.sp)
                            Text(
                                "$trackedWallCount",
                                color = Color.Cyan,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                        Box(modifier = Modifier
                            .width(1.dp)
                            .height(36.dp)
                            .background(Color.White.copy(alpha = 0.25f)))
                        // Detected floor Y (actual ground level in ARCore world space)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("📍", fontSize = 15.sp)
                            Text("Floor Y", color = Color.White, fontSize = 10.sp)
                            Text(
                                floorY?.let { "${"%.2f".format(it)}m" } ?: "---",
                                color = envTrackingColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                        Box(modifier = Modifier
                            .width(1.dp)
                            .height(36.dp)
                            .background(Color.White.copy(alpha = 0.25f)))
                        // Node count + overall quality badge
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("🗺️", fontSize = 15.sp)
                            Text("Nodes", color = Color.White, fontSize = 10.sp)
                            Text(
                                "${walkableNodes.size}",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                        Box(modifier = Modifier
                            .width(1.dp)
                            .height(36.dp)
                            .background(Color.White.copy(alpha = 0.25f)))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (trackedFloorCount >= 2) "✅" else if (trackedFloorCount == 1) "⚠️" else "❌", fontSize = 15.sp)
                            Text("Quality", color = Color.White, fontSize = 10.sp)
                            Text(
                                envTrackingLabel,
                                color = envTrackingColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }
                    }
                    // ────────────────────────────────────────────────────────────────────
                }

                if (currentMode == AppMode.BARCODE) {
                    Text(
                        "Barcode Mode Active: Point camera at product barcode", 
                        color = Color.Yellow, 
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                // Top: Controls & Nav Info
                if (currentMode == AppMode.NAVIGATION && navState?.destination != null && !navState!!.isReached) {
                    // Navigation HUD Panel (Glassmorphism look)
                    Column(
                        modifier = Modifier
                            .padding(top = 16.dp)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Navigating to: ${navState!!.destination!!.name}", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("${"%.1f".format(navState!!.distanceMeters)}m remaining", color = Color.Cyan, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            // Hidden Bottom Sheet for Locations
            var showNameDialog by remember { mutableStateOf(false) }
            var newWaypointName by remember { mutableStateOf("") }

            if (showBottomSheet) {
                ModalBottomSheet(
                    onDismissRequest = { showBottomSheet = false },
                    sheetState = sheetState
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(if (currentMode == AppMode.SURVEY) "Survey Tools" else "Navigation Menu", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp))
                        
                        if (currentMode == AppMode.NAVIGATION) {
                            if (waypoints.isEmpty()) {
                                Text("No surroundings loaded.", color = Color.Gray, modifier = Modifier.padding(bottom = 16.dp))
                            } else {
                                waypoints.forEach { wp ->
                                    val isSelected = wp == activeDestination
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp)
                                            .clickable {
                                                activeDestination = if (isSelected) null else wp
                                                if (isSelected) showBottomSheet = false // auto close when selected
                                            }
                                            .background(if (isSelected) Color.Cyan.copy(alpha = 0.2f) else Color.Transparent, RoundedCornerShape(8.dp))
                                            .border(1.dp, if (isSelected) Color.Cyan else Color.LightGray, RoundedCornerShape(8.dp))
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(wp.name, fontWeight = FontWeight.Bold)
                                            Text("${"%.1f".format(wp.position.x)}, ${"%.1f".format(wp.position.z)}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                        }
                                        if (isSelected) {
                                            Text("Navigating", color = Color.Cyan, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        } else {
                            // Survey Mode doesn't show the nav list
                            Text("Drop markers to survey the area and upload to the cloud.", color = Color.Gray, modifier = Modifier.padding(bottom = 16.dp))
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        if (currentPose != null) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 32.dp)) {
                                if (currentMode == AppMode.SURVEY) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        Button(onClick = {
                                            newWaypointName = ""
                                            showNameDialog = true
                                        }) {
                                            Text("Drop Marker Here")
                                        }
                                        
                                        Button(
                                            onClick = { 
                                                waypoints.clear() 
                                                activeDestination = null
                                                activeMap = null
                                                repository.saveWaypoints(waypoints)
                                            },
                                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.8f))
                                        ) {
                                            Text("Clear Survey")
                                        }
                                    }
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    if (currentMode == AppMode.SURVEY) {
                                        Button(
                                            onClick = {
                                                isUploading = true
                                                scope.launch {
                                                    try {
                                                        // Construct final graph with Walkable Paths and Objects
                                                        val allNodes = mutableListOf<com.example.indoorar.shared.models.NavNode>()
                                                        allNodes.addAll(walkableNodes)
                                                        val objectNodes = waypoints.map { com.example.indoorar.shared.models.NavNode(it.id, it.position) }
                                                        allNodes.addAll(objectNodes)
                                                        
                                                        val allEdges = mutableListOf<com.example.indoorar.shared.models.NavEdge>()
                                                        allEdges.addAll(walkableEdges)
                                                        
                                                        // Connect each object to its nearest walkable node
                                                        if (walkableNodes.isNotEmpty()) {
                                                            for (obj in objectNodes) {
                                                                val nearestWalkable = walkableNodes.minByOrNull { it.position.distanceTo(obj.position) }
                                                                if (nearestWalkable != null) {
                                                                    allEdges.add(com.example.indoorar.shared.models.NavEdge(
                                                                        nodeAId = nearestWalkable.id,
                                                                        nodeBId = obj.id,
                                                                        weight = nearestWalkable.position.distanceTo(obj.position)
                                                                    ))
                                                                    // Bidirectional edge for returning
                                                                    allEdges.add(com.example.indoorar.shared.models.NavEdge(
                                                                        nodeAId = obj.id,
                                                                        nodeBId = nearestWalkable.id,
                                                                        weight = obj.position.distanceTo(nearestWalkable.position)
                                                                    ))
                                                                }
                                                            }
                                                        } else {
                                                            // Fallback: connect objects linearly if no walkable nodes exist
                                                            for (i in 0 until objectNodes.size - 1) {
                                                                allEdges.add(com.example.indoorar.shared.models.NavEdge(objectNodes[i].id, objectNodes[i+1].id, objectNodes[i].position.distanceTo(objectNodes[i+1].position)))
                                                                allEdges.add(com.example.indoorar.shared.models.NavEdge(objectNodes[i+1].id, objectNodes[i].id, objectNodes[i+1].position.distanceTo(objectNodes[i].position)))
                                                            }
                                                        }
                                                        
                                                        val mapLocation = com.example.indoorar.shared.models.MapLocation(
                                                            id = java.util.UUID.randomUUID().toString(),
                                                            name = "Saved Map ${System.currentTimeMillis()}",
                                                            cloudAnchorId = "test_anchor",
                                                            navGraph = com.example.indoorar.shared.models.NavGraph(allNodes, allEdges, waypoints.toList())
                                                        )
                                                        
                                                        // ── Upload with auto-retry ─────────────────────────────────
                                                        // Render free-tier servers sleep after 15 min of inactivity
                                                        // and take ~30–60 s to cold-start.  NeonDB serverless also
                                                        // has a connection warm-up delay.  A single attempt often
                                                        // loses a race against these combined cold-starts, which is
                                                        // why the old code showed "Upload Failed" every time.
                                                        // We now retry up to 3 times with increasing back-off so
                                                        // the server has time to fully wake between tries.
                                                        val maxAttempts = 3
                                                        var lastError: String? = null
                                                        var uploaded = false

                                                        for (attempt in 1..maxAttempts) {
                                                            uploadStatusText = if (attempt == 1)
                                                                "Connecting to server…"
                                                            else
                                                                "Retry $attempt/$maxAttempts…"

                                                            val result = com.example.indoorar.network.NetworkManager.uploadMap(mapLocation)

                                                            if (result.isSuccess) {
                                                                uploaded = true
                                                                break
                                                            }

                                                            lastError = result.exceptionOrNull()?.message ?: "Unknown error"
                                                            android.util.Log.e("IndoorAR", "Upload attempt $attempt failed: $lastError")

                                                            if (attempt < maxAttempts) {
                                                                // 5 s after 1st fail, 10 s after 2nd — enough for
                                                                // Render + NeonDB to finish their cold-start
                                                                uploadStatusText = "Attempt $attempt failed — retrying in ${attempt * 5}s…"
                                                                delay(attempt * 5_000L)
                                                            }
                                                        }

                                                        if (uploaded) {
                                                            android.widget.Toast.makeText(context, "Survey Uploaded Successfully!", android.widget.Toast.LENGTH_SHORT).show()
                                                            currentMode = AppMode.NAVIGATION
                                                            showBottomSheet = false
                                                            walkableNodes.clear()
                                                            walkableEdges.clear()
                                                        } else {
                                                            // Show the real failure reason so the user knows what went wrong
                                                            android.widget.Toast.makeText(
                                                                context,
                                                                "Upload Failed after $maxAttempts attempts:\n$lastError",
                                                                android.widget.Toast.LENGTH_LONG
                                                            ).show()
                                                        }
                                                        uploadStatusText = ""
                                                        // ─────────────────────────────────────────────────────────
                                                    } finally {
                                                        isUploading = false
                                                    }
                                                }
                                            },
                                            enabled = !isUploading && waypoints.isNotEmpty()
                                        ) {
                                            Text(
                                                when {
                                                    isUploading && uploadStatusText.isNotBlank() -> uploadStatusText
                                                    isUploading -> "Uploading…"
                                                    else -> "Complete Survey & Upload"
                                                }
                                            )
                                        }
                                    } else {
                                        Button(
                                            onClick = {
                                                isDownloading = true
                                                scope.launch {
                                                    try {
                                                        val maps = com.example.indoorar.network.NetworkManager.getMaps()
                                                        if (maps.isNotEmpty()) {
                                                            // Just download the first map for POC
                                                            val map = com.example.indoorar.network.NetworkManager.downloadMap(maps.first().id)
                                                            if (map != null) {
                                                                activeMap = map
                                                                waypoints.clear()
                                                                waypoints.addAll(map.navGraph.waypoints)
                                                                android.widget.Toast.makeText(context, "Surroundings Loaded!", android.widget.Toast.LENGTH_SHORT).show()
                                                            }
                                                        } else {
                                                            android.widget.Toast.makeText(context, "No surroundings found on server", android.widget.Toast.LENGTH_SHORT).show()
                                                        }
                                                    } finally {
                                                        isDownloading = false
                                                    }
                                                }
                                            },
                                            enabled = !isDownloading
                                        ) {
                                            Text(if (isDownloading) "Loading..." else "Load Surroundings")
                                        }
                                    }
                                }
                            }
                        } else {
                            Text("Waiting for AR tracking...", modifier = Modifier.padding(bottom = 32.dp))
                        }
                    }
                }
            }

            if (showNameDialog && currentPose != null) {
                val poseForDialog = currentPose!!
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showNameDialog = false },
                    title = { Text("Name this Location") },
                    text = {
                        androidx.compose.material3.OutlinedTextField(
                            value = newWaypointName,
                            onValueChange = { newWaypointName = it },
                            label = { Text("e.g. Kitchen, TV, Keys") },
                            singleLine = true
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val nameToSave = newWaypointName.ifBlank { "Marker ${waypoints.size + 1}" }
                                
                                // Use the precise floor targeted position, or fallback to 2m ahead
                                val newPos = targetedPosition ?: run {
                                    val pos = poseForDialog.position
                                    val yawRad = Math.toRadians(poseForDialog.rotation.y.toDouble()).toFloat()
                                    val dx = -kotlin.math.sin(yawRad) * 2f
                                    val dz = -kotlin.math.cos(yawRad) * 2f
                                    com.example.indoorar.shared.models.Vector3(
                                        x = pos.x + dx,
                                        y = floorY ?: (pos.y - 1.5f), // Use detected floor plane Y, or estimate if not yet found
                                        z = pos.z + dz
                                    )
                                }
                                
                                waypoints.add(
                                    Waypoint(
                                        id = UUID.randomUUID().toString(),
                                        name = nameToSave,
                                        position = newPos
                                    )
                                )
                                repository.saveWaypoints(waypoints)
                                showNameDialog = false
                                newWaypointName = ""
                            }
                        ) {
                            Text("Save Pin")
                        }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { showNameDialog = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }
            
            // Scanned Product Overlay (2D Centered Card)
            if (scannedProduct != null) {
                val product = scannedProduct!!
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (!product.url.isNullOrBlank()) {
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(product.url))
                                    context.startActivity(intent)
                                }
                            },
                        shape = RoundedCornerShape(16.dp),
                        elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 8.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.White)
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Product Found", color = Color.Green, fontWeight = FontWeight.Bold)
                                androidx.compose.material3.IconButton(
                                    onClick = { scannedProduct = null },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Text("✕", color = Color.Black, fontWeight = FontWeight.Bold)
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            if (!product.imageUrl.isNullOrBlank()) {
                                coil.compose.AsyncImage(
                                    model = product.imageUrl,
                                    contentDescription = "Product Image",
                                    modifier = Modifier
                                        .size(180.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                            
                            Text(
                                text = product.title,
                                color = Color.Black,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                maxLines = 2,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val displayPrice = product.discountedPrice ?: product.price
                                Text("₹$displayPrice", color = Color.Black, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                                
                                if (product.discountedPrice != null) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("₹${product.price}", color = Color.Gray, textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("${product.discount}% OFF", color = Color.Red, fontWeight = FontWeight.Bold)
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            Button(
                                onClick = {
                                    if (!product.url.isNullOrBlank()) {
                                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(product.url))
                                        context.startActivity(intent)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("View on Store")
                            }
                        }
                    }
                }
            }
        }
    }
}
