package com.example.changewatcher

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/*
 * MainActivity.kt
 *
 * Actividad de entrada y pantalla principal, construidas con Jetpack Compose.
 *
 * La actividad elige los colores según los ajustes de apariencia y muestra una de dos pantallas:
 *  - [WatcherScreen]: el último aviso guardado (ya con formato) y su imagen (que se amplía al tocarla,
 *    ver [ImageViewerDialog]), con un icono de actualizar arriba a la derecha (que muestra una animación
 *    mientras se revisa, ver [RefreshButton]) y, a su derecha, el icono de configuración; en la esquina
 *    superior derecha de la tarjeta del aviso van los iconos de abrir la página y copiar el aviso.
 *    Debajo del título "Avisos", en letra pequeña, se muestra la fecha y hora de la última actualización.
 *  - [SettingsScreen]: monitoreo, departamentos, intervalo y apariencia (ver SettingsScreen.kt).
 *
 * Cada vez que la app pasa a primer plano se lanza una revisión (refresh) y, al terminar,
 * se actualiza el último aviso mostrado. Con la app abierta no se envían notificaciones
 * (ver [Foreground] y [CheckWorker]).
 *
 * Al abrir la app también se busca una versión nueva (ver UpdateChecker.kt). Si hay una, se avisa con un
 * diálogo una sola vez por versión (queda anotada en [Prefs.notifiedUpdateVersion]); si el usuario lo
 * ignora, no se vuelve a mostrar.
 *
 * Esta pantalla no descarga nada por sí misma: lanza revisiones de [CheckWorker]
 * y lee el resultado desde [Prefs]. El formato del aviso lo aplica [AlertFormatter].
 */

/** Nombre único de la revisión al abrir la app o al pulsar Actualizar; si se lanza otra, reemplaza a la anterior. */
private const val REFRESH_WORK_NAME = "refresh_on_open"

/** Actividad de entrada (la que se abre desde el icono o desde la notificación). */
class MainActivity : ComponentActivity() {

    /** Sube cada vez que la app pasa a primer plano; la pantalla reacciona lanzando una revisión. */
    private var refreshTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val prefs = remember { Prefs(this) }
            // Ajustes de apariencia: al cambiarlos en Configuración, los colores se actualizan al instante
            var theme by remember { mutableStateOf(prefs.loadTheme()) }
            var showSettings by rememberSaveable { mutableStateOf(false) }
            // Versión nueva de la que se avisa con el diálogo (null = no hay nada que mostrar)
            var updateVersion by rememberSaveable { mutableStateOf<String?>(null) }

            // Al abrir la app se busca una versión nueva. Se avisa una sola vez por versión: se anota
            // antes de mostrar el diálogo, así que si el usuario lo ignora no vuelve a aparecer.
            LaunchedEffect(Unit) {
                if (updateVersion != null) return@LaunchedEffect
                val result = Updater.checkForUpdate(this@MainActivity)
                if (result is UpdateResult.Available &&
                    result.release.versionName != prefs.notifiedUpdateVersion
                ) {
                    prefs.notifiedUpdateVersion = result.release.versionName
                    updateVersion = result.release.versionName
                }
            }

