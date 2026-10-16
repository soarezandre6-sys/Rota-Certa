package com.rotacerta.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.rotacerta.app.data.RouteStore
import com.rotacerta.app.importer.ImageRouteImporter
import com.rotacerta.app.location.RouteRecordingService
import com.rotacerta.app.model.RecordedRoute
import com.rotacerta.app.model.RoutePoint
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.Polyline
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var navigateButton: Button
    private lateinit var centerLocationButton: ImageButton
    private lateinit var routeStore: RouteStore
    private lateinit var imageImporter: ImageRouteImporter

    private lateinit var controlPanelBody: LinearLayout
    private lateinit var panelToggle: TextView
    private lateinit var navigationInstructionPanel: LinearLayout
    private lateinit var navigationArrow: TextView
    private lateinit var navigationPrimaryText: TextView
    private lateinit var navigationSecondaryText: TextView

    private val fusedLocationClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }

    private val streetLookupExecutor = Executors.newSingleThreadExecutor()
    private val streetNameCache = mutableMapOf<Int, String>()
    private var pendingStreetLookupIndex: Int? = null

    private var map: MapLibreMap? = null
    private var mapStyleReady = false
    private var vectorStyleLoaded = false
    private var mapStyleAttempt = 0

    private var recordedOutline: Polyline? = null
    private var recordedPolyline: Polyline? = null
    private var savedRouteOutline: Polyline? = null
    private var savedRoutePolyline: Polyline? = null
    private var locationMarker: Marker? = null

    private var currentLocation: Location? = null
    private var liveLocationUpdatesActive = false
    private var centeredOnUserOnce = false
    private var lastCameraFollowAt = 0L

    private var lastSavedRoute: RecordedRoute? = null
    private var localNavigationRoute: RecordedRoute? = null
    private var lastSpokenTurnIndex = -1
    private var offRouteAnnounced = false
    private var controlsCollapsed = false

    private var textToSpeech: TextToSpeech? = null
    private var textToSpeechReady = false

    private var recordingService: RouteRecordingService? = null
    private var bindRequested = false
    private var pendingStartRecording = false
    private var recordingUiActive = false
    private var startRecordingAfterPermission = false

    private val liveLocationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { renderLiveLocation(it, forceFollow = false) }
        }
    }

    private val recordingListener = object : RouteRecordingService.Listener {
        override fun onRecordingUpdated(snapshot: RouteRecordingService.Snapshot) {
            runOnUiThread { renderRecordingSnapshot(snapshot) }
        }
    }

    private val recordingConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val serviceBinder = binder as? RouteRecordingService.LocalBinder ?: return
            recordingService = serviceBinder.getService()
            recordingService?.setListener(recordingListener)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recordingService?.setListener(null)
            recordingService = null
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        if (granted) {
            startLiveLocationUpdates()
            if (startRecordingAfterPermission) startRecordingNow()
        } else if (startRecordingAfterPermission) {
            toast("Permissão de localização é necessária para gravar a rota.")
        } else {
            statusText.text = "Permita a localização para mostrar sua posição no mapa"
        }
        startRecordingAfterPermission = false
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let(::readRouteImage)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.map_view)
        statusText = findViewById(R.id.status_text)
        startButton = findViewById(R.id.start_button)
        stopButton = findViewById(R.id.stop_button)
        navigateButton = findViewById(R.id.navigate_last_button)
        centerLocationButton = findViewById(R.id.center_location_button)
        controlPanelBody = findViewById(R.id.control_panel_body)
        panelToggle = findViewById(R.id.panel_toggle)
        navigationInstructionPanel = findViewById(R.id.navigation_instruction_panel)
        navigationArrow = findViewById(R.id.navigation_arrow)
        navigationPrimaryText = findViewById(R.id.navigation_primary_text)
        navigationSecondaryText = findViewById(R.id.navigation_secondary_text)

        applySystemBarInsets()

        routeStore = RouteStore(this)
        imageImporter = ImageRouteImporter(this)
        lastSavedRoute = routeStore.loadLast()

        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeechReady = true
                textToSpeech?.language = Locale("pt", "BR")
            }
        }

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { mapLibreMap ->
            map = mapLibreMap
            mapLibreMap.uiSettings.isCompassEnabled = true
            mapLibreMap.uiSettings.isAttributionEnabled = true
            mapLibreMap.uiSettings.isLogoEnabled = true
            loadVectorMapStyle(mapLibreMap)
        }

        panelToggle.setOnClickListener { setControlsCollapsed(!controlsCollapsed) }
        startButton.setOnClickListener { startRecording() }
        stopButton.setOnClickListener { stopAndNameRoute() }
        findViewById<Button>(R.id.saved_routes_button).setOnClickListener { showSavedRoutes() }
        findViewById<Button>(R.id.import_image_button).setOnClickListener {
            imagePicker.launch("image/*")
        }
        navigateButton.setOnClickListener {
            if (localNavigationRoute != null) {
                stopLocalNavigation(updateStatus = true)
            } else {
                lastSavedRoute?.let(::navigateRoute) ?: toast("Selecione ou grave uma rota primeiro.")
            }
        }
        centerLocationButton.setOnClickListener {
            val location = currentLocation
            if (location != null) {
                centeredOnUserOnce = true
                centerMapOn(location, driving = recordingUiActive || localNavigationRoute != null, immediate = false)
            } else {
                toast("Aguardando uma posição GPS válida.")
            }
        }

        if (hasLocationPermission()) startLiveLocationUpdates()
        else requestLocationPermission(startRecordingAfter = false)
    }

    private fun setControlsCollapsed(collapsed: Boolean) {
        controlsCollapsed = collapsed
        controlPanelBody.visibility = if (collapsed) View.GONE else View.VISIBLE
        panelToggle.text = if (collapsed) "⌃" else "⌄"
        panelToggle.contentDescription = if (collapsed) "Abrir controles" else "Recolher controles"
    }

    private fun showNavigationInstruction(arrow: String, primary: String, secondary: String) {
        navigationInstructionPanel.visibility = View.VISIBLE
        navigationArrow.text = arrow
        navigationPrimaryText.text = primary
        navigationSecondaryText.text = secondary
    }

    private fun hideNavigationInstruction() {
        navigationInstructionPanel.visibility = View.GONE
    }

    private fun loadVectorMapStyle(mapLibreMap: MapLibreMap) {
        mapStyleReady = false
        vectorStyleLoaded = false

        mapStyleAttempt += 1
        val brightAttempt = mapStyleAttempt
        mapLibreMap.setStyle(Style.Builder().fromUri(OPENFREE_BRIGHT_STYLE_URI)) {
            if (mapStyleAttempt == brightAttempt) {
                vectorStyleLoaded = true
                onMapStyleReady()
            }
        }

        // Se o estilo Bright não responder, preserva um segundo mapa vetorial
        // antes de cair para o raster básico. Assim a navegação não perde detalhes.
        mapView.postDelayed({
            if (!vectorStyleLoaded && !isFinishing && map === mapLibreMap) {
                mapStyleAttempt += 1
                val libertyAttempt = mapStyleAttempt
                mapLibreMap.setStyle(Style.Builder().fromUri(OPENFREE_LIBERTY_STYLE_URI)) {
                    if (mapStyleAttempt == libertyAttempt) {
                        vectorStyleLoaded = true
                        onMapStyleReady()
                    }
                }
            }
        }, VECTOR_STYLE_FALLBACK_DELAY_MS)

        mapView.postDelayed({
            if (!vectorStyleLoaded && !isFinishing && map === mapLibreMap) {
                mapStyleAttempt += 1
                val rasterAttempt = mapStyleAttempt
                mapLibreMap.setStyle(Style.Builder().fromJson(OSM_FALLBACK_STYLE_JSON)) {
                    if (mapStyleAttempt == rasterAttempt) {
                        vectorStyleLoaded = false
                        onMapStyleReady()
                        if (!recordingUiActive && localNavigationRoute == null) {
                            statusText.text = "Mapa básico carregado • GPS pronto"
                        }
                    }
                }
            }
        }, RASTER_STYLE_FALLBACK_DELAY_MS)
    }

    private fun onMapStyleReady() {
        mapStyleReady = true
        clearAnnotationReferences()
        lastSavedRoute?.let(::drawSavedRoute)
        currentLocation?.let { renderLiveLocation(it, forceFollow = !centeredOnUserOnce) }
        if (!recordingUiActive && localNavigationRoute == null) {
            statusText.text = if (vectorStyleLoaded) {
                "Mapa detalhado e GPS prontos"
            } else {
                "Mapa e GPS prontos"
            }
        }
    }

    private fun clearAnnotationReferences() {
        recordedOutline = null
        recordedPolyline = null
        savedRouteOutline = null
        savedRoutePolyline = null
        locationMarker = null
    }

    private fun applySystemBarInsets() {
        val panel = findViewById<View>(R.id.control_panel)
        val baseBottomPadding = panel.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(panel) { view, insets ->
            val nav = insets.getInsets(
                WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.systemGestures()
            )
            val safeBottom = max(nav.bottom, dpToPx(MIN_EXTRA_BOTTOM_DP))
            view.updatePadding(bottom = baseBottomPadding + safeBottom)
            insets
        }

        val topInsetListener = OnApplyWindowInsetsListener { view, insets ->
            val status = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.translationY = status.top.toFloat()
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(centerLocationButton, topInsetListener)
        ViewCompat.setOnApplyWindowInsetsListener(navigationInstructionPanel, topInsetListener)

        ViewCompat.requestApplyInsets(panel)
        ViewCompat.requestApplyInsets(centerLocationButton)
        ViewCompat.requestApplyInsets(navigationInstructionPanel)
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).roundToInt()

    private fun requestLocationPermission(startRecordingAfter: Boolean) {
        startRecordingAfterPermission = startRecordingAfter
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun startRecording() {
        if (hasLocationPermission()) startRecordingNow()
        else requestLocationPermission(startRecordingAfter = true)
    }

    private fun startRecordingNow() {
        stopLocalNavigation(updateStatus = false)
        maybeRequestNotificationPermission()
        pendingStartRecording = true
        recordingUiActive = true
        centeredOnUserOnce = true

        recordedOutline?.let { map?.removeAnnotation(it) }
        recordedPolyline?.let { map?.removeAnnotation(it) }
        recordedOutline = null
        recordedPolyline = null

        startButton.isEnabled = false
        stopButton.isEnabled = true
        statusText.text = "Ativando GPS e iniciando gravação..."

        ContextCompat.startForegroundService(
            this,
            Intent(this, RouteRecordingService::class.java).apply {
                action = RouteRecordingService.ACTION_START
            }
        )
    }

    private fun renderRecordingSnapshot(snapshot: RouteRecordingService.Snapshot) {
        if (snapshot.recording) {
            pendingStartRecording = false
            recordingUiActive = true
            startButton.isEnabled = false
            stopButton.isEnabled = true

            val latLngs = snapshot.points.map { LatLng(it.latitude, it.longitude) }
            if (latLngs.size >= 2 && mapStyleReady) {
                if (recordedOutline == null) {
                    recordedOutline = map?.addPolyline(
                        PolylineOptions().addAll(latLngs).width(16f).color(Color.rgb(13, 45, 68))
                    )
                    recordedPolyline = map?.addPolyline(
                        PolylineOptions().addAll(latLngs).width(9f).color(Color.rgb(0, 210, 153))
                    )
                } else {
                    recordedOutline?.points = latLngs
                    recordedPolyline?.points = latLngs
                }
            }

            snapshot.points.lastOrNull()?.let { renderLiveLocation(toLocation(it), forceFollow = true) }

            statusText.text = String.format(
                Locale.getDefault(),
                "Gravando • %.2f km • %d pontos",
                snapshot.distanceMeters / 1_000f,
                latLngs.size
            )
        } else if (recordingUiActive && !pendingStartRecording) {
            recordingUiActive = false
            startButton.isEnabled = true
            stopButton.isEnabled = false
        }
    }

    private fun stopAndNameRoute() {
        val service = recordingService
        if (service == null || !service.isRecording()) {
            toast("A gravação ainda não está pronta para ser encerrada.")
            return
        }

        val finalSnapshot = service.stopRecording()
        pendingStartRecording = false
        recordingUiActive = false
        startButton.isEnabled = true
        stopButton.isEnabled = false

        if (finalSnapshot.points.size < 2) {
            statusText.text = "Rota curta demais para salvar"
            return
        }

        val route = RecordedRoute(
            id = System.currentTimeMillis(),
            name = "",
            startedAt = finalSnapshot.startedAt,
            finishedAt = System.currentTimeMillis(),
            distanceMeters = finalSnapshot.distanceMeters,
            points = finalSnapshot.points
        )
        askRouteNameAndSave(route, "Ex.: Linha 203 Centro - Terminal")
    }

    private fun askRouteNameAndSave(route: RecordedRoute, hint: String) {
        val input = EditText(this).apply { this.hint = hint }
        AlertDialog.Builder(this)
            .setTitle("Salvar rota")
            .setMessage("Dê um nome para reconhecer este trajeto depois.")
            .setView(input)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar") { _, _ ->
                val now = System.currentTimeMillis()
                val saved = route.copy(
                    id = now,
                    name = input.text.toString().trim().ifBlank { "Rota ${routeStore.loadAll().size + 1}" },
                    finishedAt = if (route.finishedAt > 0L) route.finishedAt else now
                )
                routeStore.save(saved)
                lastSavedRoute = saved
                drawSavedRoute(saved)
                statusText.text = "${saved.name} salva • ${"%.2f".format(saved.distanceMeters / 1_000f)} km"
            }
            .show()
    }

    private fun showSavedRoutes() {
        val routes = routeStore.loadAll().sortedByDescending { it.finishedAt }
        if (routes.isEmpty()) {
            toast("Ainda não há rotas salvas.")
            return
        }

        val labels = routes.map { route ->
            "${route.name} • ${"%.2f".format(route.distanceMeters / 1_000f)} km"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Rotas salvas")
            .setItems(labels) { _, which ->
                stopLocalNavigation(updateStatus = false)
                val selected = routes[which]
                lastSavedRoute = selected
                drawSavedRoute(selected)
                statusText.text = "Rota selecionada: ${selected.name}"
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun navigateRoute(route: RecordedRoute) {
        if (route.points.size < 2) {
            toast("A rota salva não tem pontos suficientes.")
            return
        }
        if (!hasLocationPermission()) {
            toast("Permita a localização para navegar pela rota.")
            requestLocationPermission(startRecordingAfter = false)
            return
        }

        lastSavedRoute = route
        localNavigationRoute = route
        drawSavedRoute(route)
        lastSpokenTurnIndex = -1
        offRouteAnnounced = false
        centeredOnUserOnce = true
        streetNameCache.clear()
        pendingStreetLookupIndex = null
        navigateButton.text = "ENCERRAR NAVEGAÇÃO"
        setControlsCollapsed(true)
        showNavigationInstruction("↑", "Navegação iniciada", "Aguardando próxima instrução")
        speak("Navegação iniciada")

        currentLocation?.let {
            centerMapOn(it, driving = true, immediate = false)
            updateLocalNavigation(it)
        } ?: run {
            statusText.text = "Navegação iniciada • aguardando GPS..."
        }
    }

    private fun stopLocalNavigation(updateStatus: Boolean) {
        localNavigationRoute = null
        lastSpokenTurnIndex = -1
        offRouteAnnounced = false
        streetNameCache.clear()
        pendingStreetLookupIndex = null
        navigateButton.text = "NAVEGAR NA ROTA SELECIONADA"
        hideNavigationInstruction()
        if (updateStatus) {
            statusText.text = "Navegação encerrada"
            setControlsCollapsed(false)
        }
    }

    private fun updateLocalNavigation(location: Location) {
        val route = localNavigationRoute ?: return
        val points = route.points
        if (points.size < 2) return

        val nearestIndex = nearestRouteIndex(location, points)
        val deviation = location.distanceTo(toLocation(points[nearestIndex]))
        val destinationDistance = location.distanceTo(toLocation(points.last()))

        if (destinationDistance <= DESTINATION_RADIUS_METERS) {
            speak("Você chegou ao destino")
            stopLocalNavigation(updateStatus = false)
            statusText.text = "Destino alcançado • ${route.name}"
            showNavigationInstruction("✓", "Destino alcançado", route.name)
            mapView.postDelayed({ hideNavigationInstruction() }, 4_000L)
            return
        }

        if (deviation > OFF_ROUTE_DISTANCE_METERS) {
            statusText.text = "Fora da rota • ${formatDistance(deviation)} do trajeto"
            showNavigationInstruction("↶", "Fora da rota", "Retorne ao trajeto marcado")
            if (!offRouteAnnounced) {
                speak("Você saiu da rota. Retorne ao trajeto marcado.")
                offRouteAnnounced = true
            }
            return
        }
        offRouteAnnounced = false

        val nextTurnIndex = findNextTurnIndex(points, nearestIndex)
        if (nextTurnIndex != null) {
            val turnDistance = location.distanceTo(toLocation(points[nextTurnIndex]))
            val direction = turnDirection(points, nextTurnIndex)
            val arrow = if (direction == "direita") "↱" else "↰"
            val street = streetNameCache[nextTurnIndex] ?: "Próxima conversão"

            statusText.text = "Navegando • ${formatDistance(turnDistance)} • vire à $direction"
            showNavigationInstruction(
                arrow,
                "${formatDistance(turnDistance)} • Vire à $direction",
                street
            )
            requestStreetName(nextTurnIndex, points[nextTurnIndex])

            if (turnDistance <= TURN_VOICE_DISTANCE_METERS && nextTurnIndex != lastSpokenTurnIndex) {
                val spokenStreet = streetNameCache[nextTurnIndex]?.let { " na $it" }.orEmpty()
                speak("Em ${spokenDistance(turnDistance)}, vire à $direction$spokenStreet")
                lastSpokenTurnIndex = nextTurnIndex
            }
        } else {
            statusText.text = "Navegando • siga em frente • ${formatDistance(destinationDistance)} até o destino"
            showNavigationInstruction(
                "↑",
                "Siga em frente",
                "${formatDistance(destinationDistance)} até o destino"
            )
        }
    }

    private fun requestStreetName(index: Int, point: RoutePoint) {
        if (streetNameCache.containsKey(index) || pendingStreetLookupIndex == index) return
        pendingStreetLookupIndex = index
        streetLookupExecutor.execute {
            val street = runCatching {
                val geocoder = Geocoder(this, Locale("pt", "BR"))
                @Suppress("DEPRECATION")
                val address = geocoder.getFromLocation(point.latitude, point.longitude, 1)?.firstOrNull()
                address?.thoroughfare ?: address?.featureName ?: address?.subLocality
            }.getOrNull()

            runOnUiThread {
                pendingStreetLookupIndex = null
                if (!street.isNullOrBlank()) {
                    streetNameCache[index] = street
                    val current = currentLocation
                    if (current != null && localNavigationRoute != null) updateLocalNavigation(current)
                }
            }
        }
    }

    private fun nearestRouteIndex(location: Location, points: List<RoutePoint>): Int {
        var bestIndex = 0
        var bestDistance = Float.MAX_VALUE
        points.forEachIndexed { index, point ->
            val distance = location.distanceTo(toLocation(point))
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
        }
        return bestIndex
    }

    private fun findNextTurnIndex(points: List<RoutePoint>, currentIndex: Int): Int? {
        if (points.size < 3) return null
        var traveled = 0f
        val start = (currentIndex + 2).coerceAtLeast(1)
        for (index in start until points.lastIndex) {
            traveled += distanceMeters(points[index - 1], points[index])
            if (traveled < MIN_TURN_LOOKAHEAD_METERS) continue
            if (turnAngle(points[index - 1], points[index], points[index + 1]) >= LOCAL_TURN_DEGREES) {
                return index
            }
        }
        return null
    }

    private fun turnDirection(points: List<RoutePoint>, index: Int): String {
        val firstBearing = toLocation(points[index - 1]).bearingTo(toLocation(points[index]))
        val secondBearing = toLocation(points[index]).bearingTo(toLocation(points[index + 1]))
        val delta = ((secondBearing - firstBearing + 540f) % 360f) - 180f
        return if (delta >= 0f) "direita" else "esquerda"
    }

    private fun turnAngle(a: RoutePoint, b: RoutePoint, c: RoutePoint): Float {
        val firstBearing = toLocation(a).bearingTo(toLocation(b))
        val secondBearing = toLocation(b).bearingTo(toLocation(c))
        return abs(((secondBearing - firstBearing + 540f) % 360f) - 180f)
    }

    private fun readRouteImage(uri: Uri) {
        statusText.text = "Analisando o print da rota..."
        imageImporter.scanRouteImage(
            uri = uri,
            onSuccess = { scan ->
                runOnUiThread {
                    if (scan.references.isEmpty()) {
                        statusText.text = "Não encontrei nomes de ruas úteis nessa imagem"
                        toast("Tente um print com os nomes das ruas visíveis.")
                        return@runOnUiThread
                    }

                    val traceMessage = if (scan.visualTrace.isNotEmpty()) {
                        "Também detectei a linha colorida do trajeto."
                    } else {
                        "Não consegui isolar a linha da rota; preciso da linha do percurso visível para montar com segurança."
                    }
                    val labels = scan.references.take(14).joinToString("\n") { "• ${it.label}" }
                    AlertDialog.Builder(this)
                        .setTitle("Rota encontrada no print")
                        .setMessage("$labels\n\n$traceMessage\nVou reconstruir a rota, encaixar nas ruas reais e mostrar uma prévia para você conferir.")
                        .setNegativeButton("Cancelar", null)
                        .setPositiveButton("Montar rota") { _, _ -> geocodeImportedRoute(scan) }
                        .show()
                    statusText.text = "Print lido • ${scan.references.size} referências"
                }
            },
            onError = {
                runOnUiThread {
                    statusText.text = "Falha ao ler a imagem: ${it.message ?: "erro desconhecido"}"
                }
            }
        )
    }

    private fun geocodeImportedRoute(scan: ImageRouteImporter.ScanResult) {
        statusText.text = "Localizando as ruas e reconstruindo a rota..."
        resolveLocationBias { bias ->
            imageImporter.geocodeCandidates(
                candidates = scan.references,
                biasLatitude = bias?.latitude,
                biasLongitude = bias?.longitude,
                onSuccess = { streets ->
                    val reconstructed = imageImporter.reconstructRoute(scan, streets)
                    runOnUiThread {
                        if (reconstructed.points.size < 2) {
                            statusText.text = "Não consegui montar uma rota confiável a partir desse print"
                            toast("A importação foi rejeitada para evitar uma rota errada. Tente um print maior, com a linha do percurso e nomes de ruas bem visíveis.")
                        } else {
                            showImportedRoutePreview(streets, reconstructed)
                        }
                    }
                },
                onError = {
                    runOnUiThread {
                        statusText.text = "Não consegui localizar as ruas da imagem"
                        toast(it.message ?: "Falha na geocodificação")
                    }
                }
            )
        }
    }

    private fun showImportedRoutePreview(
        streets: List<ImageRouteImporter.GeocodedStreet>,
        reconstructed: ImageRouteImporter.ReconstructedRoute
    ) {
        val now = System.currentTimeMillis()
        val points = reconstructed.points.mapIndexed { index, point ->
            RoutePoint(
                latitude = point.latitude,
                longitude = point.longitude,
                timestamp = now + index,
                accuracy = 0f,
                speed = 0f,
                bearing = 0f,
                altitude = null
            )
        }

        val temporary = RecordedRoute(
            id = now,
            name = "Rota importada",
            startedAt = now,
            finishedAt = now,
            distanceMeters = routeDistance(points),
            points = points
        )

        drawSavedRoute(temporary)
        val method = if (reconstructed.usedVisualTrace) {
            "Linha visual + nomes das ruas + encaixe viário"
        } else {
            "Encaixe viário"
        }
        val labels = streets.take(10).joinToString("\n") { "• ${it.label}" }
        AlertDialog.Builder(this)
            .setTitle("Prévia da rota importada")
            .setMessage(
                "$labels\n\nMétodo: $method\nConfiança estimada: ${reconstructed.confidence}%\n\nConfira o desenho no mapa antes de salvar."
            )
            .setNegativeButton("Descartar") { _, _ ->
                lastSavedRoute?.let(::drawSavedRoute) ?: removeSavedRouteFromMap()
            }
            .setPositiveButton("Salvar rota") { _, _ ->
                askRouteNameAndSave(temporary, "Ex.: Linha 203 - print da internet")
            }
            .show()
        statusText.text = "Prévia montada • ${points.size} pontos • ${reconstructed.confidence}%"
    }

    @SuppressLint("MissingPermission")
    private fun resolveLocationBias(onResolved: (Location?) -> Unit) {
        currentLocation?.let {
            onResolved(it)
            return
        }
        val servicePoint = recordingService?.snapshot()?.points?.lastOrNull()
        if (servicePoint != null) {
            onResolved(toLocation(servicePoint))
            return
        }
        if (!hasLocationPermission()) {
            onResolved(null)
            return
        }
        fusedLocationClient.lastLocation
            .addOnSuccessListener(onResolved)
            .addOnFailureListener { onResolved(null) }
    }

    private fun routeDistance(points: List<RoutePoint>): Float {
        if (points.size < 2) return 0f
        var total = 0f
        for (i in 1 until points.size) total += distanceMeters(points[i - 1], points[i])
        return total
    }

    private fun distanceMeters(a: RoutePoint, b: RoutePoint): Float =
        toLocation(a).distanceTo(toLocation(b))

    private fun toLocation(point: RoutePoint): Location = Location("rota-certa").apply {
        latitude = point.latitude
        longitude = point.longitude
        accuracy = point.accuracy
        speed = point.speed
        bearing = point.bearing
        time = point.timestamp
    }

    private fun drawSavedRoute(route: RecordedRoute) {
        if (!mapStyleReady) return
        val points = route.points.map { LatLng(it.latitude, it.longitude) }
        removeSavedRouteFromMap()

        if (points.size >= 2) {
            savedRouteOutline = map?.addPolyline(
                PolylineOptions().addAll(points).width(19f).color(Color.rgb(18, 38, 63))
            )
            savedRoutePolyline = map?.addPolyline(
                PolylineOptions().addAll(points).width(11f).color(Color.rgb(28, 126, 247))
            )
            runCatching {
                val bounds = LatLngBounds.fromLatLngs(points)
                map?.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 90))
            }
        }
    }

    private fun removeSavedRouteFromMap() {
        savedRouteOutline?.let { map?.removeAnnotation(it) }
        savedRoutePolyline?.let { map?.removeAnnotation(it) }
        savedRouteOutline = null
        savedRoutePolyline = null
    }

    @SuppressLint("MissingPermission")
    private fun startLiveLocationUpdates() {
        if (!hasLocationPermission() || liveLocationUpdatesActive) return
        liveLocationUpdatesActive = true

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_INTERVAL_MS)
            .setMinUpdateIntervalMillis(LOCATION_FASTEST_INTERVAL_MS)
            .setMinUpdateDistanceMeters(LOCATION_MIN_DISTANCE_METERS)
            .setWaitForAccurateLocation(false)
            .build()

        fusedLocationClient.requestLocationUpdates(request, liveLocationCallback, Looper.getMainLooper())
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            location?.let { renderLiveLocation(it, forceFollow = !centeredOnUserOnce) }
        }
    }

    private fun stopLiveLocationUpdates() {
        if (!liveLocationUpdatesActive) return
        fusedLocationClient.removeLocationUpdates(liveLocationCallback)
        liveLocationUpdatesActive = false
    }

    private fun renderLiveLocation(location: Location, forceFollow: Boolean) {
        if (location.accuracy > MAX_RENDER_ACCURACY_METERS) return
        currentLocation = location
        if (!mapStyleReady) return

        val latLng = LatLng(location.latitude, location.longitude)
        val currentMap = map ?: return
        val marker = locationMarker
        if (marker == null) {
            locationMarker = currentMap.addMarker(
                MarkerOptions().position(latLng).title("Você está aqui")
            )
        } else {
            marker.position = latLng
        }

        val shouldFollow = forceFollow || recordingUiActive || localNavigationRoute != null || !centeredOnUserOnce
        if (shouldFollow) {
            centerMapOn(
                location = location,
                driving = recordingUiActive || localNavigationRoute != null,
                immediate = !centeredOnUserOnce
            )
            centeredOnUserOnce = true
        }

        if (localNavigationRoute != null) {
            updateLocalNavigation(location)
        } else if (!recordingUiActive) {
            statusText.text = String.format(
                Locale.getDefault(),
                "GPS ativo • precisão %.0f m",
                location.accuracy
            )
        }
    }

    private fun centerMapOn(location: Location, driving: Boolean, immediate: Boolean) {
        val currentMap = map ?: return
        val now = System.currentTimeMillis()
        if (!immediate && now - lastCameraFollowAt < CAMERA_MIN_INTERVAL_MS) return
        lastCameraFollowAt = now

        val baseTarget = LatLng(location.latitude, location.longitude)
        if (driving) {
            val hasReliableBearing = location.hasBearing() && location.speed >= MIN_BEARING_SPEED_MPS
            val bearing = if (hasReliableBearing) {
                location.bearing.toDouble()
            } else {
                currentMap.cameraPosition.bearing
            }

            val navigating = localNavigationRoute != null
            val lookAheadMeters = if (navigating) {
                navigationLookAheadMeters(location)
            } else {
                RECORDING_LOOK_AHEAD_METERS
            }
            val target = if (hasReliableBearing) {
                projectAhead(location, bearing, lookAheadMeters)
            } else {
                baseTarget
            }

            val camera = CameraPosition.Builder()
                .target(target)
                .zoom(if (navigating) navigationZoom(location) else RECORDING_ZOOM)
                .bearing(bearing)
                .tilt(if (navigating) NAVIGATION_TILT else RECORDING_TILT)
                .build()
            if (immediate) currentMap.moveCamera(CameraUpdateFactory.newCameraPosition(camera))
            else currentMap.animateCamera(CameraUpdateFactory.newCameraPosition(camera), CAMERA_ANIMATION_MS)
        } else {
            val update = CameraUpdateFactory.newLatLngZoom(baseTarget, IDLE_ZOOM)
            if (immediate) currentMap.moveCamera(update)
            else currentMap.animateCamera(update, CAMERA_ANIMATION_MS)
        }
    }

    private fun navigationZoom(location: Location): Double {
        val speed = if (location.hasSpeed()) location.speed else 0f
        return when {
            speed >= 24f -> 17.85
            speed >= 15f -> 18.10
            speed >= 8f -> 18.35
            else -> 18.65
        }
    }

    private fun navigationLookAheadMeters(location: Location): Double {
        val speed = if (location.hasSpeed()) location.speed else 0f
        return when {
            speed >= 24f -> 105.0
            speed >= 15f -> 85.0
            speed >= 8f -> 65.0
            else -> 45.0
        }
    }

    private fun projectAhead(location: Location, bearingDegrees: Double, distanceMeters: Double): LatLng {
        val angularDistance = distanceMeters / EARTH_RADIUS_METERS
        val bearing = Math.toRadians(bearingDegrees)
        val latitude = Math.toRadians(location.latitude)
        val longitude = Math.toRadians(location.longitude)

        val projectedLatitude = asin(
            sin(latitude) * cos(angularDistance) +
                cos(latitude) * sin(angularDistance) * cos(bearing)
        )
        val projectedLongitude = longitude + atan2(
            sin(bearing) * sin(angularDistance) * cos(latitude),
            cos(angularDistance) - sin(latitude) * sin(projectedLatitude)
        )

        return LatLng(
            Math.toDegrees(projectedLatitude),
            Math.toDegrees(projectedLongitude)
        )
    }

    private fun formatDistance(distanceMeters: Float): String =
        if (distanceMeters < 1_000f) "${distanceMeters.roundToInt()} m"
        else String.format(Locale.getDefault(), "%.1f km", distanceMeters / 1_000f)

    private fun spokenDistance(distanceMeters: Float): String =
        if (distanceMeters < 1_000f) "${(distanceMeters / 10f).roundToInt() * 10} metros"
        else String.format(Locale.getDefault(), "%.1f quilômetros", distanceMeters / 1_000f)

    private fun speak(message: String) {
        if (!textToSpeechReady) return
        textToSpeech?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "rota-certa-guidance")
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun bindRecordingService() {
        if (bindRequested) return
        bindRequested = bindService(
            Intent(this, RouteRecordingService::class.java),
            recordingConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    private fun unbindRecordingService() {
        if (!bindRequested) return
        recordingService?.setListener(null)
        runCatching { unbindService(recordingConnection) }
        bindRequested = false
        recordingService = null
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onStart() {
        super.onStart()
        mapView.onStart()
        bindRecordingService()
        lastSavedRoute = routeStore.loadLast() ?: lastSavedRoute
        if (hasLocationPermission()) startLiveLocationUpdates()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        stopLiveLocationUpdates()
        unbindRecordingService()
        mapView.onStop()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        mapView.onSaveInstanceState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onDestroy() {
        stopLiveLocationUpdates()
        imageImporter.close()
        streetLookupExecutor.shutdownNow()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        mapView.onDestroy()
        super.onDestroy()
    }

    companion object {
        private const val OPENFREE_BRIGHT_STYLE_URI = "https://tiles.openfreemap.org/styles/bright"
        private const val OPENFREE_LIBERTY_STYLE_URI = "https://tiles.openfreemap.org/styles/liberty"
        private const val VECTOR_STYLE_FALLBACK_DELAY_MS = 5_000L
        private const val RASTER_STYLE_FALLBACK_DELAY_MS = 10_000L

        private const val LOCATION_INTERVAL_MS = 700L
        private const val LOCATION_FASTEST_INTERVAL_MS = 350L
        private const val LOCATION_MIN_DISTANCE_METERS = 1.0f
        private const val MAX_RENDER_ACCURACY_METERS = 80f

        private const val CAMERA_MIN_INTERVAL_MS = 450L
        private const val CAMERA_ANIMATION_MS = 520
        private const val MIN_BEARING_SPEED_MPS = 1.2f
        private const val IDLE_ZOOM = 17.4
        private const val RECORDING_ZOOM = 18.15
        private const val RECORDING_TILT = 36.0
        private const val NAVIGATION_TILT = 55.0
        private const val RECORDING_LOOK_AHEAD_METERS = 32.0
        private const val EARTH_RADIUS_METERS = 6_371_000.0

        private const val LOCAL_TURN_DEGREES = 42f
        private const val MIN_TURN_LOOKAHEAD_METERS = 25f
        private const val TURN_VOICE_DISTANCE_METERS = 180f
        private const val OFF_ROUTE_DISTANCE_METERS = 80f
        private const val DESTINATION_RADIUS_METERS = 35f
        private const val MIN_EXTRA_BOTTOM_DP = 10

        private val OSM_FALLBACK_STYLE_JSON = """
            {
              "version": 8,
              "name": "Rota Certa fallback",
              "sources": {
                "osm": {
                  "type": "raster",
                  "tiles": ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
                  "tileSize": 256,
                  "attribution": "© OpenStreetMap contributors",
                  "maxzoom": 19
                }
              },
              "layers": [
                {
                  "id": "osm-base",
                  "type": "raster",
                  "source": "osm"
                }
              ]
            }
        """.trimIndent()
    }
}
