package com.example.nexusmap

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Paint
import android.location.LocationManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

// ── Colors ──
private val CyanColor = Color(0xFF00D4FF)
private val CyanDim = Color(0x1A00D4FF)
private val PanelBg = Color(0xF0060614)
private val BorderColor = Color(0x4000D4FF)
private val MutedColor = Color(0xFF64748B)
private val TrafficBlue = Color(0xFF3B82F6)
private val TrafficOrange = Color(0xFFF97316)
private val TrafficRed = Color(0xFFEF4444)
private val GreenColor = Color(0xFF00FF88)

// ── Tile Sources ──
private fun esriSatellite() = object : OnlineTileSourceBase(
    "ESRI_Satellite", 0, 19, 256, "",
    arrayOf("https://server.arcgisonline.com")
) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        "$baseUrl/ArcGIS/rest/services/World_Imagery/MapServer/tile/" +
        "${MapTileIndex.getZoom(pMapTileIndex)}/" +
        "${MapTileIndex.getY(pMapTileIndex)}/" +
        "${MapTileIndex.getX(pMapTileIndex)}"
}

private fun cartoDark() = object : OnlineTileSourceBase(
    "CartoDark", 0, 19, 256, ".png",
    arrayOf("https://a.basemaps.cartocdn.com", "https://b.basemaps.cartocdn.com",
            "https://c.basemaps.cartocdn.com", "https://d.basemaps.cartocdn.com")
) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        "${baseUrl.trimEnd('/')}/dark_all/${MapTileIndex.getZoom(pMapTileIndex)}/${MapTileIndex.getX(pMapTileIndex)}/${MapTileIndex.getY(pMapTileIndex)}.png"
}

private fun cartoLight() = object : OnlineTileSourceBase(
    "CartoLight", 0, 19, 256, ".png",
    arrayOf("https://a.basemaps.cartocdn.com", "https://b.basemaps.cartocdn.com",
            "https://c.basemaps.cartocdn.com", "https://d.basemaps.cartocdn.com")
) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        "${baseUrl.trimEnd('/')}/light_all/${MapTileIndex.getZoom(pMapTileIndex)}/${MapTileIndex.getX(pMapTileIndex)}/${MapTileIndex.getY(pMapTileIndex)}.png"
}

