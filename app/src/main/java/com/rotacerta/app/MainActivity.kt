package com.rotacerta.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.navigation.Waypoint
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
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private lateinit var mapView: MapView
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var navigateButton: Button
    private lateinit var centerLocationButton: ImageButton
    private lateinit var routeStore: RouteStore
    private lateinit var imageImporter: ImageRouteImporter

    private val fusedLocationClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }

    private var map: MapLibreMap? = null
    private var mapStyleReady = false
    private var recordedPolyline: Polyline? = null
    private var savedRoutePolyline: Polyline? = null
    private var locationMarker: Marker? = null
    private var currentLocation: Location? = null
    private var liveLocationUpdatesActive = false
    private var centeredOnUserOnce = false

    private var navigator: Navigator? = null
    private var arrivalListener: Navigator.ArrivalListener? = null
    private var lastSavedRoute: RecordedRoute? = null

    private var localNavigationRoute: RecordedRoute? = null
    private var lastSpokenTurnIndex = -1
    private var offRouteAnnounced = false
    private var textToSpeech: TextToSpeech? = null
    private var textToSpeechReady = false

    private var recordingService: RouteRecordingService? = null
    private var recordingServiceBound = false
    private var bindRequested = false
    private var pendingStartRecording = false
    private var recordingUiActive = false
    private var startRecordingAfterPermission = false

    private val liveLocationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let(::renderLiveLocation)
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
            recordingServiceBound = true
            recordingService?.setListener(recordingListener)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recordingService?.setListener(null)
            recordingService = null
            recordingServiceBound = false
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
    ) { /* A gravação continua se o usuário recusar a notificação. */ }

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
            mapLibreMap.setStyle(Style.Builder().fromJson(OSM_STYLE_JSON)) {
                mapStyleReady = true
                lastSavedRoute?.let(::drawSavedRoute)
                currentLocation?.let(::renderLiveLocation)
                if (!recordingUiActive && localNavigationRoute == null) {
                    statusText.text = "Mapa e GPS prontos"
                }
            }
        }

        if (BuildConfig.GOOGLE_NAV_CONFIGURED) {
            initializeGoogleNavigation()
        }

        startButton.setOnClickListener { startRecording() }
        stopButton.setOnClickListener { stopAndNameRoute() }
        findViewById<Button>(R.id.saved_routes_button).setOnClickListener { showSavedRoutes() }
        findViewById<Button>(R.id.import_image_button).setOnClickListener {
            imagePicker.launch("image/*")
        }
        navigateButton.setOnClickListener {
            if (localNavigationRoute != null) {
                stopNavigation()
            } else {
                lastSavedRoute?.let(::navigateRoute) ?: toast("Selecione ou grave uma rota primeiro.")
            }
        }
        centerLocationButton.setOnClickListener {
            val location = currentLocation
            if (location != null) centerMapOn(location, navigating = localNavigationRoute != null)
            else toast("Aguardando uma posição GPS válida.")
        }

        if (hasLocationPermission()) {
            startLiveLocationUpdates()
        } else {
            requestLocationPermission(startRecordingAfter = false)
        }
    }

    private fun applySystemBarInsets() {
        val panel = findViewById<View>(R.id.control_panel)
        val baseBottomPadding = panel.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(panel) { view, insets ->
            val navigation = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.updatePadding(bottom = baseBottomPadding + navigation.bottom)
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(centerLocationButton) { view, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.translationY = statusBars.top.toFloat()
            insets
        }

        ViewCompat.requestApplyInsets(panel)
        ViewCompat.requestApplyInsets(centerLocationButton)
    }

    private fun initializeGoogleNavigation() {
        NavigationApi.getNavigator(
            this,
            object : NavigationApi.NavigatorListener {
                override fun onNavigatorReady(readyNavigator: Navigator) {
                    navigator = readyNavigator
                    registerArrivalListener(readyNavigator)
                }

                override fun onError(errorCode: Int) {
                    // O mapa e o modo de seguir a rota continuam funcionando sem o SDK Google.
                    if (localNavigationRoute == null && !recordingUiActive) {
                        statusText.text = "Mapa/GPS prontos • navegação Google indisponível ($errorCode)"
                    }
                }
            }
        )
    }

    private fun registerArrivalListener(nav: Navigator) {
        arrivalListener?.let(nav::removeArrivalListener)
        arrivalListener = Navigator.ArrivalListener { event ->
            if (event.isFinalDestination) {
                nav.stopGuidance()
                stopLocalNavigation(updateStatus = false)
                statusText.text = "Destino final alcançado"
                speak("Você chegou ao destino")
            } else {
                nav.continueToNextDestination()
                nav.startGuidance()
            }
        }
        arrivalListener?.let(nav::addArrivalListener)
    }

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
        if (hasLocationPermission()) {
            startRecordingNow()
        } else {
            requestLocationPermission(startRecordingAfter = true)
        }
    }

    private fun startRecordingNow() {
        if (localNavigationRoute != null) stopNavigation()
        maybeRequestNotificationPermission()
        pendingStartRecording = true
        recordingUiActive = true
        recordedPolyline?.let { map?.removeAnnotation(it) }
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
                if (recordedPolyline == null) {
                    recordedPolyline = map?.addPolyline(
                        PolylineOptions()
                            .addAll(latLngs)
                            .width(8f)
                            .color(Color.rgb(30, 136, 229))
                    )
                } else {
                    recordedPolyline?.points = latLngs
                }
            }

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

        val final = service.stopRecording()
        pendingStartRecording = false
        recordingUiActive = false
        startButton.isEnabled = true
        stopButton.isEnabled = false

        if (final.points.size < 2) {
            statusText.text = "Rota curta demais para salvar"
            return
        }

        val route = RecordedRoute(
            id = System.currentTimeMillis(),
            name = "",
            startedAt = final.startedAt,
            finishedAt = System.currentTimeMillis(),
            distanceMeters = final.distanceMeters,
            points = final.points
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
        drawSavedRoute(route)
        localNavigationRoute = route
        lastSpokenTurnIndex = -1
        offRouteAnnounced = false
        navigateButton.text = "ENCERRAR NAVEGAÇÃO"
        currentLocation?.let(::updateLocalNavigation)
        if (currentLocation == null) statusText.text = "Navegação iniciada • aguardando GPS..."

        if (BuildConfig.GOOGLE_NAV_CONFIGURED && isOnline()) {
            startGoogleGuidance(route)
        } else {
            statusText.text = "Navegando pela rota gravada • GPS em tempo real"
        }
    }

    private fun startGoogleGuidance(route: RecordedRoute) {
        val nav = navigator ?: return
        val sampled = selectNavigationPoints(route.points, MAX_NAVIGATION_WAYPOINTS)
        val waypoints = sampled.mapIndexed { index, point ->
            Waypoint.builder()
                .setLatLng(point.latitude, point.longitude)
                .setTitle(if (index == sampled.lastIndex) route.name else "Trecho ${index + 1}")
                .setVehicleStopover(index == sampled.lastIndex)
                .build()
        }

        nav.setDestinations(waypoints)?.setOnResultListener { code ->
            when (code) {
                Navigator.RouteStatus.OK -> {
                    nav.setAudioGuidance(Navigator.AudioGuidance.VOICE_ALERTS_AND_GUIDANCE)
                    runCatching { nav.setHeadsUpNotificationEnabled(true) }
                    nav.startGuidance()
                }
                Navigator.RouteStatus.NETWORK_ERROR -> statusText.text = "Navegação local ativa • Google sem rede"
                Navigator.RouteStatus.ROUTING_TIMEOUT -> statusText.text = "Navegação local ativa • Google demorou para calcular"
                Navigator.RouteStatus.WAYPOINT_ERROR -> statusText.text = "Navegação local ativa • ponto Google inválido"
                Navigator.RouteStatus.NO_ROUTE_FOUND -> statusText.text = "Navegação local ativa • Google não achou a via"
                else -> statusText.text = "Navegação local ativa • Google indisponível ($code)"
            }
        }
    }

    private fun stopNavigation() {
        runCatching { navigator?.stopGuidance() }
        stopLocalNavigation(updateStatus = true)
    }

    private fun stopLocalNavigation(updateStatus: Boolean) {
        localNavigationRoute = null
        lastSpokenTurnIndex = -1
        offRouteAnnounced = false
        navigateButton.text = "NAVEGAR NA ROTA SELECIONADA"
        if (updateStatus) statusText.text = "Navegação encerrada"
    }

    private fun updateLocalNavigation(location: Location) {
        val route = localNavigationRoute ?: return
        val points = route.points
        if (points.size < 2) return

        val nearestIndex = nearestRouteIndex(location, points)
        val nearestPoint = toLocation(points[nearestIndex])
        val deviation = location.distanceTo(nearestPoint)
        val destinationDistance = location.distanceTo(toLocation(points.last()))

        if (destinationDistance <= DESTINATION_RADIUS_METERS) {
            speak("Você chegou ao destino")
            runCatching { navigator?.stopGuidance() }
            stopLocalNavigation(updateStatus = false)
            statusText.text = "Destino alcançado • ${route.name}"
            return
        }

        if (deviation > OFF_ROUTE_DISTANCE_METERS) {
            statusText.text = "Fora da rota • aprox. ${formatDistance(deviation)} do trajeto"
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
            statusText.text = "Navegando • ${formatDistance(turnDistance)} • vire à $direction"

            if (turnDistance <= TURN_VOICE_DISTANCE_METERS && nextTurnIndex != lastSpokenTurnIndex) {
                speak("Em ${spokenDistance(turnDistance)}, vire à $direction")
                lastSpokenTurnIndex = nextTurnIndex
            }
        } else {
            statusText.text = "Navegando • siga em frente • ${formatDistance(destinationDistance)} até o destino"
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

    private fun selectNavigationPoints(points: List<RoutePoint>, maxWaypoints: Int): List<RoutePoint> {
        if (points.size <= 2) return points

        val cleaned = mutableListOf(points.first())
        points.drop(1).forEach { point ->
            val previous = cleaned.last()
            if (distanceMeters(previous, point) >= MIN_WAYPOINT_SPACING_METERS) cleaned += point
        }
        if (cleaned.last() != points.last()) cleaned += points.last()
        if (cleaned.size <= maxWaypoints) return cleaned

        val selected = linkedSetOf(0, cleaned.lastIndex)
        val turns = (1 until cleaned.lastIndex)
            .map { index -> index to turnAngle(cleaned[index - 1], cleaned[index], cleaned[index + 1]) }
            .filter { (_, angle) -> angle >= IMPORTANT_TURN_DEGREES }
            .sortedByDescending { (_, angle) -> angle }

        turns.take((maxWaypoints / 2).coerceAtLeast(1)).forEach { (index, _) ->
            if (selected.size < maxWaypoints) selected += index
        }

        val cumulative = FloatArray(cleaned.size)
        for (i in 1 until cleaned.size) {
            cumulative[i] = cumulative[i - 1] + distanceMeters(cleaned[i - 1], cleaned[i])
        }
        val total = cumulative.last().coerceAtLeast(1f)

        for (slot in 1 until maxWaypoints - 1) {
            if (selected.size >= maxWaypoints) break
            val target = total * slot / (maxWaypoints - 1f)
            val nearest = cumulative.indices.minByOrNull { abs(cumulative[it] - target) } ?: continue
            selected += nearest
        }

        if (selected.size < maxWaypoints) {
            for (index in cleaned.indices) {
                if (selected.size >= maxWaypoints) break
                selected += index
            }
        }

        return selected.sorted().map(cleaned::get)
    }

    private fun turnAngle(a: RoutePoint, b: RoutePoint, c: RoutePoint): Float {
        val firstBearing = toLocation(a).bearingTo(toLocation(b))
        val secondBearing = toLocation(b).bearingTo(toLocation(c))
        return abs(((secondBearing - firstBearing + 540f) % 360f) - 180f)
    }

    private fun readRouteImage(uri: Uri) {
        statusText.text = "Lendo ruas e referências da imagem..."
        imageImporter.readStreetCandidates(
            uri = uri,
            onSuccess = { candidates ->
                runOnUiThread {
                    if (candidates.isEmpty()) {
                        statusText.text = "Não encontrei nomes de ruas úteis nessa imagem"
                    } else {
                        AlertDialog.Builder(this)
                            .setTitle("Referências encontradas")
                            .setMessage(
                                candidates.take(16).joinToString("\n") +
                                    "\n\nVou cruzar estes nomes com sua região atual e montar uma rota para você conferir."
                            )
                            .setNegativeButton("Cancelar", null)
                            .setPositiveButton("Montar rota") { _, _ ->
                                geocodeImportedRoute(candidates)
                            }
                            .show()
                        statusText.text = "Imagem lida • ${candidates.size} referências"
                    }
                }
            },
            onError = {
                runOnUiThread { statusText.text = "Falha ao ler a imagem: ${it.message ?: "erro desconhecido"}" }
            }
        )
    }

    private fun geocodeImportedRoute(candidates: List<String>) {
        statusText.text = "Localizando as ruas da imagem..."
        resolveLocationBias { bias ->
            imageImporter.geocodeCandidates(
                candidates = candidates,
                biasLatitude = bias?.latitude,
                biasLongitude = bias?.longitude,
                onSuccess = { streets ->
                    runOnUiThread {
                        if (streets.size < 2) {
                            statusText.text = "A imagem não trouxe ruas suficientes para montar uma rota"
                            toast("Tente um print com nomes de ruas mais legíveis e da mesma região em que você está.")
                        } else {
                            showImportedRoutePreview(streets)
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

    private fun showImportedRoutePreview(streets: List<ImageRouteImporter.GeocodedStreet>) {
        val now = System.currentTimeMillis()
        val points = streets.mapIndexed { index, street ->
            RoutePoint(
                latitude = street.latitude,
                longitude = street.longitude,
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
        val labels = streets.joinToString("\n") { "• ${it.label}" }
        AlertDialog.Builder(this)
            .setTitle("Prévia da rota importada")
            .setMessage(
                "$labels\n\nConfira o desenho no mapa. Como o print não contém coordenadas completas, esta é uma reconstrução baseada nos nomes de ruas detectados."
            )
            .setNegativeButton("Descartar") { _, _ ->
                lastSavedRoute?.let(::drawSavedRoute) ?: savedRoutePolyline?.let { map?.removeAnnotation(it) }
            }
            .setPositiveButton("Salvar rota") { _, _ ->
                askRouteNameAndSave(temporary, "Ex.: Linha 203 - print da internet")
            }
            .show()
        statusText.text = "Prévia montada • ${streets.size} pontos geográficos"
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

    private fun distanceMeters(a: RoutePoint, b: RoutePoint): Float = toLocation(a).distanceTo(toLocation(b))

    private fun toLocation(point: RoutePoint): Location = Location("rota-certa").apply {
        latitude = point.latitude
        longitude = point.longitude
    }

    private fun drawSavedRoute(route: RecordedRoute) {
        if (!mapStyleReady) return
        val points = route.points.map { LatLng(it.latitude, it.longitude) }
        savedRoutePolyline?.let { map?.removeAnnotation(it) }
        savedRoutePolyline = null

        if (points.size >= 2) {
            savedRoutePolyline = map?.addPolyline(
                PolylineOptions()
                    .addAll(points)
                    .width(9f)
                    .color(Color.rgb(99, 102, 241))
            )
            runCatching {
                val bounds = LatLngBounds.fromLatLngs(points)
                map?.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 80))
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLiveLocationUpdates() {
        if (!hasLocationPermission() || liveLocationUpdatesActive) return
        liveLocationUpdatesActive = true

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
            .setMinUpdateIntervalMillis(500L)
            .setMinUpdateDistanceMeters(1.5f)
            .build()

        fusedLocationClient.requestLocationUpdates(request, liveLocationCallback, Looper.getMainLooper())
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            location?.let(::renderLiveLocation)
        }
    }

    private fun stopLiveLocationUpdates() {
        if (!liveLocationUpdatesActive) return
        fusedLocationClient.removeLocationUpdates(liveLocationCallback)
        liveLocationUpdatesActive = false
    }

    private fun renderLiveLocation(location: Location) {
        if (location.accuracy > 100f) return
        currentLocation = location
        if (!mapStyleReady) return

        val latLng = LatLng(location.latitude, location.longitude)
        val currentMap = map ?: return
        val marker = locationMarker
        if (marker == null) {
            locationMarker = currentMap.addMarker(
                MarkerOptions()
                    .position(latLng)
                    .title("Você está aqui")
            )
        } else {
            marker.position = latLng
        }

        if (localNavigationRoute != null) {
            centerMapOn(location, navigating = true)
            updateLocalNavigation(location)
        } else if (!centeredOnUserOnce) {
            centerMapOn(location, navigating = false)
            centeredOnUserOnce = true
        }
    }

    private fun centerMapOn(location: Location, navigating: Boolean) {
        val target = LatLng(location.latitude, location.longitude)
        val currentMap = map ?: return
        if (navigating) {
            val camera = CameraPosition.Builder()
                .target(target)
                .zoom(17.5)
                .bearing(if (location.hasBearing()) location.bearing.toDouble() else currentMap.cameraPosition.bearing)
                .tilt(45.0)
                .build()
            currentMap.animateCamera(CameraUpdateFactory.newCameraPosition(camera))
        } else {
            currentMap.animateCamera(CameraUpdateFactory.newLatLngZoom(target, 17.0))
        }
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

    private fun isOnline(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
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
        recordingServiceBound = false
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
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        val nav = navigator
        val listener = arrivalListener
        if (nav != null && listener != null) nav.removeArrivalListener(listener)
        nav?.cleanup()
        mapView.onDestroy()
        super.onDestroy()
    }

    companion object {
        private const val MAX_NAVIGATION_WAYPOINTS = 25
        private const val MIN_WAYPOINT_SPACING_METERS = 18f
        private const val IMPORTANT_TURN_DEGREES = 32f
        private const val LOCAL_TURN_DEGREES = 42f
        private const val MIN_TURN_LOOKAHEAD_METERS = 25f
        private const val TURN_VOICE_DISTANCE_METERS = 120f
        private const val OFF_ROUTE_DISTANCE_METERS = 80f
        private const val DESTINATION_RADIUS_METERS = 35f

        private val OSM_STYLE_JSON = """
            {
              "version": 8,
              "name": "Rota Certa OpenStreetMap",
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
