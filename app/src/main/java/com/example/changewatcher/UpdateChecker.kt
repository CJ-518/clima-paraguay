package com.example.changewatcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.Locale

/*
 * UpdateChecker.kt
 *
 * Actualización de la app desde los Releases del repositorio en GitHub ([REPO_URL]).
 *
 * Flujo (lo dispara la pantalla de Configuración, ver SettingsScreen.kt):
 *  1. [Updater.currentVersionName] devuelve la versión instalada (versionName de build.gradle.kts).
 *  2. [Updater.checkForUpdate] consulta el último release publicado y lo compara con la versión instalada.
 *  3. [Updater.downloadApk] descarga el APK del release (con progreso) a la caché de la app y comprueba
 *     que sea un APK válido de esta misma aplicación.
 *  4. [Updater.installApk] abre el instalador de Android con ese APK. Android pide confirmación al usuario.
 *
 * Para que la comparación funcione, la etiqueta del release debe ser "v" + versionName (por ejemplo v1.5
 * para versionName "1.5"; es lo que hace release.ps1) y el release debe llevar adjunto un archivo .apk.
 * Además, el APK nuevo debe estar firmado con la misma clave que el instalado; si no, Android lo rechaza.
 */

/**
 * Datos del último release publicado en GitHub.
 *
 * @param versionName versión sin la "v" inicial (por ejemplo "1.5").
 * @param tag etiqueta tal como está en GitHub (por ejemplo "v1.5").
 * @param notes notas del release (texto, puede venir vacío).
 * @param pageUrl dirección de la página del release en GitHub.
 * @param apkUrl dirección de descarga del APK, o null si el release no tiene ningún .apk adjunto.
 * @param apkSize tamaño del APK en bytes (0 si no se conoce).
 */
data class ReleaseInfo(
    val versionName: String,
    val tag: String,
    val notes: String,
    val pageUrl: String,
    val apkUrl: String?,
    val apkSize: Long,
)

/** Resultado de buscar actualizaciones. */
sealed class UpdateResult {
    /** Hay una versión más nueva que la instalada. */
    data class Available(val release: ReleaseInfo) : UpdateResult()

    /** La versión instalada es la última publicada (o más nueva). */
    data class UpToDate(val release: ReleaseInfo) : UpdateResult()

    /** El repositorio todavía no tiene ningún release publicado. */
    object NoReleases : UpdateResult()

    /** No se pudo consultar GitHub; [reason] explica por qué, en texto para el usuario. */
    data class Failed(val reason: String) : UpdateResult()
}

object Updater {

    /** Consulta del último release publicado (no incluye borradores ni pre-lanzamientos). */
    private val LATEST_RELEASE_API =
        REPO_URL.replace("https://github.com/", "https://api.github.com/repos/") + "/releases/latest"

    /** Página con todos los releases; se usa si GitHub no entrega la dirección del release. */
    private val RELEASES_PAGE = "$REPO_URL/releases"

    private const val USER_AGENT = "ClimaParaguay-Updater"
    private const val AUTHORITY_SUFFIX = ".fileprovider"
    private const val APK_MIME = "application/vnd.android.package-archive"
    private const val MAX_REDIRECTS = 5
    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

    // ---------------------------------------------------------------------------------------------
    // Versión instalada y comparación
    // ---------------------------------------------------------------------------------------------

