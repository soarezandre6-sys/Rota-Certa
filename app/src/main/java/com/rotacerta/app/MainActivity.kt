package com.rotacerta.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.NavigationView
import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.navigation.Waypoint
import com.rotacerta.app.data.RouteStore
import com.rotacerta.app.importer.ImageRouteImporter
import com.rotacerta.app.location.RouteRecordingService
import com.rotacerta.app.model.RecordedRoute
import com.rotacerta.app.model.RoutePoint
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity() {
    private lateinit var navView: NavigationView
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var routeStore: RouteStore
    private lateinit var imageImporter: ImageRouteImporter

    private val fusedLocationClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }

    private var map: GoogleMap? = null
    private var recordedPolyline: Polyline? = null
    private var savedRoutePolyline: Polyline? = null
    private var navigator: Navigator? = null
    private var arrivalListener: Navigator.ArrivalListener? = null
    private var lastSavedRoute: RecordedRoute? = null

    private var recordingService: RouteRecordingService? = null
    private var recordingServiceBound = false
    private var bindRequested = false
    private var pendingStartRecording = false
    private var recordingUiActive = false

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
            enableMyLocationIfAllowed()
            startRecordingNow()
        } else {
            toast("Permissão de localização é necessária para gravar a rota.")
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* A gravação continua mesmo se o usuário recusar a notificação visível. */ }

    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let(::readRouteImage)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        navView = findViewById(R.id.navigation_view)
        statusText = findViewById(R.id.status_text)
        startButton = findViewById(R.id.start_button)
        stopButton = findViewById(R.id.stop_button)

        routeStore = RouteStore(this)
        imageImporter = ImageRouteImporter(this)
        lastSavedRoute = routeStore.loadLast()

        navView.onCreate(savedInstanceState)
        navView.getMapAsync { googleMap ->
            map = googleMap
            enableMyLocationIfAllowed()
            lastSavedRoute?.let(::drawSavedRoute)
        }

        initializeNavigation()

        startButton.setOnClickListener { startRecording() }
        stopButton.setOnClickListener { stopAndNameRoute() }
        findViewById<Button>(R.id.saved_routes_button).setOnClickListener { showSavedRoutes() }
        findViewById<Button>(R.id.import_image_button).setOnClickListener {
            imagePicker.launch("image/*")
        }
        findViewById<Button>(R.id.navigate_last_button).setOnClickListener {
            lastSavedRoute?.let(::navigateRoute) ?: toast("Selecione ou grave uma rota primeiro.")
        }
    }

    private fun initializeNavigation() {
        NavigationApi.getNavigator(
            this,
            object : NavigationApi.NavigatorListener {
                override fun onNavigatorReady(readyNavigator: Navigator) {
                    navigator = readyNavigator
                    // O comportamento padrão do SDK mantém orientação e notificação se o app sair da tela.
                    registerArrivalListener(readyNavigator)
                    statusText.text = "GPS e navegação prontos"
                }

                override fun onError(errorCode: Int) {
                    statusText.text = "Navegação indisponível • código $errorCode"
                }
            }
        )
    }

    private fun registerArrivalListener(nav: Navigator) {
        arrivalListener?.let(nav::removeArrivalListener)
        arrivalListener = Navigator.ArrivalListener { event ->
            if (event.isFinalDestination) {
                statusText.text = "Destino final alcançado"
            } else {
                nav.continueToNextDestination()
                nav.startGuidance()
                statusText.text = "Seguindo o próximo trecho da rota salva"
            }
        }
        arrivalListener?.let(nav::addArrivalListener)
    }

    private fun startRecording() {
        if (hasLocationPermission()) {
            startRecordingNow()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun startRecordingNow() {
        maybeRequestNotificationPermission()
        pendingStartRecording = true
        recordingUiActive = true
        recordedPolyline?.remove()
        recordedPolyline = map?.addPolyline(PolylineOptions().width(12f))
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
            if (recordedPolyline == null) {
                recordedPolyline = map?.addPolyline(PolylineOptions().width(12f))
            }
            recordedPolyline?.points = latLngs
            statusText.text = String.format(
                Locale.getDefault(),
                "Gravando • %.2f km • %d pontos",
                snapshot.distanceMeters / 1_000f,
                latLngs.size
            )
        } else if (recordingUiActive && !pendingStartRecording) {
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
                val selected = routes[which]
                lastSavedRoute = selected
                drawSavedRoute(selected)
                statusText.text = "Rota selecionada: ${selected.name}"
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun navigateRoute(route: RecordedRoute) {
        if (!isOnline()) {
            statusText.text = "Sem internet • a navegação em tempo real precisa de conexão"
            return
        }

        val nav = navigator ?: run {
            toast("Navegação ainda está inicializando.")
            return
        }
        if (route.points.size < 2) {
            toast("A rota salva não tem pontos suficientes.")
            return
        }

        val sampled = selectNavigationPoints(route.points, MAX_NAVIGATION_WAYPOINTS)
        val waypoints = sampled.mapIndexed { index, point ->
            Waypoint.builder()
                .setLatLng(point.latitude, point.longitude)
                .setTitle(if (index == sampled.lastIndex) route.name else "Trecho ${index + 1}")
                .setVehicleStopover(index == sampled.lastIndex)
                .build()
        }

        statusText.text = "Calculando a rota salva pela rede viária..."
        nav.setDestinations(waypoints)?.setOnResultListener { code ->
            when (code) {
                Navigator.RouteStatus.OK -> {
                    nav.setAudioGuidance(Navigator.AudioGuidance.VOICE_ALERTS_AND_GUIDANCE)
                    runCatching { nav.setHeadsUpNotificationEnabled(true) }
                    nav.startGuidance()
                    statusText.text = "Navegando em tempo real: ${route.name}"
                }
                Navigator.RouteStatus.NETWORK_ERROR -> statusText.text = "Sem internet para calcular a rota"
                Navigator.RouteStatus.ROUTING_TIMEOUT -> statusText.text = "Tempo esgotado ao calcular a rota"
                Navigator.RouteStatus.WAYPOINT_ERROR -> statusText.text = "Um ponto da rota não pôde ser usado"
                Navigator.RouteStatus.DUPLICATE_WAYPOINTS_ERROR -> statusText.text = "A rota tem pontos repetidos demais"
                Navigator.RouteStatus.LOCATION_DISABLED -> statusText.text = "Ative o GPS do aparelho"
                Navigator.RouteStatus.LOCATION_UNKNOWN -> statusText.text = "Aguardando uma posição GPS válida"
                Navigator.RouteStatus.NO_ROUTE_FOUND -> statusText.text = "Não encontrei uma via válida entre os pontos"
                Navigator.RouteStatus.QUOTA_CHECK_FAILED -> statusText.text = "Serviço de navegação sem cota/chave válida"
                else -> statusText.text = "Não foi possível iniciar a navegação: $code"
            }
        }
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
                lastSavedRoute?.let(::drawSavedRoute) ?: run { savedRoutePolyline?.remove() }
            }
            .setPositiveButton("Salvar rota") { _, _ ->
                askRouteNameAndSave(temporary, "Ex.: Linha 203 - print da internet")
            }
            .show()
        statusText.text = "Prévia montada • ${streets.size} pontos geográficos"
    }

    @SuppressLint("MissingPermission")
    private fun resolveLocationBias(onResolved: (Location?) -> Unit) {
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
        val points = route.points.map { LatLng(it.latitude, it.longitude) }
        savedRoutePolyline?.remove()
        if (points.size >= 2) {
            savedRoutePolyline = map?.addPolyline(PolylineOptions().addAll(points).width(10f))
            val boundsBuilder = LatLngBounds.builder()
            points.forEach(boundsBuilder::include)
            runCatching {
                map?.animateCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 80))
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableMyLocationIfAllowed() {
        if (hasLocationPermission()) map?.isMyLocationEnabled = true
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
        navView.onStart()
        bindRecordingService()
    }

    override fun onResume() {
        super.onResume()
        navView.onResume()
    }

    override fun onPause() {
        navView.onPause()
        super.onPause()
    }

    override fun onStop() {
        unbindRecordingService()
        navView.onStop()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        navView.onSaveInstanceState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        navView.onTrimMemory(level)
    }

    override fun onDestroy() {
        imageImporter.close()
        val nav = navigator
        val listener = arrivalListener
        if (nav != null && listener != null) nav.removeArrivalListener(listener)
        nav?.cleanup()
        navView.onDestroy()
        super.onDestroy()
    }

    companion object {
        private const val MAX_NAVIGATION_WAYPOINTS = 25
        private const val MIN_WAYPOINT_SPACING_METERS = 18f
        private const val IMPORTANT_TURN_DEGREES = 32f
    }
}
