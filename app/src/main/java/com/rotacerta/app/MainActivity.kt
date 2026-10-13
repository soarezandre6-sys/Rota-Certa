package com.rotacerta.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.NavigationView
import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.navigation.Waypoint
import com.rotacerta.app.data.RouteStore
import com.rotacerta.app.importer.ImageRouteImporter
import com.rotacerta.app.location.RouteRecorder
import com.rotacerta.app.model.RecordedRoute
import com.rotacerta.app.model.RoutePoint
import java.util.Locale
import kotlin.math.ceil

class MainActivity : AppCompatActivity() {
    private lateinit var navView: NavigationView
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var routeRecorder: RouteRecorder
    private lateinit var routeStore: RouteStore
    private lateinit var imageImporter: ImageRouteImporter

    private var map: GoogleMap? = null
    private var recordedPolyline: Polyline? = null
    private var savedRoutePolyline: Polyline? = null
    private var navigator: Navigator? = null
    private var arrivalListener: Navigator.ArrivalListener? = null
    private var startedAt: Long = 0L
    private var lastSavedRoute: RecordedRoute? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) startRecordingNow() else toast("Permissão de localização é necessária.")
    }

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

        routeRecorder = RouteRecorder(this)
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
            navigateLastSavedRoute()
        }
    }

    private fun initializeNavigation() {
        NavigationApi.getNavigator(
            this,
            object : NavigationApi.NavigatorListener {
                override fun onNavigatorReady(readyNavigator: Navigator) {
                    navigator = readyNavigator
                    readyNavigator.setTaskRemovedBehavior(Navigator.TaskRemovedBehavior.QUIT_SERVICE)
                    registerArrivalListener(readyNavigator)
                    statusText.text = "GPS e navegação prontos"
                }

                override fun onError(errorCode: Int) {
                    statusText.text = "Navegação indisponível: código $errorCode"
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
                statusText.text = "Seguindo para o próximo trecho da rota salva"
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

    @SuppressLint("MissingPermission")
    private fun startRecordingNow() {
        startedAt = System.currentTimeMillis()
        recordedPolyline?.remove()
        recordedPolyline = map?.addPolyline(PolylineOptions().width(12f))
        startButton.isEnabled = false
        stopButton.isEnabled = true
        statusText.text = "Gravando rota..."

        routeRecorder.start { _, distanceMeters ->
            runOnUiThread {
                val latLngs = routeRecorder.snapshot().map { LatLng(it.latitude, it.longitude) }
                recordedPolyline?.points = latLngs
                statusText.text = String.format(
                    Locale.getDefault(),
                    "Gravando • %.2f km • %d pontos",
                    distanceMeters / 1000f,
                    latLngs.size
                )
            }
        }
    }

    private fun stopAndNameRoute() {
        val points = routeRecorder.stop()
        startButton.isEnabled = true
        stopButton.isEnabled = false

        if (points.size < 2) {
            statusText.text = "Rota curta demais para salvar"
            return
        }

        val input = EditText(this).apply { hint = "Ex.: Linha 203 Centro - Terminal" }
        AlertDialog.Builder(this)
            .setTitle("Salvar rota")
            .setMessage("Dê um nome para reconhecer este trajeto depois.")
            .setView(input)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar") { _, _ ->
                val now = System.currentTimeMillis()
                val route = RecordedRoute(
                    id = now,
                    name = input.text.toString().trim().ifBlank { "Rota ${routeStore.loadAll().size + 1}" },
                    startedAt = startedAt,
                    finishedAt = now,
                    distanceMeters = routeRecorder.currentDistanceMeters(),
                    points = points
                )
                routeStore.save(route)
                lastSavedRoute = route
                drawSavedRoute(route)
                statusText.text = "${route.name} salva • ${"%.2f".format(route.distanceMeters / 1000f)} km"
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
            "${route.name} • ${"%.2f".format(route.distanceMeters / 1000f)} km"
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

    private fun navigateLastSavedRoute() {
        val route = lastSavedRoute ?: run {
            toast("Selecione ou grave uma rota primeiro.")
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

        val sampled = samplePoints(route.points, 24)
        val waypoints = sampled.mapIndexed { index, point ->
            Waypoint.builder()
                .setLatLng(point.latitude, point.longitude)
                .setTitle(if (index == sampled.lastIndex) route.name else "Trecho ${index + 1}")
                .setVehicleStopover(index == sampled.lastIndex)
                .build()
        }

        statusText.text = "Calculando navegação pela rota salva..."
        nav.setDestinations(waypoints)?.setOnResultListener { code ->
            when (code) {
                Navigator.RouteStatus.OK -> {
                    nav.setAudioGuidance(Navigator.AudioGuidance.VOICE_ALERTS_AND_GUIDANCE)
                    nav.startGuidance()
                    statusText.text = "Navegando: ${route.name}"
                }
                Navigator.RouteStatus.NETWORK_ERROR -> statusText.text = "Sem internet para calcular a rota"
                Navigator.RouteStatus.ROUTING_TIMEOUT -> statusText.text = "Tempo esgotado ao calcular a rota"
                Navigator.RouteStatus.WAYPOINT_ERROR -> statusText.text = "Um ponto da rota não pôde ser usado"
                else -> statusText.text = "Não foi possível iniciar: $code"
            }
        }
    }

    private fun samplePoints(points: List<RoutePoint>, maxWaypoints: Int): List<RoutePoint> {
        if (points.size <= maxWaypoints) return points.distinctBy { it.latitude to it.longitude }
        val step = ceil(points.size.toDouble() / maxWaypoints.toDouble()).toInt().coerceAtLeast(1)
        val sampled = points.filterIndexed { index, _ -> index % step == 0 }.toMutableList()
        if (sampled.lastOrNull() != points.last()) sampled += points.last()
        return sampled.distinctBy { it.latitude to it.longitude }.take(maxWaypoints)
    }

    private fun readRouteImage(uri: Uri) {
        statusText.text = "Lendo nomes e referências da imagem..."
        imageImporter.readStreetCandidates(
            uri = uri,
            onSuccess = { candidates ->
                runOnUiThread {
                    if (candidates.isEmpty()) {
                        statusText.text = "Não encontrei texto útil nessa imagem"
                    } else {
                        AlertDialog.Builder(this)
                            .setTitle("Rota detectada na imagem")
                            .setMessage(
                                candidates.take(20).joinToString("\n") +
                                    "\n\nPróxima etapa: cruzar estes nomes com o mapa e montar o trajeto geográfico para confirmação."
                            )
                            .setPositiveButton("OK", null)
                            .show()
                        statusText.text = "Imagem lida • ${candidates.size} referências encontradas"
                    }
                }
            },
            onError = {
                runOnUiThread { statusText.text = "Falha ao ler a imagem: ${it.message ?: "erro desconhecido"}" }
            }
        )
    }

    private fun drawSavedRoute(route: RecordedRoute) {
        val points = route.points.map { LatLng(it.latitude, it.longitude) }
        savedRoutePolyline?.remove()
        if (points.size >= 2) {
            savedRoutePolyline = map?.addPolyline(PolylineOptions().addAll(points).width(10f))
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableMyLocationIfAllowed() {
        if (hasLocationPermission()) map?.isMyLocationEnabled = true
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onStart() {
        super.onStart()
        navView.onStart()
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
        routeRecorder.stop()
        val nav = navigator
        val listener = arrivalListener
        if (nav != null && listener != null) {
            nav.removeArrivalListener(listener)
        }
        nav?.cleanup()
        navView.onDestroy()
        super.onDestroy()
    }
}