            MaterialTheme(colorScheme = appColorScheme(theme)) {
                Surface(Modifier.fillMaxSize()) {
                    // El botón Atrás del teléfono vuelve de Configuración a la pantalla principal
                    BackHandler(enabled = showSettings) { showSettings = false }
                    if (showSettings) {
                        SettingsScreen(
                            theme = theme,
                            onThemeChange = {
                                theme = it
                                prefs.saveTheme(it)
                            },
                            onBack = { showSettings = false },
                        )
                    } else {
                        WatcherScreen(refreshTick, onOpenSettings = { showSettings = true })
                    }
                }

                // Aviso de actualización disponible: "Ver" lleva a Configuración, donde se descarga e instala
                updateVersion?.let { version ->
                    AlertDialog(
                        onDismissRequest = { updateVersion = null },
                        title = { Text("Actualización disponible") },
                        text = { Text("Hay una versión nueva ($version). Puedes instalarla desde Configuración.") },
                        confirmButton = {
                            TextButton(onClick = {
                                updateVersion = null
                                showSettings = true
                            }) { Text("Ver") }
                        },
                        dismissButton = {
                            TextButton(onClick = { updateVersion = null }) { Text("Ahora no") }
                        },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        refreshTick++
    }
}

/**
 * Pantalla principal: el último aviso guardado y sus botones.
 *
 * @param refreshTick contador que sube cada vez que la app vuelve a primer plano (0 = todavía no arrancó).
 * @param onOpenSettings abre la pantalla de configuración.
 */
@Composable
fun WatcherScreen(refreshTick: Int, onOpenSettings: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { Prefs(ctx) }
    // Departamentos elegidos en Configuración; se usan para resaltarlos en el aviso
    val selected = remember { prefs.load().departments }

    var lastCheck by remember { mutableStateOf(prefs.lastCheckMillis) }       // hora de la última actualización (0 = ninguna)
    var error by remember { mutableStateOf<String?>(null) }                   // error visible
    var checkId by remember { mutableStateOf<UUID?>(null) }                   // id de la revisión en curso
    var refreshing by remember { mutableStateOf(false) }                      // true = hay una revisión en curso (se muestra la animación)
    var snapshotName by remember { mutableStateOf(prefs.lastSnapshot) }       // archivo del último aviso guardado
    var alertText by remember { mutableStateOf<String?>(null) }               // texto del último aviso (null = cargando)
    var imageName by remember { mutableStateOf(prefs.lastImage) }             // archivo de la imagen del último aviso
    var alertImage by remember { mutableStateOf<ImageBitmap?>(null) }         // imagen del aviso ya leída (null = sin imagen)
    var showImage by rememberSaveable { mutableStateOf(false) }               // true = imagen ampliada a pantalla completa

    val askNotificationPermission = rememberNotificationPermissionAsker()

    /** Lanza una revisión (al abrir la app o con el icono Actualizar). Reemplaza a la anterior si sigue en curso. */
    fun startRefresh() {
        val request = OneTimeWorkRequestBuilder<CheckWorker>()
            .setInputData(workDataOf(CheckWorker.KEY_REFRESH to true))
            .build()
        WorkManager.getInstance(ctx)
            .enqueueUniqueWork(REFRESH_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        // Se recuerda el id para que LaunchedEffect muestre el resultado al terminar
        checkId = request.id
        refreshing = true
    }

    // Carga el texto del último aviso guardado. Se repite cada vez que cambia el archivo.
    LaunchedEffect(snapshotName) {
        alertText = if (snapshotName.isBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                SnapshotStore.file(ctx, snapshotName)?.let { f ->
                    runCatching {
                        val content = f.readText()
                        // Lo guardado en versiones antiguas era HTML; lo nuevo ya es texto plano
                        if (snapshotName.endsWith(".html")) SnapshotStore.htmlToText(content) else content
                    }.getOrNull()
                } ?: ""
            }
        }
    }

    // Carga la imagen del aviso (reducida si es enorme). Se repite cada vez que cambia el archivo.
    LaunchedEffect(imageName) {
        alertImage = if (imageName.isBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                SnapshotStore.imageFile(ctx, imageName)?.let { f ->
                    runCatching { SnapshotStore.decodeImage(f)?.asImageBitmap() }.getOrNull()
                }
            }
        }
    }

    // Cuando termina una revisión, se muestra su resultado y el aviso más reciente.
    // Se observa el estado del trabajo en WorkManager; al terminar, se lee lo que dejó CheckWorker.
    LaunchedEffect(checkId) {
        val id = checkId ?: return@LaunchedEffect
        WorkManager.getInstance(ctx).getWorkInfoByIdFlow(id).collect { info ->
            if (info != null && info.state.isFinished) {
                refreshing = false
                lastCheck = prefs.lastCheckMillis
                snapshotName = prefs.lastSnapshot
                imageName = prefs.lastImage
            }
        }
    }

    // Al entrar a la app (y cada vez que vuelve a primer plano) se hace un refresh con lo guardado en ajustes.
    LaunchedEffect(refreshTick) {
        if (refreshTick == 0) return@LaunchedEffect
        if (prefs.load().departments.isEmpty()) return@LaunchedEffect
        startRefresh()
    }

    /** Acción del icono de actualizar: valida que haya departamentos, pide el permiso de notificaciones y revisa. */
    fun onRefreshClick() {
        if (prefs.load().departments.isEmpty()) {
            error = "Selecciona al menos un departamento en Configuración"
        } else {
            error = null
            askNotificationPermission()
            startRefresh()
        }
    }

    // Colores del aviso tomados del tema: título, fenómeno esperado y departamentos elegidos
    val alertColors = AlertColors(
        title = MaterialTheme.colorScheme.primary.toArgb(),
        alert = MaterialTheme.colorScheme.error.toArgb(),
        selected = MaterialTheme.colorScheme.tertiary.toArgb(),
        // Números de la fecha y hora de emisión: un violeta fijo, distinto del azul, el rojo y el verde del resto
        // del aviso, en versión clara u oscura según el fondo para que se lea bien en ambos modos
        emission = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
            Color(0xFFCE93D8)
        } else {
            Color(0xFF6A1B9A)
        }.toArgb(),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Barra superior: a la izquierda el título "Avisos" con la fecha y hora de la última actualización
        // debajo, en letra pequeña; a la derecha, los iconos de actualizar y de configuración.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Avisos", style = MaterialTheme.typography.titleLarge)
                // Mientras se revisa no se muestra: el progreso se ve en la animación del botón.
                if (!refreshing) {
                    val lastCheckText = if (lastCheck > 0L) {
                        val formatted = SimpleDateFormat("d MMM, HH:mm", Locale.forLanguageTag("es-PY"))
                            .format(Date(lastCheck))
                        "Última actualización: $formatted"
                    } else {
                        "Aún no se hizo ninguna actualización"
                    }
                    Text(lastCheckText, style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RefreshButton(refreshing = refreshing, onClick = { onRefreshClick() })
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Configuración")
                }
            }
        }

