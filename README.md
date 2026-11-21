# Clima Paraguay

App Android que vigila los avisos meteorológicos de la Dirección de Meteorología e Hidrología de Paraguay y envía una notificación cuando un aviso incluye alguno de los departamentos que elegiste.

Fuente de datos: <https://www.meteorologia.gov.py/avisos/>

## Funciones

- Pantalla principal con el último aviso guardado, con formato (título, fenómeno esperado destacado y departamentos elegidos resaltados con otro color de letra).
- Al abrir la app se hace una actualización automática y siempre se muestra el último aviso guardado.
- Se guardan todos los avisos nuevos que publica la página, mencionen o no tus departamentos.
- Notificación solo cuando el aviso es nuevo o cambió y menciona alguno de los departamentos elegidos, para no repetir alertas.
- Sin notificaciones mientras la app está abierta: el aviso ya se ve en pantalla.
- Botones para **Actualizar**, **Copiar aviso** (al portapapeles) y **Abrir página** (en el navegador).
- Pantalla de **Configuración** (icono de tuerca):
  - Monitoreo activado o desactivado.
  - Selección de uno o varios de los 18 departamentos (incluye Asunción como capital) en un menú desplegable.
  - Intervalo de revisión de 15, 30, 45 o 60 minutos.
  - Apariencia: colores dinámicos del teléfono (Android 12 o superior) o tema manual con cinco colores a elegir, y modo claro, oscuro o el del sistema.
- Los cambios de configuración se guardan al instante.
- Coincidencia por palabra completa, por lo que "Alto Paraguay" no se confunde con "Alto Paraná".

## Requisitos

- Android 8.0 (API 26) o superior.
- Permiso de notificaciones (Android 13+).
- Permiso de alarmas y recordatorios (Android 12+), para que las revisiones sean puntuales.
- Conexión a internet.
- Colores dinámicos del teléfono: Android 12 o superior. En versiones anteriores se usa el tema manual.

## Instalación

1. Ve a la sección **Releases** de este repositorio.
2. Descarga el archivo `.apk` de la última versión.
3. Ábrelo en tu teléfono y permite la instalación desde orígenes desconocidos si Android lo pide.
4. Abre la app y concede el permiso de notificaciones.

## Uso

1. Toca el icono de tuerca para abrir la **Configuración**.
2. Abre el menú **Departamentos** y marca los que quieres vigilar.
3. Elige cada cuántos minutos revisar (15, 30, 45 o 60).
4. Activa el **Monitoreo**.
5. Vuelve a la pantalla principal. Opcional: pulsa **Actualizar** para comprobar que funciona.

Android ejecuta las revisiones periódicas de forma aproximada. La hora exacta depende de la batería y de la red.

## Compilar desde el código

Requisitos: Android Studio reciente y JDK 17.

1. Abre la carpeta del proyecto en Android Studio y espera a que termine la sincronización de Gradle.
2. Compila el APK de depuración desde una terminal en la raíz del proyecto:

```powershell
.\gradlew assembleDebug
```

El APK queda en `app/build/outputs/apk/debug/app-debug.apk`.

Para un APK de release firmado, usa en Android Studio *Build > Generate Signed App Bundle / APK*.

## Publicar una versión

1. Sube `versionCode` y `versionName` en `app/build.gradle.kts`.
2. Compila y firma el APK.
3. En la página del repositorio en GitHub, entra en **Releases > Draft a new release**, crea una etiqueta nueva, adjunta el APK y publica.

## Estructura

| Archivo | Función |
| --- | --- |
| `MainActivity.kt` | Actividad de entrada y pantalla principal (último aviso y botones) |
| `SettingsScreen.kt` | Pantalla de configuración y programación de las revisiones |
| `AppTheme.kt` | Ajustes de apariencia y colores de la app |
| `AlertFormatter.kt` | Formato del aviso guardado al mostrarlo |
| `CheckWorker.kt` | Revisión en segundo plano y notificaciones |
| `AlarmScheduler.kt` | Alarmas exactas para las revisiones periódicas |
| `WatcherReceivers.kt` | Receptores de la alarma y del reinicio del teléfono |
| `ClimaApp.kt` | Detecta si la app está en primer plano (para no notificar) |
| `Departments.kt` | Lista de departamentos y coincidencia de nombres |
| `SnapshotStore.kt` | Extracción y guardado del texto del aviso |
| `Prefs.kt` | Preferencias y configuración |

## Seguridad

No subas al repositorio archivos `.jks`, `.keystore` ni `local.properties`. El `.gitignore` ya los excluye.

## Aviso

Esta app no es oficial ni está afiliada a la Dirección de Meteorología e Hidrología. Ante emergencias, consulta siempre los canales oficiales.

## Licencia

Distribuido bajo la licencia MIT. Consulta el archivo [LICENSE](LICENSE) para más detalles.
