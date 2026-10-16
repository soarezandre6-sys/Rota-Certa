package com.rotacerta.app.ui

import android.content.Context
import android.content.ContextWrapper
import android.location.Location
import android.util.AttributeSet
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.rotacerta.app.MainActivity
import com.rotacerta.app.model.RecordedRoute
import com.rotacerta.app.model.RoutePoint
import com.rotacerta.app.routing.RouteApproachPlanner
import java.util.Locale

class OfficialStartNavigationButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialButtonStyle
) : MaterialButton(context, attrs, defStyleAttr) {

    private var delegatedClick: OnClickListener? = null
    private var internalListenerInstalled = false

    override fun setOnClickListener(listener: OnClickListener?) {
        if (!internalListenerInstalled) {
            delegatedClick = listener
            internalListenerInstalled = true
            super.setOnClickListener { view -> interceptNavigationClick(view) }
        } else {
            delegatedClick = listener
        }
    }

    private fun interceptNavigationClick(view: View) {
        val activity = findMainActivity()
        if (activity == null) {
            delegatedClick?.onClick(view)
            return
        }

        val activeNavigation = readPrivateField<RecordedRoute>(activity, "localNavigationRoute")
        if (activeNavigation != null) {
            delegatedClick?.onClick(view)
            return
        }

        val selectedRoute = readPrivateField<RecordedRoute>(activity, "lastSavedRoute")
        if (selectedRoute == null || selectedRoute.points.size < 2) {
            delegatedClick?.onClick(view)
            return
        }

        val currentLocation = readPrivateField<Location>(activity, "currentLocation")
        if (currentLocation == null) {
            Toast.makeText(
                context,
                "Aguardando uma posição GPS válida para verificar o início da rota.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val officialStart = selectedRoute.points.first()
        val distanceToStart = distanceMeters(currentLocation, officialStart)
        if (distanceToStart <= OFFICIAL_START_TOLERANCE_METERS) {
            delegatedClick?.onClick(view)
            return
        }

        AlertDialog.Builder(activity)
            .setTitle("Ir até o início da rota?")
            .setMessage(
                "Você está a ${formatDistance(distanceToStart)} do início oficial de \"${selectedRoute.name}\".\n\n" +
                    "Para não pular nenhum trecho da rota salva, o Rota Certa pode criar um caminho temporário até o primeiro ponto. " +
                    "Depois desse ponto, a rota salva continua completa até o fim."
            )
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("GUIAR ATÉ O INÍCIO") { _, _ ->
                calculateApproach(activity, currentLocation, selectedRoute)
            }
            .show()
    }

    private fun calculateApproach(
        activity: MainActivity,
        currentLocation: Location,
        selectedRoute: RecordedRoute
    ) {
        val firstPoint = selectedRoute.points.first()
        isEnabled = false
        text = "CALCULANDO ACESSO..."

        val planner = RouteApproachPlanner()
        planner.plan(
            fromLatitude = currentLocation.latitude,
            fromLongitude = currentLocation.longitude,
            toLatitude = firstPoint.latitude,
            toLongitude = firstPoint.longitude,
            onSuccess = { approach ->
                planner.close()
                activity.runOnUiThread {
                    isEnabled = true
                    text = "NAVEGAR NA ROTA SELECIONADA"

                    val combined = buildCombinedRoute(selectedRoute, approach.points, approach.distanceMeters)
                    val started = invokeNavigateRoute(activity, combined)
                    if (!started) {
                        Toast.makeText(
                            context,
                            "Não consegui iniciar a navegação até o começo da rota.",
                            Toast.LENGTH_LONG
                        ).show()
                        return@runOnUiThread
                    }

                    // navigateRoute altera lastSavedRoute para a rota temporária. Restauramos
                    // a seleção original para que o percurso salvo nunca seja substituído.
                    writePrivateField(activity, "lastSavedRoute", selectedRoute)
                    invokeSpeak(
                        activity,
                        "Guiando até o início oficial da rota. Depois, continue pela rota salva completa."
                    )
                    Toast.makeText(
                        context,
                        "Acesso temporário criado • início obrigatório preservado",
                        Toast.LENGTH_LONG
                    ).show()
                }
            },
            onError = { error ->
                planner.close()
                activity.runOnUiThread {
                    isEnabled = true
                    text = "NAVEGAR NA ROTA SELECIONADA"
                    Toast.makeText(
                        context,
                        error.message ?: "Não consegui calcular o caminho até o início da rota.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }

    private fun buildCombinedRoute(
        savedRoute: RecordedRoute,
        approachPoints: List<RoutePoint>,
        approachDistanceMeters: Float
    ): RecordedRoute {
        val merged = ArrayList<RoutePoint>(approachPoints.size + savedRoute.points.size)
        merged.addAll(approachPoints)

        val firstSaved = savedRoute.points.first()
        val lastApproach = merged.lastOrNull()
        val duplicateStart = lastApproach != null && distanceMeters(lastApproach, firstSaved) <= 3f
        if (duplicateStart) {
            merged.addAll(savedRoute.points.drop(1))
        } else {
            merged.addAll(savedRoute.points)
        }

        val now = System.currentTimeMillis()
        return RecordedRoute(
            id = now,
            name = savedRoute.name,
            startedAt = now,
            finishedAt = savedRoute.finishedAt,
            distanceMeters = approachDistanceMeters + savedRoute.distanceMeters,
            points = merged
        )
    }

    private fun invokeNavigateRoute(activity: MainActivity, route: RecordedRoute): Boolean =
        runCatching {
            val method = MainActivity::class.java.getDeclaredMethod(
                "navigateRoute",
                RecordedRoute::class.java
            )
            method.isAccessible = true
            method.invoke(activity, route)
            true
        }.getOrDefault(false)

    private fun invokeSpeak(activity: MainActivity, message: String) {
        runCatching {
            val method = MainActivity::class.java.getDeclaredMethod("speak", String::class.java)
            method.isAccessible = true
            method.invoke(activity, message)
        }
    }

    private fun <T> readPrivateField(activity: MainActivity, fieldName: String): T? =
        runCatching {
            val field = MainActivity::class.java.getDeclaredField(fieldName)
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            field.get(activity) as? T
        }.getOrNull()

    private fun writePrivateField(activity: MainActivity, fieldName: String, value: Any?) {
        runCatching {
            val field = MainActivity::class.java.getDeclaredField(fieldName)
            field.isAccessible = true
            field.set(activity, value)
        }
    }

    private fun findMainActivity(): MainActivity? {
        var candidate: Context? = context
        while (candidate is ContextWrapper) {
            if (candidate is MainActivity) return candidate
            candidate = candidate.baseContext
        }
        return candidate as? MainActivity
    }

    private fun distanceMeters(location: Location, point: RoutePoint): Float {
        val target = Location("official-route-start").apply {
            latitude = point.latitude
            longitude = point.longitude
        }
        return location.distanceTo(target)
    }

    private fun distanceMeters(a: RoutePoint, b: RoutePoint): Float {
        val first = Location("route-a").apply {
            latitude = a.latitude
            longitude = a.longitude
        }
        val second = Location("route-b").apply {
            latitude = b.latitude
            longitude = b.longitude
        }
        return first.distanceTo(second)
    }

    private fun formatDistance(distanceMeters: Float): String =
        if (distanceMeters < 1_000f) {
            "${distanceMeters.toInt()} m"
        } else {
            String.format(Locale.getDefault(), "%.1f km", distanceMeters / 1_000f)
        }

    companion object {
        private const val OFFICIAL_START_TOLERANCE_METERS = 45f
    }
}
