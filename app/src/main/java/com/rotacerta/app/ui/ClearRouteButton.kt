package com.rotacerta.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.AttributeSet
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.rotacerta.app.R
import org.maplibre.android.annotations.Polyline
import org.maplibre.android.maps.MapLibreMap

/**
 * Botão de apoio da Build 6.
 * Retira a rota atualmente carregada do mapa sem apagar a rota salva no RouteStore.
 */
class ClearRouteButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : MaterialButton(context, attrs) {

    init {
        setOnClickListener { clearLoadedRoute() }
    }

    private fun clearLoadedRoute() {
        val activity = context.findActivity() ?: return

        val recording = readPrivateField<Boolean>(activity, "recordingUiActive") == true
        if (recording) {
            Toast.makeText(
                context,
                "Pare e salve a gravação antes de retirar a rota do mapa.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val hadSavedLine = readPrivateField<Polyline>(activity, "savedRoutePolyline") != null
        val hadRecordedLine = readPrivateField<Polyline>(activity, "recordedPolyline") != null
        val hadNavigation = readPrivateField<Any>(activity, "localNavigationRoute") != null
        val hadSelectedRoute = readPrivateField<Any>(activity, "lastSavedRoute") != null
        val hadRoute = hadSavedLine || hadRecordedLine || hadNavigation || hadSelectedRoute

        invokePrivate(activity, "stopLocalNavigation", false)
        invokePrivate(activity, "removeSavedRouteFromMap")
        removeFinishedRecordingLine(activity)

        // Limpa somente a seleção atual. A rota continua armazenada em Rotas Salvas.
        writePrivateField(activity, "lastSavedRoute", null)
        invokePrivate(activity, "setControlsCollapsed", false)

        activity.findViewById<TextView>(R.id.status_text)?.text = if (hadRoute) {
            "Rota retirada do mapa • continua disponível em Rotas Salvas"
        } else {
            "Nenhuma rota carregada no mapa"
        }

        Toast.makeText(
            context,
            if (hadRoute) "Rota retirada do mapa" else "Não há rota para retirar",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun removeFinishedRecordingLine(activity: Activity) {
        val map = readPrivateField<MapLibreMap>(activity, "map") ?: return
        listOf("recordedOutline", "recordedPolyline").forEach { fieldName ->
            val line = readPrivateField<Polyline>(activity, fieldName)
            if (line != null) runCatching { map.removeAnnotation(line) }
            writePrivateField(activity, fieldName, null)
        }
    }

    private fun invokePrivate(activity: Activity, name: String, vararg args: Any?) {
        runCatching {
            val parameterTypes = args.map {
                when (it) {
                    is Boolean -> Boolean::class.javaPrimitiveType!!
                    else -> it?.javaClass ?: Any::class.java
                }
            }.toTypedArray()
            val method = activity.javaClass.getDeclaredMethod(name, *parameterTypes)
            method.isAccessible = true
            method.invoke(activity, *args)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> readPrivateField(activity: Activity, name: String): T? = runCatching {
        val field = activity.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.get(activity) as? T
    }.getOrNull()

    private fun writePrivateField(activity: Activity, name: String, value: Any?) {
        runCatching {
            val field = activity.javaClass.getDeclaredField(name)
            field.isAccessible = true
            field.set(activity, value)
        }
    }

    private fun Context.findActivity(): Activity? {
        var current: Context? = this
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return current as? Activity
    }
}