@Composable
fun MapScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // State
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var locationOverlay by remember { mutableStateOf<MyLocationNewOverlay?>(null) }
    val routeOverlays = remember { mutableListOf<org.osmdroid.views.overlay.Overlay>() }
    var currentLayer by remember { mutableStateOf("normal") }
    var showLayerPanel by remember { mutableStateOf(false) }
    var showDirections by remember { mutableStateOf(false) }
    var showTrafficModal by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var fromText by remember { mutableStateOf("") }
    var toText by remember { mutableStateOf("") }
    var routeInfo by remember { mutableStateOf<RouteResult?>(null) }
    var isLoadingRoute by remember { mutableStateOf(false) }
    var trafficEnabled by remember { mutableStateOf(false) }
    var tomtomKey by remember { mutableStateOf(
        context.getSharedPreferences("nexusmap_prefs", Context.MODE_PRIVATE)
            .getString("tt_key", "") ?: ""
    ) }
    var ttKeyInput by remember { mutableStateOf("") }
    var toastMsg by remember { mutableStateOf("") }
    var toastVisible by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var zoomLevel by remember { mutableStateOf(13) }

    fun showToast(msg: String) {
        toastMsg = msg
        toastVisible = true
        scope.launch {
            delay(2200)
            toastVisible = false
        }
    }

    fun clearRoute(mv: MapView) {
        routeOverlays.forEach { mv.overlays.remove(it) }
        routeOverlays.clear()
        mv.invalidate()
    }

    fun drawRoute(mv: MapView, result: RouteResult) {
        clearRoute(mv)
        result.segments.forEach { seg ->
            val line = Polyline(mv).apply {
                setPoints(seg.points)
                val androidColor = when (seg.level) {
                    TrafficLevel.NORMAL -> android.graphics.Color.rgb(59, 130, 246)
                    TrafficLevel.MEDIUM -> android.graphics.Color.rgb(249, 115, 22)
                    TrafficLevel.HEAVY -> android.graphics.Color.rgb(239, 68, 68)
                }
                outlinePaint.color = androidColor
                outlinePaint.strokeWidth = 16f
                outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.strokeJoin = Paint.Join.ROUND
            }
            val shadow = Polyline(mv).apply {
                setPoints(seg.points)
                outlinePaint.color = android.graphics.Color.argb(80, 0, 0, 0)
                outlinePaint.strokeWidth = 20f
                outlinePaint.strokeCap = Paint.Cap.ROUND
            }
            routeOverlays.add(shadow)
            routeOverlays.add(line)
            mv.overlays.add(shadow)
            mv.overlays.add(line)
        }

        // Start marker
        if (result.segments.isNotEmpty()) {
            val startPt = result.segments.first().points.firstOrNull()
            val endPt = result.segments.last().points.lastOrNull()
            startPt?.let {
                val m = Marker(mv).apply {
                    position = it
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    icon = context.getDrawable(android.R.drawable.ic_menu_mylocation)
                    title = "Start"
                }
                routeOverlays.add(m)
                mv.overlays.add(m)
            }
            endPt?.let {
                val m = Marker(mv).apply {
                    position = it
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    icon = context.getDrawable(android.R.drawable.ic_menu_mapmode)
                    title = "Destination"
                }
                routeOverlays.add(m)
                mv.overlays.add(m)
            }
        }
        mv.invalidate()
    }

    // Location permission
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val granted = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                      perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            mapView?.let { mv ->
                val overlay = MyLocationNewOverlay(GpsMyLocationProvider(context), mv)
                overlay.enableMyLocation()
                overlay.enableFollowLocation()
                mv.overlays.add(overlay)
                locationOverlay = overlay
                mv.invalidate()
            }
        }
    }

    fun requestLocation() {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED) {
            mapView?.let { mv ->
                if (locationOverlay == null) {
                    val overlay = MyLocationNewOverlay(GpsMyLocationProvider(context), mv)
                    overlay.enableMyLocation()
                    mv.overlays.add(overlay)
                    locationOverlay = overlay
                }
                locationOverlay?.myLocation?.let { loc ->
                    mv.controller.animateTo(loc, 16.0, 1200L)
                    fromText = "${loc.latitude.toFloat().let { "%.5f".format(it) }}, ${loc.longitude.toFloat().let { "%.5f".format(it) }}"
                    showToast("LOCATION FOUND")
                } ?: showToast("ACQUIRING LOCATION…")
                mv.invalidate()
            }
        } else {
            permLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
    }

    fun setLayer(name: String, mv: MapView) {
        when (name) {
            "normal" -> mv.setTileSource(TileSourceFactory.MAPNIK)
            "satellite" -> mv.setTileSource(esriSatellite())
            "white" -> mv.setTileSource(cartoLight())
            "dark" -> mv.setTileSource(cartoDark())
        }
        currentLayer = name
        showLayerPanel = false
        mv.invalidate()
        showToast(name.uppercase() + " MODE")
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // ── MAP ──
        AndroidView(
            factory = { ctx ->
                MapView(ctx).also { mv ->
                    mv.setTileSource(TileSourceFactory.MAPNIK)
                    mv.setMultiTouchControls(true)
                    mv.controller.setZoom(13.0)
                    mv.controller.setCenter(GeoPoint(51.505, -0.09))
                    mv.setBuiltInZoomControls(false)
                    mv.addMapListener(object : org.osmdroid.events.MapListener {
                        override fun onScroll(event: org.osmdroid.events.ScrollEvent?): Boolean = false
                        override fun onZoom(event: org.osmdroid.events.ZoomEvent?): Boolean {
                            zoomLevel = mv.zoomLevel.toInt()
                            return false
                        }
                    })
                    mapView = mv
                    // Request location on map ready
                    scope.launch {
                        delay(500)
                        requestLocation()
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // ── SEARCH BAR + LOGO ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "NX/MAP",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 13.sp,
                letterSpacing = 3.sp,
                color = CyanColor
            )
            Box(modifier = Modifier.weight(1f)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { q ->
                        searchQuery = q
                        searchJob?.cancel()
                        if (q.length >= 3) {
                            searchJob = scope.launch {
                                delay(400)
                                searchResults = RouteService.search(q)
                            }
                        } else { searchResults = emptyList() }
                    },
                    placeholder = { Text("Search place…", color = MutedColor, fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        searchJob?.cancel()
                        scope.launch { searchResults = RouteService.search(searchQuery) }
                    }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanColor,
                        unfocusedBorderColor = BorderColor,
                        focusedContainerColor = PanelBg,
                        unfocusedContainerColor = PanelBg,
                        cursorColor = CyanColor,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    textStyle = TextStyle(fontSize = 14.sp, fontFamily = FontFamily.Default),
                    shape = RoundedCornerShape(4.dp)
                )
                // Search results dropdown
                if (searchResults.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .offset(y = 52.dp)
                            .background(PanelBg, RoundedCornerShape(4.dp))
                            .clip(RoundedCornerShape(4.dp))
                    ) {
                        searchResults.take(5).forEach { result ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        mapView?.let { mv ->
                                            val pt = GeoPoint(result.lat, result.lon)
                                            mv.controller.animateTo(pt, 15.0, 1200L)
                                            val marker = Marker(mv).apply {
                                                position = pt
                                                title = result.shortName
                                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                            }
                                            mv.overlays.add(marker)
                                            mv.invalidate()
                                        }
                                        searchQuery = result.shortName
                                        searchResults = emptyList()
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Text(result.shortName, color = Color.White, fontSize = 13.sp)
                            }
                            HorizontalDivider(color = Color.White.copy(alpha = 0.06f), thickness = 0.5.dp)
                        }
                    }
                }
            }
        }

        // ── RIGHT FABs ──
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 64.dp, end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Layers
            Box {
                NxFab(icon = "⊞") { showLayerPanel = !showLayerPanel }
                if (showLayerPanel) {
                    Column(
                        modifier = Modifier
                            .offset(x = (-150).dp)
                            .background(PanelBg, RoundedCornerShape(6.dp))
                            .padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf("normal" to "Normal", "satellite" to "Satellite",
                               "white" to "Light", "dark" to "Dark").forEach { (key, label) ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(if (currentLayer == key) CyanDim else Color.Transparent)
                                    .clickable { mapView?.let { setLayer(key, it) } }
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    label,
                                    color = if (currentLayer == key) CyanColor else Color.White,
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
            // Directions
            NxFab(icon = "→") { showDirections = !showDirections }
            // Traffic
            NxFab(icon = "◉", active = trafficEnabled) {
                if (trafficEnabled) {
                    trafficEnabled = false
                    showToast("TRAFFIC OFF")
                } else {
                    if (tomtomKey.isBlank()) showTrafficModal = true
                    else { trafficEnabled = true; showToast("TRAFFIC ON") }
                }
            }
            // Locate
            NxFab(icon = "⊕") { requestLocation() }
            // Zoom in
            NxFab(icon = "+") { mapView?.controller?.zoomIn() }
            // Zoom out
            NxFab(icon = "−") { mapView?.controller?.zoomOut() }
        }

        // ── ZOOM HUD ──
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(start = 12.dp, bottom = if (showDirections) 300.dp else 16.dp)
                .background(PanelBg, RoundedCornerShape(3.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                "ZOOM $zoomLevel",
                color = MutedColor,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp
            )
        }

        // Traffic legend
        if (trafficEnabled) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 12.dp, bottom = if (showDirections) 300.dp else 16.dp)
                    .background(PanelBg, RoundedCornerShape(4.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(TrafficBlue to "Normal", TrafficOrange to "Moderate", TrafficRed to "Heavy").forEach { (color, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(modifier = Modifier.size(10.dp).background(color, RoundedCornerShape(50)))
                        Text(label, color = MutedColor, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        // ── DIRECTIONS PANEL ──
        AnimatedVisibility(
            visible = showDirections,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .background(PanelBg, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .padding(bottom = 8.dp)
            ) {
                // Handle bar
                Box(modifier = Modifier
                    .width(36.dp).height(4.dp)
                    .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(2.dp))
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("ROUTE PLANNER", fontFamily = FontFamily.Monospace, fontSize = 12.sp, letterSpacing = 2.sp, color = CyanColor)
                    TextButton(onClick = { showDirections = false }) { Text("×", color = MutedColor, fontSize = 20.sp) }
                }

                // From field
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(modifier = Modifier.size(10.dp).background(GreenColor, RoundedCornerShape(50)))
                    OutlinedTextField(
                        value = fromText,
                        onValueChange = { fromText = it },
                        placeholder = { Text("From…", color = MutedColor, fontSize = 13.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanColor, unfocusedBorderColor = BorderColor,
                            focusedContainerColor = Color.White.copy(alpha = 0.05f),
                            unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                            cursorColor = CyanColor
                        ),
                        textStyle = TextStyle(fontSize = 14.sp),
                        shape = RoundedCornerShape(4.dp)
                    )
                }

                // To field
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(modifier = Modifier.size(10.dp).background(Color(0xFFFF4466), RoundedCornerShape(50)))
                    OutlinedTextField(
                        value = toText,
                        onValueChange = { toText = it },
                        placeholder = { Text("To…", color = MutedColor, fontSize = 13.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanColor, unfocusedBorderColor = BorderColor,
                            focusedContainerColor = Color.White.copy(alpha = 0.05f),
                            unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                            cursorColor = CyanColor
                        ),
                        textStyle = TextStyle(fontSize = 14.sp),
                        shape = RoundedCornerShape(4.dp)
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Go button
                Button(
                    onClick = {
                        if (fromText.isBlank() || toText.isBlank()) { showToast("ENTER BOTH LOCATIONS"); return@Button }
                        scope.launch {
                            isLoadingRoute = true
                            showToast("CALCULATING ROUTE…")
                            mapView?.let { mv ->
                                val fromPt = if (fromText.matches(Regex("-?\\d+\\.\\d+,\\s*-?\\d+\\.\\d+"))) {
                                    val parts = fromText.split(",")
                                    GeoPoint(parts[0].trim().toDouble(), parts[1].trim().toDouble())
                                } else RouteService.geocode(fromText)

                                val toPt = RouteService.geocode(toText)

                                if (fromPt == null || toPt == null) {
                                    showToast("LOCATION NOT FOUND"); isLoadingRoute = false; return@let
                                }

                                val key = if (trafficEnabled) tomtomKey else ""
                                val result = RouteService.getRoute(fromPt, toPt, key)
                                if (result == null) {
                                    showToast("ROUTING FAILED"); isLoadingRoute = false; return@let
                                }

                                routeInfo = result
                                drawRoute(mv, result)
                                // Fit map to route
                                val allPts = result.segments.flatMap { it.points }
                                if (allPts.isNotEmpty()) {
                                    val box = org.osmdroid.util.BoundingBox.fromGeoPoints(allPts)
                                    mv.zoomToBoundingBox(box, true, 80)
                                }
                                showToast("ROUTE FOUND")
                            }
                            isLoadingRoute = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(48.dp),
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CyanColor),
                    enabled = !isLoadingRoute
                ) {
                    if (isLoadingRoute) {
                        CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Text("CALCULATE ROUTE", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 2.sp, color = Color.Black)
                    }
                }

                // Route info
                routeInfo?.let { info ->
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .background(CyanDim, RoundedCornerShape(6.dp))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("DISTANCE", color = MutedColor, fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
                            val dist = if (info.distanceM >= 1000) "${"%.1f".format(info.distanceM / 1000f)} km" else "${info.distanceM} m"
                            Text(dist, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("ETA", color = MutedColor, fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
                            val eta = if (info.durationSec >= 3600) "${info.durationSec / 3600}h ${(info.durationSec % 3600) / 60}min" else "${info.durationSec / 60} min"
                            Text(eta, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
                        items(info.steps.take(15)) { step ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Text(step.icon, color = CyanColor, fontSize = 14.sp, modifier = Modifier.width(18.dp))
                                Column {
                                    Text(step.instruction, color = Color.White, fontSize = 13.sp)
                                    val stepDist = if (step.distanceM >= 1000) "${"%.1f".format(step.distanceM / 1000f)} km" else "${step.distanceM} m"
                                    Text(stepDist, color = Color(0xFF7C3AED), fontSize = 11.sp)
                                }
                            }
                            HorizontalDivider(color = Color.White.copy(alpha = 0.05f), thickness = 0.5.dp)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        // ── TRAFFIC KEY MODAL ──
        if (showTrafficModal) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f))
                    .clickable { showTrafficModal = false },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.88f)
                        .background(PanelBg, RoundedCornerShape(8.dp))
                        .clickable {}
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("TRAFFIC LAYER", fontFamily = FontFamily.Monospace, fontSize = 13.sp, letterSpacing = 2.sp, color = CyanColor)
                    Text(
                        "Enter your free TomTom API key to enable live traffic coloring on routes. Get one free at developer.tomtom.com",
                        color = MutedColor, fontSize = 12.sp, lineHeight = 18.sp
                    )
                    OutlinedTextField(
                        value = ttKeyInput,
                        onValueChange = { ttKeyInput = it },
                        placeholder = { Text("Paste API key here…", color = MutedColor, fontSize = 13.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanColor, unfocusedBorderColor = BorderColor,
                            focusedContainerColor = Color.White.copy(alpha = 0.05f),
                            unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                            cursorColor = CyanColor
                        ),
                        shape = RoundedCornerShape(4.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { showTrafficModal = false },
                            modifier = Modifier.weight(1f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
                            shape = RoundedCornerShape(4.dp)
                        ) { Text("Cancel", color = Color.White) }
                        Button(
                            onClick = {
                                if (ttKeyInput.isBlank()) { showToast("ENTER API KEY"); return@Button }
                                tomtomKey = ttKeyInput
                                context.getSharedPreferences("nexusmap_prefs", Context.MODE_PRIVATE)
                                    .edit().putString("tt_key", ttKeyInput).apply()
                                trafficEnabled = true
                                showTrafficModal = false
                                showToast("TRAFFIC ENABLED")
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = CyanColor),
                            shape = RoundedCornerShape(4.dp)
                        ) { Text("ENABLE", fontFamily = FontFamily.Monospace, color = Color.Black, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }

        // ── TOAST ──
        AnimatedVisibility(
            visible = toastVisible,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 72.dp)
        ) {
            Box(
                modifier = Modifier
                    .background(PanelBg, RoundedCornerShape(4.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(toastMsg, color = CyanColor, fontSize = 11.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
            }
        }
    }
}

@Composable
private fun NxFab(icon: String, active: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(
                if (active) CyanDim else PanelBg,
                RoundedCornerShape(4.dp)
            )
            .clickable { onClick() }
            .padding(1.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(icon, color = if (active) CyanColor else CyanColor.copy(alpha = 0.8f), fontSize = 18.sp)
    }
}