        // Mensaje de error, si lo hay
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        // Último aviso guardado, con formato: siempre visible al entrar; se actualiza solo al terminar el refresh
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    snapshotName.isBlank() -> Text(
                        "No hay ningún aviso meteorológico vigente. Aquí aparecerá cuando la página publique uno.",
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    alertText == null -> Text("Cargando…", style = MaterialTheme.typography.bodyMedium)

                    alertText.isNullOrBlank() -> Text(
                        "No se pudo leer el aviso guardado.",
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    else -> {
                        // El aviso con formato; los departamentos elegidos se resaltan con otro color de letra
                        val raw = alertText ?: ""
                        val formatted = remember(raw, selected, alertColors) {
                            AlertFormatter.format(raw, selected, alertColors)
                        }
                        // Color base del texto: el del contenido de la tarjeta, para que se lea en modo claro y oscuro
                        val textColor = LocalContentColor.current.toArgb()
                        AndroidView(
                            modifier = Modifier.fillMaxWidth(),
                            factory = { c ->
                                TextView(c).apply {
                                    textSize = 16f
                                    setLineSpacing(0f, 1.3f)
                                }
                            },
                            update = {
                                it.text = formatted
                                it.setTextColor(textColor)
                            },
                        )
                    }
                }

                // Imagen del aviso (si la página la trae); al tocarla se abre ampliada y con zoom
                val image = alertImage
                if (snapshotName.isNotBlank() && image != null) {
                    Image(
                        bitmap = image,
                        contentDescription = "Imagen del aviso. Toca para ampliar",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showImage = true },
                    )
                    Text("Toca la imagen para ampliarla", style = MaterialTheme.typography.bodySmall)
                }

                // Iconos al final de la tarjeta, en la esquina inferior derecha: abrir la página y, en la esquina, copiar el aviso
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Abre la página de avisos en el navegador
                    IconButton(onClick = {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PAGE_URL)))
                    }) {
                        Icon(OpenInNewIcon, contentDescription = "Abrir página")
                    }
                    // Copia el aviso tal como se ve (sin colores ni tamaños) al portapapeles
                    IconButton(
                        enabled = !alertText.isNullOrBlank(),
                        onClick = {
                            val text = AlertFormatter.format(alertText ?: "", selected, alertColors).toString()
                            ctx.getSystemService(ClipboardManager::class.java)
                                .setPrimaryClip(ClipData.newPlainText("Aviso meteorológico", text))
                            Toast.makeText(ctx, "Aviso copiado al portapapeles", Toast.LENGTH_SHORT).show()
                        },
                    ) {
                        Icon(CopyIcon, contentDescription = "Copiar aviso")
                    }
                }
            }
        }
    }

    // Imagen ampliada a pantalla completa
    val fullImage = alertImage
    if (showImage && fullImage != null) {
        ImageViewerDialog(fullImage, onDismiss = { showImage = false })
    }
}

