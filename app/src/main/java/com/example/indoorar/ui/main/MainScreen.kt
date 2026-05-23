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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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

enum class AppMode { SURVEY, NAVIGATION }

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
    var isDownloading by remember { mutableStateOf(false) }
    
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var showBottomSheet by remember { mutableStateOf(false) }

    var currentMode by remember { mutableStateOf(AppMode.NAVIGATION) }

    var targetedPosition by remember { mutableStateOf<com.example.indoorar.shared.models.Vector3?>(null) }
    
    var isProcessingFrame by remember { mutableStateOf(false) }
    var scannedProduct by remember { mutableStateOf<com.example.indoorar.shared.models.Product?>(null) }
    var lastScannedTime by remember { mutableStateOf(0L) }
    val barcodeScanner = remember { com.google.mlkit.vision.barcode.BarcodeScanning.getClient() }

    LaunchedEffect(Unit) {
        waypoints.addAll(repository.loadWaypoints())
    }

    // Auto-record walkable path while in SURVEY mode
    LaunchedEffect(currentMode) {
        if (currentMode == AppMode.SURVEY) {
            while (true) {
                val pose = currentPose
                if (pose != null) {
                    val pos = pose.position
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
                    if (!isProcessingFrame && System.currentTimeMillis() - lastScannedTime > 2000L) {
                        try {
                            val image = frame.acquireCameraImage()
                            isProcessingFrame = true
                            val inputImage = com.google.mlkit.vision.common.InputImage.fromMediaImage(
                                image,
                                0 // upright orientation
                            )
                            barcodeScanner.process(inputImage)
                                .addOnSuccessListener { barcodes ->
                                    val barcode = barcodes.firstOrNull()?.rawValue
                                    if (barcode != null) {
                                        lastScannedTime = System.currentTimeMillis()
                                        scope.launch {
                                            val product = com.example.indoorar.network.NetworkManager.getProduct(barcode)
                                            if (product != null) {
                                                scannedProduct = product
                                            }
                                        }
                                    }
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
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(20.dp)
                    .background(if (targetedPosition != null) Color.Green.copy(alpha = 0.5f) else Color.Red.copy(alpha = 0.5f), androidx.compose.foundation.shape.CircleShape)
            )

            // Overlay for projecting waypoints
            val poseForOverlay = currentPose
            if (poseForOverlay != null && screenSize.width > 0 && screenSize.height > 0) {
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
                                .background(
                                    if (waypoint == activeDestination) Color.Green.copy(alpha = 0.8f) else Color.Black.copy(alpha = 0.7f), 
                                    RoundedCornerShape(8.dp)
                                )
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
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.Center,
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
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text("Survey Area")
                    }
                    Button(
                        onClick = { currentMode = AppMode.NAVIGATION },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = if (currentMode == AppMode.NAVIGATION) Color.Cyan.copy(alpha = 0.8f) else Color.Transparent,
                            contentColor = if (currentMode == AppMode.NAVIGATION) Color.Black else Color.White
                        ),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text("Navigate")
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
                }

                // Top: Controls & Nav Info
                if (navState?.destination != null && !navState!!.isReached) {
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
                                                        
                                                        val success = com.example.indoorar.network.NetworkManager.uploadMap(mapLocation)
                                                        if (success) {
                                                            android.widget.Toast.makeText(context, "Survey Uploaded Successfully!", android.widget.Toast.LENGTH_SHORT).show()
                                                            currentMode = AppMode.NAVIGATION
                                                            showBottomSheet = false
                                                            // Clear survey state for next time
                                                            walkableNodes.clear()
                                                            walkableEdges.clear()
                                                        } else {
                                                            android.widget.Toast.makeText(context, "Upload Failed", android.widget.Toast.LENGTH_SHORT).show()
                                                        }
                                                    } finally {
                                                        isUploading = false
                                                    }
                                                }
                                            },
                                            enabled = !isUploading && waypoints.isNotEmpty()
                                        ) {
                                            Text(if (isUploading) "Uploading Survey..." else "Complete Survey & Upload")
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
                                        y = pos.y - 1.5f, // Assume floor is 1.5m below camera
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
            
            // Scanned Product Overlay
            scannedProduct?.let { product ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .background(Color.White.copy(alpha = 0.9f), RoundedCornerShape(16.dp))
                            .padding(16.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Product Found!", fontWeight = FontWeight.Bold, color = Color.Green, fontSize = 18.sp)
                            androidx.compose.material3.IconButton(onClick = { scannedProduct = null }) {
                                Text("X", fontWeight = FontWeight.Bold, color = Color.Black)
                            }
                        }
                        
                        Text(product.title, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 2, color = Color.Black)
                        if (!product.description.isNullOrBlank()) {
                            Text(product.description!!, fontSize = 14.sp, color = Color.DarkGray, maxLines = 3, modifier = Modifier.padding(top = 4.dp))
                        }
                        
                        Row(modifier = Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (product.discountedPrice != null) {
                                Text("₹${product.discountedPrice}", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color.Black)
                                Spacer(modifier = Modifier.size(8.dp))
                                Text("₹${product.price}", textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough, color = Color.Gray)
                            } else {
                                Text("₹${product.price}", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color.Black)
                            }
                            if (product.discount != null) {
                                Spacer(modifier = Modifier.size(8.dp))
                                Text("${product.discount}% OFF", color = Color.Red, fontWeight = FontWeight.Bold)
                            }
                        }
                        
                        Spacer(modifier = Modifier.size(16.dp))
                        
                        Button(
                            onClick = {
                                if (!product.url.isNullOrBlank()) {
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(product.url))
                                    context.startActivity(intent)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("View Product")
                        }
                    }
                }
            }
        }
    }
}
