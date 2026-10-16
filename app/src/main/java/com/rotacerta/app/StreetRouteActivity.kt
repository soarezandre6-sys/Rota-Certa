package com.rotacerta.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.rotacerta.app.data.RouteStore
import com.rotacerta.app.model.RecordedRoute
import com.rotacerta.app.routing.StreetRoutePlanner
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Polyline
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.util.Locale

class StreetRouteActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var cityInput: EditText
    private lateinit var streetsInput: EditText
    private lateinit var statusText: TextView
    private lateinit var buildButton: Button
    private lateinit var saveButton: Button

    private lateinit var planner: StreetRoutePlanner
    private lateinit var routeStore: RouteStore

    private val fusedLocationClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }

    private var map: MapLibreMap? = null
    private var mapReady = false
    private var vectorStyleLoaded = false
    private var previewOutline: Polyline? = null
    private var previewRoute: Polyline? = null
    private var pendingRoute: StreetRoutePlanner.PlannedRoute? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_street_route)

        mapView = findViewById(R.id.street_route_map)
        cityInput = findViewById(R.id.street_route_city_input)
        streetsInput = findViewById(R.id.street_route_streets_input)
        statusText = findViewById(R.id.street_route_status)
        buildButton = findViewById(R.id.street_route_build_button)
        saveButton = findViewById(R.id.street_route_save_button)

        planner = StreetRoutePlanner(this)
        routeStore = RouteStore(this)

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { mapLibreMap ->
            map = mapLibreMap
            mapLibreMap.uiSettings.isCompassEnabled = true
            mapLibreMap.uiSettings.isAttributionEnabled = true
            mapLibreMap.uiSettings.isLogoEnabled = true
            loadMapStyle(mapLibreMap)
        }

        buildButton.setOnClickListener { buildRouteFromTypedStreets() }
        saveButton.setOnClickListener { savePendingRoute() }
        findViewById<Button>(R.id.street_route_back_button).setOnClickListener { finish() }
    }

    private fun loadMapStyle(mapLibreMap: MapLibreMap) {
        mapReady = false
        vectorStyleLoaded = false
        mapLibreMap.setStyle(Style.Builder().fromUri(OPENFREE_BRIGHT_STYLE_URI)) {
            vectorStyleLoaded = true
            mapReady = true
            pendingRoute?.let(::drawPreview)
        }

        mapView.postDelayed({
            if (!vectorStyleLoaded && !isFinishing && map === mapLibreMap) {
                mapLibreMap.setStyle(Style.Builder().fromJson(OSM_FALLBACK_STYLE_JSON)) {
                    mapReady = true
                    pendingRoute?.let(::drawPreview)
                }
            }
        }, STYLE_FALLBACK_DELAY_MS)
    }

    private fun buildRouteFromTypedStreets() {
        val streetNames = streetsInput.text.toString()
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .toList()

        if (streetNames.size < 2) {
            toast("Digite pelo menos duas ruas, uma por linha.")
            return
        }
        if (streetNames.size > MAX_UI_STREETS) {
            toast("Use no máximo $MAX_UI_STREETS ruas por rota.")
            return
        }

        val areaHint = cityInput.text.toString().trim()
        buildButton.isEnabled = false
        saveButton.isEnabled = false
        statusText.text = "Localizando as ruas e calculando o trajeto..."

        if (areaHint.isNotBlank()) {
            requestRoute(streetNames, areaHint, null)
            return
        }

        if (!hasLocationPermission()) {
            buildButton.isEnabled = true
            statusText.text = "Informe Cidade/UF ou habilite a localização"
            toast("Sem Cidade/UF, preciso da localização atual para procurar as ruas na região certa.")
            return
        }

        fusedLocationClient.lastLocation
            .addOnSuccessListener { location ->
                if (location == null) {
                    buildButton.isEnabled = true
                    statusText.text = "GPS sem posição atual"
                    toast("Informe Cidade/UF para montar a rota sem depender do GPS.")
                } else {
                    requestRoute(streetNames, areaHint, location)
                }
            }
            .addOnFailureListener {
                buildButton.isEnabled = true
                statusText.text = "Não consegui obter a posição atual"
                toast("Informe Cidade/UF e tente novamente.")
            }
    }

    private fun requestRoute(streetNames: List<String>, areaHint: String, bias: Location?) {
        planner.build(
            streetNames = streetNames,
            areaHint = areaHint,
            biasLatitude = bias?.latitude,
            biasLongitude = bias?.longitude,
            onSuccess = { result ->
                runOnUiThread {
                    pendingRoute = result
                    buildButton.isEnabled = true
                    saveButton.isEnabled = true
                    drawPreview(result)
                    statusText.text = String.format(
                        Locale.getDefault(),
                        "Prévia pronta • %.2f km • %d ruas",
                        result.distanceMeters / 1_000f,
                        result.waypoints.size
                    )
                    showResolvedStreets(result)
                }
            },
            onError = { error ->
                runOnUiThread {
                    pendingRoute = null
                    buildButton.isEnabled = true
                    saveButton.isEnabled = false
                    statusText.text = "Não consegui montar uma rota confiável"
                    toast(error.message ?: "Falha ao calcular a rota")
                }
            }
        )
    }

    private fun showResolvedStreets(result: StreetRoutePlanner.PlannedRoute) {
        val resolved = result.waypoints.mapIndexed { index, waypoint ->
            "${index + 1}. ${waypoint.resolvedName}"
        }.joinToString("\n")

        AlertDialog.Builder(this)
            .setTitle("Rota montada")
            .setMessage(
                "$resolved\n\nA rota foi calculada pelas ruas reais, na ordem informada. Confira o desenho no mapa antes de salvar."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun drawPreview(result: StreetRoutePlanner.PlannedRoute) {
        if (!mapReady) return
        val currentMap = map ?: return
        val points = result.points.map { LatLng(it.latitude, it.longitude) }
        if (points.size < 2) return

        previewOutline?.let(currentMap::removeAnnotation)
        previewRoute?.let(currentMap::removeAnnotation)

        previewOutline = currentMap.addPolyline(
            PolylineOptions()
                .addAll(points)
                .width(19f)
                .color(Color.rgb(18, 38, 63))
        )
        previewRoute = currentMap.addPolyline(
            PolylineOptions()
                .addAll(points)
                .width(11f)
                .color(Color.rgb(28, 126, 247))
        )

        runCatching {
            val bounds = LatLngBounds.fromLatLngs(points)
            currentMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 80))
        }
    }

    private fun savePendingRoute() {
        val result = pendingRoute ?: run {
            toast("Monte a rota antes de salvar.")
            return
        }

        val input = EditText(this).apply {
            hint = "Ex.: Casa - Trabalho pela Av. Brasil"
        }
        AlertDialog.Builder(this)
            .setTitle("Salvar rota por ruas")
            .setMessage("Dê um nome para reconhecer este trajeto depois.")
            .setView(input)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar") { _, _ ->
                val now = System.currentTimeMillis()
                val route = RecordedRoute(
                    id = now,
                    name = input.text.toString().trim().ifBlank {
                        "Rota por ruas ${routeStore.loadAll().size + 1}"
                    },
                    startedAt = now,
                    finishedAt = now,
                    distanceMeters = result.distanceMeters,
                    points = result.points
                )
                routeStore.save(route)
                setResult(RESULT_OK)
                toast("${route.name} salva")
                finish()
            }
            .show()
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onStart() {
        super.onStart()
        mapView.onStart()
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
        planner.close()
        mapView.onDestroy()
        super.onDestroy()
    }

    companion object {
        private const val MAX_UI_STREETS = 12
        private const val OPENFREE_BRIGHT_STYLE_URI = "https://tiles.openfreemap.org/styles/bright"
        private const val STYLE_FALLBACK_DELAY_MS = 8_000L

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