/**
 * Icono de copiar (dos hojas). La librería de iconos que usa el proyecto no lo trae, así que se dibuja aquí
 * con el mismo trazo de los iconos Material.
 */
private val CopyIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "ContentCopy",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = addPathNodes(
            "M16,1L4,1C2.9,1 2,1.9 2,3v14h2L4,3h12L16,1zM19,5L8,5C6.9,5 6,5.9 6,7v14c0,1.1 0.9,2 2,2h11" +
                    "c1.1,0 2,-0.9 2,-2L21,7C21,5.9 20.1,5 19,5zM19,21L8,21L8,7h11L19,21z"
        ),
        fill = SolidColor(Color.Black),
    ).build()
}

/** Icono de abrir en el navegador (cuadro con flecha saliendo), dibujado igual que [CopyIcon]. */
private val OpenInNewIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "OpenInNew",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = addPathNodes(
            "M19,19H5V5h7V3H5C3.89,3 3,3.9 3,5v14c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2v-7h-2V19z" +
                    "M14,3v2h3.59l-9.83,9.83 1.41,1.41L19,6.41V10h2V3H14z"
        ),
        fill = SolidColor(Color.Black),
    ).build()
}

/**
 * Botón de actualizar de la barra superior.
 *
 * En reposo es solo el icono. Mientras [refreshing] es true, un anillo de progreso gira alrededor del botón
 * y el icono rota sobre sí mismo, para que se vea que la revisión está en proceso. Durante ese tiempo
 * los toques se ignoran (el botón conserva su color normal).
 *
 * @param refreshing true mientras hay una revisión en curso.
 * @param onClick acción al tocar el botón cuando no hay una revisión en curso.
 */
@Composable
fun RefreshButton(refreshing: Boolean, onClick: () -> Unit) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp)) {
        // Anillo de progreso alrededor del icono
        if (refreshing) {
            CircularProgressIndicator(modifier = Modifier.size(40.dp), strokeWidth = 3.dp)
        }
        IconButton(onClick = { if (!refreshing) onClick() }) {
            if (refreshing) {
                SpinningRefreshIcon()
            } else {
                Icon(Icons.Filled.Refresh, contentDescription = "Actualizar")
            }
        }
    }
}

/** Icono de actualizar que da una vuelta completa por segundo, sin parar. Solo existe mientras se actualiza. */
@Composable
private fun SpinningRefreshIcon() {
    val transition = rememberInfiniteTransition(label = "refresh")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1000, easing = LinearEasing)),
        label = "refreshAngle",
    )
    Icon(
        Icons.Filled.Refresh,
        contentDescription = "Actualizando",
        modifier = Modifier.rotate(angle),
    )
}

/**
 * Muestra la imagen del aviso a pantalla completa sobre fondo negro.
 * Se puede acercar con dos dedos, mover con un dedo cuando está ampliada, y volver al tamaño normal
 * con doble toque. Se cierra con la X o con el botón Atrás del teléfono.
 */
@Composable
fun ImageViewerDialog(image: ImageBitmap, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        var size by remember { mutableStateOf(IntSize.Zero) }

        /** Limita el desplazamiento para que la imagen ampliada no se salga de la pantalla. */
        fun clamp(o: Offset, s: Float): Offset {
            val maxX = size.width * (s - 1f) / 2f
            val maxY = size.height * (s - 1f) / 2f
            return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onSizeChanged { size = it },
        ) {
            Image(
                bitmap = image,
                contentDescription = "Imagen del aviso ampliada",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = {
                            scale = 1f
                            offset = Offset.Zero
                        })
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val newScale = (scale * zoom).coerceIn(1f, 6f)
                            scale = newScale
                            offset = if (newScale == 1f) Offset.Zero else clamp(offset + pan, newScale)
                        }
                    }
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    ),
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Cerrar", tint = Color.White)
            }
        }
    }
}
