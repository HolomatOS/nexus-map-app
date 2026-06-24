package com.example.nexusmap

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas as ACanvas
import android.graphics.Paint as APaint
import android.graphics.Path as APath
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
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
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

private val CyanColor = Color(0xFF00D4FF)
private val CyanDim = Color(0x1A00D4FF)
private val PanelBg = Color(0xF0060614)
private val BorderColor = Color(0x4000D4FF)
private val MutedColor = Color(0xFF64748B)
private val GreenColor = Color(0xFF00FF88)

private fun cartoVoyager() = object : OnlineTileSourceBase("CartoVoyager",0,19,256,".png",
    arrayOf("https://a.basemaps.cartocdn.com","https://b.basemaps.cartocdn.com","https://c.basemaps.cartocdn.com","https://d.basemaps.cartocdn.com")) {
    override fun getTileURLString(t: Long) = "${baseUrl.trimEnd('/')}/rastertiles/voyager/${MapTileIndex.getZoom(t)}/${MapTileIndex.getX(t)}/${MapTileIndex.getY(t)}.png"
}
private fun esriSatellite() = object : OnlineTileSourceBase("ESRI",0,19,256,"",arrayOf("https://server.arcgisonline.com")) {
    override fun getTileURLString(t: Long) = "$baseUrl/ArcGIS/rest/services/World_Imagery/MapServer/tile/${MapTileIndex.getZoom(t)}/${MapTileIndex.getY(t)}/${MapTileIndex.getX(t)}"
}
private fun cartoLight() = object : OnlineTileSourceBase("CartoLight",0,19,256,".png",arrayOf("https://a.basemaps.cartocdn.com","https://b.basemaps.cartocdn.com")) {
    override fun getTileURLString(t: Long) = "${baseUrl.trimEnd('/')}/light_all/${MapTileIndex.getZoom(t)}/${MapTileIndex.getX(t)}/${MapTileIndex.getY(t)}.png"
}
private fun cartoDark() = object : OnlineTileSourceBase("CartoDark",0,19,256,".png",arrayOf("https://a.basemaps.cartocdn.com","https://b.basemaps.cartocdn.com")) {
    override fun getTileURLString(t: Long) = "${baseUrl.trimEnd('/')}/dark_all/${MapTileIndex.getZoom(t)}/${MapTileIndex.getX(t)}/${MapTileIndex.getY(t)}.png"
}

private fun makeCircleBitmap(): Bitmap {
    val s = 64; val bmp = Bitmap.createBitmap(s,s,Bitmap.Config.ARGB_8888)
    val cv = ACanvas(bmp); val r = s/2f
    cv.drawCircle(r,r,r,APaint(APaint.ANTI_ALIAS_FLAG).also{it.color=android.graphics.Color.argb(60,33,150,243)})
    cv.drawCircle(r,r,r*0.68f,APaint(APaint.ANTI_ALIAS_FLAG).also{it.color=android.graphics.Color.WHITE})
    cv.drawCircle(r,r,r*0.52f,APaint(APaint.ANTI_ALIAS_FLAG).also{it.color=android.graphics.Color.rgb(33,150,243)})
    return bmp
}

private fun makeArrowBitmap(): Bitmap {
    val s = 80; val bmp = Bitmap.createBitmap(s,s,Bitmap.Config.ARGB_8888)
    val cv = ACanvas(bmp); val cx = s/2f
    val path = APath().also{ it.moveTo(cx,s*0.04f); it.lineTo(s*0.85f,s*0.90f); it.lineTo(cx,s*0.62f); it.lineTo(s*0.15f,s*0.90f); it.close() }
    cv.drawPath(path,APaint(APaint.ANTI_ALIAS_FLAG).also{it.color=android.graphics.Color.argb(60,0,0,0)})
    cv.drawPath(path,APaint(APaint.ANTI_ALIAS_FLAG).also{it.color=android.graphics.Color.rgb(33,150,243)})
    cv.drawPath(path,APaint(APaint.ANTI_ALIAS_FLAG).also{it.color=android.graphics.Color.WHITE;it.style=APaint.Style.STROKE;it.strokeWidth=s*0.07f;it.strokeJoin=APaint.Join.ROUND})
    return bmp
}

