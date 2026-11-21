package com.example.changewatcher

import android.app.Activity
import android.app.Application
import android.os.Bundle

/*
 * ClimaApp.kt
 *
 * Clase Application de la app (registrada en AndroidManifest.xml con android:name=".ClimaApp").
 * Lleva la cuenta de cuántas pantallas de la app están visibles, para que [CheckWorker]
 * sepa si la app está en primer plano y, en ese caso, no envíe notificaciones.
 */

/**
 * Estado de primer plano de la app.
 *
 * Se guarda solo en memoria a propósito: si el proceso está muerto, la app no está en primer plano,
 * y las revisiones lanzadas por alarma o WorkManager sí deben notificar.
 * Se cuenta (en vez de usar un booleano) porque al pasar de una pantalla a otra de la app
 * la nueva pantalla arranca antes de que la anterior se detenga.
 */
object Foreground {
    @Volatile
    private var started = 0

    /** True si alguna pantalla de la app está visible para el usuario. */
    val isActive: Boolean get() = started > 0

    internal fun onStart() {
        started++
    }

    internal fun onStop() {
        if (started > 0) started--
    }
}

/** Registra el seguimiento de pantallas visibles al crearse el proceso de la app. */
class ClimaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) = Foreground.onStart()
            override fun onActivityStopped(activity: Activity) = Foreground.onStop()
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