    /** Versión instalada (versionName de build.gradle.kts), por ejemplo "1.4". */
    @Suppress("DEPRECATION")
    fun currentVersionName(ctx: Context): String = runCatching {
        val pm = ctx.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            pm.getPackageInfo(ctx.packageName, 0)
        }
        info.versionName
    }.getOrNull() ?: "desconocida"

    /** Convierte "v1.4.2" o "1.4-beta" en la lista de números [1, 4, 2] para poder compararlas. */
    private fun parseVersion(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V")
            .substringBefore('-').substringBefore('+')
            .split('.')
            .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    /** True si la versión [remote] es más nueva que [local] (compara número por número: 1.10 > 1.9). */
    fun isNewer(remote: String, local: String): Boolean {
        val a = parseVersion(remote)
        val b = parseVersion(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Tamaño legible, por ejemplo "12,3 MB". */
    fun formatSize(bytes: Long): String =
        String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))

    /** Convierte un error de red en un mensaje corto para mostrar al usuario. */
    fun friendlyError(e: Throwable): String = when (e) {
        is UnknownHostException, is ConnectException -> "sin conexión a internet"
        is SocketTimeoutException -> "la conexión tardó demasiado"
        else -> e.message ?: e.javaClass.simpleName
    }

    // ---------------------------------------------------------------------------------------------
    // Consulta a GitHub
    // ---------------------------------------------------------------------------------------------

    /**
     * Pregunta a GitHub cuál es el último release y lo compara con la versión instalada.
     * Nunca lanza errores de red: los devuelve como [UpdateResult.Failed].
     */
    suspend fun checkForUpdate(ctx: Context): UpdateResult = withContext(Dispatchers.IO) {
        try {
            val conn = open(LATEST_RELEASE_API, "application/vnd.github+json", 15_000)
            try {
                when (val code = conn.responseCode) {
                    in 200..299 -> Unit
                    404 -> return@withContext UpdateResult.NoReleases
                    403, 429 -> return@withContext UpdateResult.Failed(
                        "GitHub limitó las consultas; intenta de nuevo en un rato"
                    )

                    else -> return@withContext UpdateResult.Failed("GitHub respondió HTTP $code")
                }
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val release = parseRelease(json)
                if (release.versionName.isBlank()) {
                    return@withContext UpdateResult.Failed("la respuesta de GitHub no trae la versión")
                }
                if (isNewer(release.versionName, currentVersionName(ctx))) {
                    UpdateResult.Available(release)
                } else {
                    // Ya no hace falta ningún APK descargado antes
                    clearDownloads(ctx)
                    UpdateResult.UpToDate(release)
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UpdateResult.Failed(friendlyError(e))
        }
    }

    /** Lee un campo de texto del JSON; devuelve "" si falta o es null (org.json devolvería "null"). */
    private fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key, "")

    /** Arma un [ReleaseInfo] con la respuesta de GitHub; toma el primer archivo .apk adjunto. */
    private fun parseRelease(json: JSONObject): ReleaseInfo {
        val tag = json.str("tag_name")
        var apkUrl: String? = null
        var apkSize = 0L
        val assets = json.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                if (asset.str("name").endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.str("browser_download_url").takeIf { it.startsWith("https://") }
                    apkSize = asset.optLong("size", 0L)
                    break
                }
            }
        }
        return ReleaseInfo(
            versionName = tag.trim().removePrefix("v").removePrefix("V"),
            tag = tag,
            notes = json.str("body"),
            pageUrl = json.str("html_url").ifBlank { RELEASES_PAGE },
            apkUrl = apkUrl,
            apkSize = apkSize,
        )
    }

    /**
     * Abre una conexión GET y sigue hasta [MAX_REDIRECTS] redirecciones a mano
     * (GitHub redirige las descargas de los releases a otro servidor).
     * Quien la llama debe revisar el código de respuesta y cerrar la conexión.
     */
    private fun open(urlStr: String, accept: String, readTimeoutMs: Int): HttpURLConnection {
        var current = urlStr
        repeat(MAX_REDIRECTS + 1) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = readTimeoutMs
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", accept)
            // Sin compresión: así el tamaño que informa el servidor coincide con los bytes recibidos
            conn.setRequestProperty("Accept-Encoding", "identity")
            val code = conn.responseCode
            if (code in REDIRECT_CODES) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location == null) throw IllegalStateException("HTTP $code sin destino")
                current = URL(URL(current), location).toString()
            } else {
                return conn
            }
        }
        throw IllegalStateException("demasiadas redirecciones")
    }

    // ---------------------------------------------------------------------------------------------
    // Descarga
    // ---------------------------------------------------------------------------------------------

    /** Carpeta de la caché de la app donde se guardan los APK descargados (la expone el FileProvider). */
    private fun downloadsDir(ctx: Context) = File(ctx.cacheDir, "updates")

    /** Borra los APK descargados antes (y los restos de descargas interrumpidas). */
    fun clearDownloads(ctx: Context) {
        downloadsDir(ctx).listFiles()?.forEach { it.delete() }
    }

    /** Versión que declara un APK descargado, o null si el archivo no es un APK legible. */
    @Suppress("DEPRECATION")
    private fun archiveVersion(ctx: Context, file: File): String? =
        runCatching { ctx.packageManager.getPackageArchiveInfo(file.absolutePath, 0)?.versionName }.getOrNull()

    /**
     * Borra los APK guardados que ya no sirven: los que no son más nuevos que la versión instalada
     * (por ejemplo, el que se acaba de instalar). Se llama al abrir la pantalla de Configuración.
     */
    suspend fun cleanObsolete(ctx: Context) {
        withContext(Dispatchers.IO) {
            val current = currentVersionName(ctx)
            downloadsDir(ctx).listFiles()?.forEach { f ->
                if (f.name.endsWith(".apk")) {
                    val v = archiveVersion(ctx, f)
                    if (v == null || !isNewer(v, current)) f.delete()
                }
            }
        }
    }

    /**
     * Descarga el APK del release a la caché de la app y devuelve el archivo.
     * Se escribe primero con extensión .part y solo se renombra a .apk cuando está completo y verificado.
     *
     * @param onProgress avance de 0f a 1f (se llama desde un hilo de fondo, solo cuando cambia el porcentaje;
     *   si el servidor no informa el tamaño no se llama nunca).
     * @throws Exception si no hay APK, falla la red, la descarga queda incompleta o el archivo no es válido.
     */
    suspend fun downloadApk(ctx: Context, release: ReleaseInfo, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val url = release.apkUrl ?: throw IllegalStateException("el release no incluye un archivo APK")
            val dir = downloadsDir(ctx)
            clearDownloads(ctx)
            dir.mkdirs()
            val part = File(dir, "update.apk.part")
            val target = File(dir, "ClimaParaguay-v${release.versionName}.apk")
            try {
                val conn = open(url, "application/octet-stream", 30_000)
                try {
                    if (conn.responseCode !in 200..299) {
                        throw IllegalStateException("HTTP ${conn.responseCode}")
                    }
                    val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
                    var done = 0L
                    var lastPercent = -1
                    conn.inputStream.use { input ->
                        part.outputStream().use { out ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                ensureActive() // permite cancelar la descarga al salir de la pantalla
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                done += n
                                if (total > 0) {
                                    val percent = (done * 100 / total).toInt().coerceIn(0, 100)
                                    if (percent != lastPercent) {
                                        lastPercent = percent
                                        onProgress(percent / 100f)
                                    }
                                }
                            }
                        }
                    }
                    if (done == 0L) throw IllegalStateException("el archivo descargado está vacío")
                    if (total > 0 && done != total) throw IllegalStateException("la descarga quedó incompleta")
                } finally {
                    conn.disconnect()
                }
                if (!part.renameTo(target)) throw IllegalStateException("no se pudo guardar el archivo")
                verifyApk(ctx, target)
                target
            } catch (e: Throwable) {
                part.delete()
                target.delete()
                throw e
            }
        }

    /** Comprueba que el archivo sea un APK legible y de esta misma aplicación. */
    @Suppress("DEPRECATION")
    private fun verifyApk(ctx: Context, file: File) {
        val info = ctx.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            ?: throw IllegalStateException("el archivo descargado no es un APK válido")
        if (info.packageName != ctx.packageName) {
            throw IllegalStateException("el APK descargado no corresponde a esta aplicación")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Instalación
    // ---------------------------------------------------------------------------------------------

    /** True si el usuario ya permitió a esta app instalar aplicaciones (Android 8+ lo pide por app). */
    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    /** Abre los ajustes de Android donde se concede a esta app el permiso de instalar aplicaciones. */
    fun openInstallPermissionSettings(ctx: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }

    /** Abre el instalador de Android con el APK descargado. Android pide la confirmación al usuario. */
    fun installApk(ctx: Context, file: File) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + AUTHORITY_SUFFIX, file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }
}