@Composable
fun MapScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var locationOverlay by remember { mutableStateOf<MyLocationNewOverlay?>(null) }
    val routeOverlays = remember { mutableListOf<org.osmdroid.views.overlay.Overlay>() }
    var currentLayer by remember { mutableStateOf("voyager") }
    var showLayerPanel by remember { mutableStateOf(false) }
    var showDirections by remember { mutableStateOf(false) }
    var showTrafficModal by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var fromText by remember { mutableStateOf("") }
    var toText by remember { mutableStateOf("") }
    var routeInfo by remember { mutableStateOf<RouteResult?>(null) }
    var isLoadingRoute by remember { mutableStateOf(false) }
    var trafficEnabled by remember { mutableStateOf(false) }
    var tomtomKey by remember { mutableStateOf(context.getSharedPreferences("nexusmap_prefs",Context.MODE_PRIVATE).getString("tt_key","")?:"") }
    var ttKeyInput by remember { mutableStateOf("") }
    var toastMsg by remember { mutableStateOf("") }
    var toastVisible by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var zoomLevel by remember { mutableStateOf(13) }
    var isNavigating by remember { mutableStateOf(false) }
    var myLocation by remember { mutableStateOf<GeoPoint?>(null) }

    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                if (isNavigating) return
                val mat = FloatArray(9); SensorManager.getRotationMatrixFromVector(mat,e.values)
                val ori = FloatArray(3); SensorManager.getOrientation(mat,ori)
                val az = Math.toDegrees(ori[0].toDouble()).toFloat()
                mapViewRef?.post { mapViewRef?.setMapOrientation(-az) }
            }
            override fun onAccuracyChanged(s: Sensor?,a: Int) {}
        }
        sensor?.let { sm.registerListener(listener,it,SensorManager.SENSOR_DELAY_UI) }
        onDispose { sm.unregisterListener(listener) }
    }

    DisposableEffect(Unit) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                myLocation = GeoPoint(loc.latitude,loc.longitude)
                if (loc.hasBearing() && loc.speed > 0.5f && isNavigating) {
                    mapViewRef?.post {
                        mapViewRef?.setMapOrientation(-loc.bearing)
                        locationOverlay?.myLocation?.let { mapViewRef?.controller?.animateTo(it) }
                    }
                }
            }
        }
        try {
            if (ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000L,1f,listener)
                try { lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,2000L,5f,listener) } catch(_:Exception){}
            }
        } catch(_:Exception){}
        onDispose { try { lm.removeUpdates(listener) } catch(_:Exception){} }
    }

    fun showToast(msg: String) { toastMsg=msg; toastVisible=true; scope.launch { delay(2200); toastVisible=false } }

    fun clearRoute(mv: MapView) { routeOverlays.forEach{mv.overlays.remove(it)}; routeOverlays.clear(); mv.invalidate() }

    fun drawRoute(mv: MapView, result: RouteResult) {
        clearRoute(mv)
        result.segments.forEach { seg ->
            val shadow = Polyline(mv).apply { setPoints(seg.points); outlinePaint.color=android.graphics.Color.argb(80,0,0,0); outlinePaint.strokeWidth=22f; outlinePaint.strokeCap=android.graphics.Paint.Cap.ROUND }
            val line = Polyline(mv).apply {
                setPoints(seg.points)
                outlinePaint.color = when(seg.level) { TrafficLevel.NORMAL->android.graphics.Color.rgb(59,130,246); TrafficLevel.MEDIUM->android.graphics.Color.rgb(249,115,22); TrafficLevel.HEAVY->android.graphics.Color.rgb(239,68,68) }
                outlinePaint.strokeWidth=16f; outlinePaint.strokeCap=android.graphics.Paint.Cap.ROUND; outlinePaint.strokeJoin=android.graphics.Paint.Join.ROUND
            }
            routeOverlays.add(shadow); mv.overlays.add(shadow); routeOverlays.add(line); mv.overlays.add(line)
        }
        result.segments.firstOrNull()?.points?.firstOrNull()?.let { Marker(mv).also{ m->m.position=it;m.title="Start";m.setAnchor(Marker.ANCHOR_CENTER,Marker.ANCHOR_CENTER);m.icon=context.getDrawable(android.R.drawable.ic_menu_mylocation);routeOverlays.add(m);mv.overlays.add(m) } }
        result.segments.lastOrNull()?.points?.lastOrNull()?.let { Marker(mv).also{ m->m.position=it;m.title="Dest";m.setAnchor(Marker.ANCHOR_CENTER,Marker.ANCHOR_CENTER);m.icon=context.getDrawable(android.R.drawable.ic_menu_mapmode);routeOverlays.add(m);mv.overlays.add(m) } }
        mv.invalidate()
    }

    fun setLayer(name: String, mv: MapView) {
        when(name) { "voyager"->mv.setTileSource(cartoVoyager()); "satellite"->mv.setTileSource(esriSatellite()); "light"->mv.setTileSource(cartoLight()); "dark"->mv.setTileSource(cartoDark()) }
        currentLayer=name; showLayerPanel=false; mv.invalidate(); showToast(name.uppercase()+" MODE")
    }

    fun setupLocationOverlay(mv: MapView) {
        val ov = MyLocationNewOverlay(GpsMyLocationProvider(context),mv)
        ov.setDirectionArrow(makeCircleBitmap(),makeArrowBitmap())
        ov.enableMyLocation()
        mv.overlays.add(ov)
        locationOverlay = ov
        ov.runOnFirstFix { mv.post { ov.myLocation?.let { mv.controller.animateTo(it,15.0,1200L) }; showToast("LOCATION FOUND") } }
        mv.invalidate()
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        if (perms[Manifest.permission.ACCESS_FINE_LOCATION]==true||perms[Manifest.permission.ACCESS_COARSE_LOCATION]==true)
            mapViewRef?.let { setupLocationOverlay(it) }
    }

    fun locateMe() {
        if (ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED) {
            mapViewRef?.let { mv ->
                if (locationOverlay==null) setupLocationOverlay(mv)
                else { locationOverlay?.myLocation?.let { mv.controller.animateTo(it,15.0,1200L); showToast("LOCATION FOUND") } ?: showToast("ACQUIRING…") }
            }
        } else permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    Box(modifier=Modifier.fillMaxSize().background(Color.Black)) {

        AndroidView(factory={ ctx ->
            MapView(ctx).also{ mv ->
                mv.setTileSource(cartoVoyager()); mv.setMultiTouchControls(true)
                mv.controller.setZoom(13.0); mv.controller.setCenter(GeoPoint(51.505,-0.09))
                mv.setBuiltInZoomControls(false); mv.minZoomLevel=3.0
                mv.addMapListener(object:org.osmdroid.events.MapListener{
                    override fun onScroll(e:org.osmdroid.events.ScrollEvent?)=false
                    override fun onZoom(e:org.osmdroid.events.ZoomEvent?):Boolean{ zoomLevel=mv.zoomLevel.toInt();return false }
                })
                mapViewRef=mv
                scope.launch { delay(600); locateMe() }
            }
        }, modifier=Modifier.fillMaxSize())

        Column(modifier=Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal=12.dp,vertical=8.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Text("NX/MAP",fontFamily=FontFamily.Monospace,fontWeight=FontWeight.Black,fontSize=13.sp,letterSpacing=3.sp,color=CyanColor)
                Box(modifier=Modifier.weight(1f)) {
                    OutlinedTextField(
                        value=searchQuery, onValueChange={ q-> searchQuery=q; searchJob?.cancel()
                            if(q.length>=2){ searchJob=scope.launch{ delay(350);isSearching=true;searchResults=RouteService.search(q);isSearching=false } } else searchResults=emptyList() },
                        placeholder={ Text("Search places…",color=MutedColor,fontSize=13.sp) }, singleLine=true,
                        modifier=Modifier.fillMaxWidth().height(50.dp),
                        leadingIcon={ if(isSearching) CircularProgressIndicator(color=CyanColor,modifier=Modifier.size(18.dp),strokeWidth=2.dp) else Text("⌕",color=MutedColor,fontSize=16.sp) },
                        keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),
                        keyboardActions=KeyboardActions(onSearch={ searchJob?.cancel();scope.launch{isSearching=true;searchResults=RouteService.search(searchQuery);isSearching=false} }),
                        colors=OutlinedTextFieldDefaults.colors(focusedBorderColor=CyanColor,unfocusedBorderColor=BorderColor,focusedContainerColor=PanelBg,unfocusedContainerColor=PanelBg,cursorColor=CyanColor,focusedTextColor=Color.White,unfocusedTextColor=Color.White),
                        textStyle=TextStyle(fontSize=14.sp), shape=RoundedCornerShape(8.dp)
                    )
                }
            }
            if(searchResults.isNotEmpty()) {
                Column(modifier=Modifier.fillMaxWidth().padding(top=4.dp).background(PanelBg,RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp))) {
                    searchResults.take(6).forEachIndexed{ i,r ->
                        Row(modifier=Modifier.fillMaxWidth().clickable{
                            mapViewRef?.let{ mv-> val pt=GeoPoint(r.lat,r.lon); mv.controller.animateTo(pt,15.0,1200L)
                                Marker(mv).also{m->m.position=pt;m.title=r.shortName;m.setAnchor(Marker.ANCHOR_CENTER,Marker.ANCHOR_BOTTOM);mv.overlays.add(m);mv.invalidate()} }
                            searchQuery=r.shortName; searchResults=emptyList()
                        }.padding(horizontal=14.dp,vertical=11.dp), verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            Text("›",color=CyanColor.copy(alpha=0.6f),fontSize=16.sp)
                            Column {
                                Text(r.shortName,color=Color.White,fontSize=13.sp,fontWeight=FontWeight.Medium)
                                val sub=r.displayName.split(",").drop(1).take(2).joinToString(",").trim()
                                if(sub.isNotEmpty()) Text(sub,color=MutedColor,fontSize=11.sp,maxLines=1)
                            }
                        }
                        if(i<searchResults.size-1) HorizontalDivider(color=Color.White.copy(alpha=0.05f),thickness=0.5.dp)
                    }
                }
            }
        }

        Column(modifier=Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top=64.dp,end=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Box {
                NxFab("⊞"){ showLayerPanel=!showLayerPanel }
                if(showLayerPanel) {
                    Column(modifier=Modifier.offset(x=(-155).dp).background(PanelBg,RoundedCornerShape(8.dp)).padding(6.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                        listOf("voyager" to "Streets","satellite" to "Satellite","light" to "Light","dark" to "Dark").forEach{ (k,l)->
                            Box(modifier=Modifier.clip(RoundedCornerShape(4.dp)).background(if(currentLayer==k)CyanDim else Color.Transparent).clickable{mapViewRef?.let{setLayer(k,it)}}.padding(horizontal=14.dp,vertical=9.dp)) {
                                Text(l,color=if(currentLayer==k)CyanColor else Color.White,fontSize=13.sp,fontFamily=FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
            NxFab("→"){ showDirections=!showDirections }
            NxFab("◉",active=trafficEnabled){ if(trafficEnabled){trafficEnabled=false;showToast("TRAFFIC OFF")} else if(tomtomKey.isBlank())showTrafficModal=true else{trafficEnabled=true;showToast("TRAFFIC ON")} }
            NxFab("⊕"){ locateMe() }
            NxFab("+"){ mapViewRef?.controller?.zoomIn() }
            NxFab("−"){ mapViewRef?.controller?.zoomOut() }
        }

        Box(modifier=Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(start=12.dp,bottom=if(showDirections)310.dp else 16.dp).background(PanelBg,RoundedCornerShape(3.dp)).padding(horizontal=8.dp,vertical=4.dp)) {
            Text("Z $zoomLevel",color=MutedColor,fontSize=10.sp,fontFamily=FontFamily.Monospace)
        }

        if(trafficEnabled) {
            Column(modifier=Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end=12.dp,bottom=if(showDirections)310.dp else 16.dp).background(PanelBg,RoundedCornerShape(4.dp)).padding(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                listOf(Color(0xFF3B82F6) to "Normal",Color(0xFFF97316) to "Moderate",Color(0xFFEF4444) to "Heavy").forEach{(col,lbl)->
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        Box(modifier=Modifier.size(8.dp).background(col,RoundedCornerShape(50))); Text(lbl,color=MutedColor,fontSize=10.sp,fontFamily=FontFamily.Monospace)
                    }
                }
            }
        }

        AnimatedVisibility(visible=showDirections,enter=slideInVertically{it},exit=slideOutVertically{it},modifier=Modifier.align(Alignment.BottomCenter)) {
            Column(modifier=Modifier.fillMaxWidth().navigationBarsPadding().background(PanelBg,RoundedCornerShape(topStart=16.dp,topEnd=16.dp)).padding(bottom=8.dp)) {
                Box(modifier=Modifier.width(36.dp).height(4.dp).background(Color.White.copy(alpha=0.2f),RoundedCornerShape(2.dp)).align(Alignment.CenterHorizontally).padding(top=10.dp))
                Row(modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=10.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                    Text("ROUTE PLANNER",fontFamily=FontFamily.Monospace,fontSize=12.sp,letterSpacing=2.sp,color=CyanColor)
                    TextButton(onClick={showDirections=false;isNavigating=false}){ Text("×",color=MutedColor,fontSize=20.sp) }
                }
                Row(modifier=Modifier.padding(horizontal=16.dp,vertical=3.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Box(modifier=Modifier.size(10.dp).background(GreenColor,RoundedCornerShape(50)))
                    OutlinedTextField(value=fromText,onValueChange={fromText=it},placeholder={Text("From (blank = my location)",color=MutedColor,fontSize=12.sp)},singleLine=true,modifier=Modifier.fillMaxWidth().height(48.dp),
                        colors=OutlinedTextFieldDefaults.colors(focusedBorderColor=CyanColor,unfocusedBorderColor=BorderColor,focusedContainerColor=Color.White.copy(0.05f),unfocusedContainerColor=Color.White.copy(0.05f),focusedTextColor=Color.White,unfocusedTextColor=Color.White,cursorColor=CyanColor),
                        textStyle=TextStyle(fontSize=13.sp),shape=RoundedCornerShape(6.dp))
                }
                Row(modifier=Modifier.padding(horizontal=16.dp,vertical=3.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Box(modifier=Modifier.size(10.dp).background(Color(0xFFFF4466),RoundedCornerShape(50)))
                    OutlinedTextField(value=toText,onValueChange={toText=it},placeholder={Text("To destination…",color=MutedColor,fontSize=12.sp)},singleLine=true,modifier=Modifier.fillMaxWidth().height(48.dp),
                        colors=OutlinedTextFieldDefaults.colors(focusedBorderColor=CyanColor,unfocusedBorderColor=BorderColor,focusedContainerColor=Color.White.copy(0.05f),unfocusedContainerColor=Color.White.copy(0.05f),focusedTextColor=Color.White,unfocusedTextColor=Color.White,cursorColor=CyanColor),
                        textStyle=TextStyle(fontSize=13.sp),shape=RoundedCornerShape(6.dp))
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick={
                    if(toText.isBlank()){showToast("ENTER DESTINATION");return@Button}
                    scope.launch {
                        isLoadingRoute=true; showToast("CALCULATING…")
                        mapViewRef?.let{ mv->
                            val fromPt:GeoPoint? = when {
                                fromText.isBlank() -> myLocation?:locationOverlay?.myLocation?:run{showToast("WAITING FOR GPS");null}
                                fromText.matches(Regex("-?[0-9]+\.[0-9]+,\s*-?[0-9]+\.[0-9]+")) -> fromText.split(",").let{GeoPoint(it[0].trim().toDouble(),it[1].trim().toDouble())}
                                else -> RouteService.geocode(fromText)
                            }
                            val toPt:GeoPoint? = when {
                                toText.matches(Regex("-?[0-9]+\.[0-9]+,\s*-?[0-9]+\.[0-9]+")) -> toText.split(",").let{GeoPoint(it[0].trim().toDouble(),it[1].trim().toDouble())}
                                else -> RouteService.geocode(toText)
                            }
                            if(fromPt==null||toPt==null){showToast("LOCATION NOT FOUND");isLoadingRoute=false;return@let}
                            val result=RouteService.getRoute(fromPt,toPt,if(trafficEnabled)tomtomKey else "")
                            if(result==null){showToast("ROUTING FAILED");isLoadingRoute=false;return@let}
                            routeInfo=result; drawRoute(mv,result)
                            isNavigating=true; locationOverlay?.enableFollowLocation()
                            val allPts=result.segments.flatMap{it.points}
                            if(allPts.isNotEmpty()) mv.zoomToBoundingBox(BoundingBox.fromGeoPoints(allPts),true,80)
                            delay(2000); mv.controller.setZoom(16.0); showToast("NAVIGATING")
                        }
                        isLoadingRoute=false
                    }
                },modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp).height(48.dp),shape=RoundedCornerShape(6.dp),colors=ButtonDefaults.buttonColors(containerColor=CyanColor),enabled=!isLoadingRoute) {
                    if(isLoadingRoute) CircularProgressIndicator(color=Color.Black,modifier=Modifier.size(20.dp),strokeWidth=2.dp)
                    else Text("GO",fontFamily=FontFamily.Monospace,fontWeight=FontWeight.Black,fontSize=15.sp,letterSpacing=3.sp,color=Color.Black)
                }
                routeInfo?.let{ info->
                    Spacer(Modifier.height(10.dp))
                    Row(modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp).background(CyanDim,RoundedCornerShape(8.dp)).padding(12.dp),horizontalArrangement=Arrangement.SpaceEvenly) {
                        Column(horizontalAlignment=Alignment.CenterHorizontally) {
                            Text("DISTANCE",color=MutedColor,fontSize=10.sp,fontFamily=FontFamily.Monospace)
                            Text(if(info.distanceM>=1000)"${"%.1f".format(info.distanceM/1000f)} km" else "${info.distanceM} m",color=Color.White,fontSize=16.sp,fontWeight=FontWeight.SemiBold)
                        }
                        Column(horizontalAlignment=Alignment.CenterHorizontally) {
                            Text("ETA",color=MutedColor,fontSize=10.sp,fontFamily=FontFamily.Monospace)
                            Text(if(info.durationSec>=3600)"${info.durationSec/3600}h ${(info.durationSec%3600)/60}min" else "${info.durationSec/60} min",color=Color.White,fontSize=16.sp,fontWeight=FontWeight.SemiBold)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(modifier=Modifier.fillMaxWidth().heightIn(max=180.dp)) {
                        items(info.steps.take(15)){ step->
                            Row(modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=6.dp),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.Top) {
                                Text(step.icon,color=CyanColor,fontSize=14.sp,modifier=Modifier.width(18.dp))
                                Column {
                                    Text(step.instruction,color=Color.White,fontSize=12.sp)
                                    Text(if(step.distanceM>=1000)"${"%.1f".format(step.distanceM/1000f)} km" else "${step.distanceM} m",color=Color(0xFF7C3AED),fontSize=11.sp)
                                }
                            }
                            HorizontalDivider(color=Color.White.copy(alpha=0.05f),thickness=0.5.dp)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        if(showTrafficModal) {
            Box(modifier=Modifier.fillMaxSize().background(Color.Black.copy(alpha=0.7f)).clickable{showTrafficModal=false},contentAlignment=Alignment.Center) {
                Column(modifier=Modifier.fillMaxWidth(0.88f).background(PanelBg,RoundedCornerShape(8.dp)).clickable{}.padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text("TRAFFIC LAYER",fontFamily=FontFamily.Monospace,fontSize=13.sp,letterSpacing=2.sp,color=CyanColor)
                    Text("Free TomTom key at developer.tomtom.com — enables live traffic colors on route.",color=MutedColor,fontSize=12.sp,lineHeight=18.sp)
                    OutlinedTextField(value=ttKeyInput,onValueChange={ttKeyInput=it},placeholder={Text("Paste API key…",color=MutedColor,fontSize=13.sp)},singleLine=true,modifier=Modifier.fillMaxWidth(),
                        colors=OutlinedTextFieldDefaults.colors(focusedBorderColor=CyanColor,unfocusedBorderColor=BorderColor,focusedContainerColor=Color.White.copy(0.05f),unfocusedContainerColor=Color.White.copy(0.05f),focusedTextColor=Color.White,unfocusedTextColor=Color.White,cursorColor=CyanColor),shape=RoundedCornerShape(4.dp))
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick={showTrafficModal=false},modifier=Modifier.weight(1f),border=androidx.compose.foundation.BorderStroke(1.dp,BorderColor),shape=RoundedCornerShape(4.dp)){ Text("Cancel",color=Color.White) }
                        Button(onClick={
                            if(ttKeyInput.isBlank()){showToast("ENTER KEY");return@Button}
                            tomtomKey=ttKeyInput
                            context.getSharedPreferences("nexusmap_prefs",Context.MODE_PRIVATE).edit().putString("tt_key",ttKeyInput).apply()
                            trafficEnabled=true;showTrafficModal=false;showToast("TRAFFIC ENABLED")
                        },modifier=Modifier.weight(1f),colors=ButtonDefaults.buttonColors(containerColor=CyanColor),shape=RoundedCornerShape(4.dp)){ Text("ENABLE",fontFamily=FontFamily.Monospace,color=Color.Black,fontWeight=FontWeight.Bold) }
                    }
                }
            }
        }

        AnimatedVisibility(visible=toastVisible,modifier=Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top=72.dp)) {
            Box(modifier=Modifier.background(PanelBg,RoundedCornerShape(4.dp)).padding(horizontal=16.dp,vertical=8.dp)) {
                Text(toastMsg,color=CyanColor,fontSize=11.sp,fontFamily=FontFamily.Monospace,letterSpacing=1.sp)
            }
        }
    }
}

@Composable
private fun NxFab(icon: String, active: Boolean = false, onClick: () -> Unit) {
    Box(modifier=Modifier.size(44.dp).background(if(active)CyanDim else PanelBg,RoundedCornerShape(8.dp)).clickable{onClick()},contentAlignment=Alignment.Center) {
        Text(icon,color=if(active)CyanColor else CyanColor.copy(alpha=0.8f),fontSize=18.sp)
    }
}
