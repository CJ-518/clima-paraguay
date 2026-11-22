# Clima Paraguay

App Android que vigila los avisos meteorológicos de la Dirección de Meteorología e Hidrología de Paraguay y envía una notificación cuando un aviso incluye alguno de los departamentos que elegiste.

Fuente de datos: <https://www.meteorologia.gov.py/avisos/>

## Funciones

- Pantalla principal con el último aviso guardado, con formato (título, fenómeno esperado destacado y departamentos elegidos resaltados con otro color de letra).
- **Imagen del aviso**: si la página publica una imagen junto al aviso (por ejemplo, el mapa de zonas afectadas), la app la descarga y la muestra bajo el texto. La imagen se borra cuando sale un aviso nuevo o cuando la página ya no tiene aviso. Al tocarla se abre a pantalla completa con zoom (dos dedos), desplazamiento y doble toque para restablecer.
- Al abrir la app se hace una actualización automática y siempre se muestra el último aviso guardado.
- Se guardan todos los avisos nuevos que publica la página, mencionen o no tus departamentos.
- Notificación solo cuando el aviso es nuevo o cambió y menciona alguno de los departamentos elegidos, para no repetir alertas.
- Sin notificaciones mientras la app está abierta: el aviso ya se ve en pantalla.
- Icono de **Actualizar** en la esquina superior derecha, y botones para **Copiar aviso** (al portapapeles) y **Abrir página** (en el navegador).
- Pantalla de **Configuración** (icono de tuerca):
  - Monitoreo activado o desactivado.
  - Selección de uno o varios de los 18 departamentos (incluye Asunción como capital) en un menú desplegable.
  - Intervalo de revisión de 15, 30, 45 o 60 minutos.
  - Sección **Acerca de**: muestra la versión instalada, permite **buscar actualizaciones** en los Releases de GitHub, descargar el APK nuevo con barra de progreso e instalarlo, y tiene un botón para abrir el repositorio del proyecto.
  - Apariencia: colores dinámicos del teléfono (Android 12 o superior) o tema manual con cinco colores a elegir, y modo claro, oscuro o el del sistema.
- Los cambios de configuración se guardan al instante.
- Coincidencia por palabra completa, por lo que "Alto Paraguay" no se confunde con "Alto Paraná".

## Requisitos

- Android 8.0 (API 26) o superior.
- Permiso de notificaciones (Android 13+).
- Permiso de alarmas y recordatorios (Android 12+), para que las revisiones sean puntuales.
- Conexión a internet.
- Permiso para instalar aplicaciones desde la propia app (solo se pide la primera vez que se actualiza desde Configuración).
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
5. Vuelve a la pantalla principal. Opcional: toca el icono de **Actualizar** (arriba a la derecha) para comprobar que funciona.
6. Si el aviso trae imagen, toca la imagen para verla ampliada.

Android ejecuta las revisiones periódicas de forma aproximada. La hora exacta depende de la batería y de la red.

## Actualizar la app

En **Configuración > Acerca de** se ve la versión instalada y el botón **Buscar actualizaciones**:

1. La app consulta el último release publicado en GitHub y lo compara con la versión instalada.
2. Si hay una versión más nueva, muestra sus notas y el botón **Descargar e instalar**.
3. Al terminar la descarga (con barra de progreso) se abre el instalador de Android, que pide la confirmación. La primera vez, Android pide además permitir a Clima Paraguay instalar aplicaciones.

Para que funcione, cada release debe cumplir lo siguiente (`release.ps1` ya lo hace):

- La etiqueta es `v` + `versionName` de `app/build.gradle.kts` (por ejemplo `v1.5` para la versión `1.5`).
- Lleva adjunto un archivo `.apk`.
- El APK nuevo está firmado con la misma clave que el instalado; si no, Android rechaza la actualización. Compilando siempre en la misma PC con `release.ps1` se usa la misma clave de depuración.

Los borradores y pre-lanzamientos no se ofrecen como actualización.

## Compilar desde el código

Requisitos: Android Studio reciente y JDK 17.

1. Abre la carpeta del proyecto en Android Studio y espera a que termine la sincronización de Gradle.
2. Compila el APK de depuración desde una terminal en la raíz del proyecto:

```powershell
.\gradlew assembleDebug
```

El APK queda en `app/build/outputs/apk/debug/app-debug.apk`.

Si el JDK 17 no está configurado en tu ventana de PowerShell, `java17.ps1` lo configura (con `-Compilar` también compila).

Para un APK de release firmado, usa en Android Studio *Build > Generate Signed App Bundle / APK*.

## Publicar una versión

El script `release.ps1` compila el APK de depuración y crea el release en GitHub con `gh release create`. Requiere [GitHub CLI](https://cli.github.com/) con sesión iniciada (`gh auth login`) y JDK 17.

```powershell
# Publica la versión que ya figura en app/build.gradle.kts
.\release.ps1 -Notas "Texto de las notas"

# Sube la versión a 1.4 (versionName y versionCode), hace commit y push, compila y publica v1.4
.\release.ps1 -Version 1.4 -Notas "Texto de las notas" -SubirCambios
```

Opciones:

| Opción | Efecto |
| --- | --- |
| `-Version 1.4` | Cambia `versionName` y suma 1 a `versionCode` en `app/build.gradle.kts` |
| `-Notas "..."` / `-NotasArchivo notas.txt` | Notas del release. Si no se indican, el script las pide o las genera GitHub desde los commits |
| `-SubirCambios` | Hace `git add -A`, commit y push antes de publicar, para que la etiqueta incluya tus cambios |
| `-Borrador` | Crea el release como borrador |
| `-PreRelease` | Lo marca como pre-lanzamiento |
| `-SinCompilar` | No compila: usa el APK de depuración ya existente |

El APK se sube con el nombre `ClimaParaguay-vX.Y.apk`. Si el release ya existe, el script se detiene sin pisarlo.

Para publicar a mano: sube `versionCode` y `versionName`, compila, y en GitHub entra en **Releases > Draft a new release**, crea una etiqueta nueva, adjunta el APK y publica.

## Estructura

| Archivo | Función |
| --- | --- |
| `MainActivity.kt` | Actividad de entrada y pantalla principal (último aviso, imagen ampliable e icono de actualizar) |
| `SettingsScreen.kt` | Pantalla de configuración (incluye la versión, la búsqueda de actualizaciones y el botón al repositorio) y programación de las revisiones |
| `UpdateChecker.kt` | Versión instalada, consulta del último release en GitHub, descarga del APK e inicio de la instalación |
| `res/xml/file_paths.xml` | Carpeta que el FileProvider comparte con el instalador de Android (el APK descargado) |
| `AppTheme.kt` | Ajustes de apariencia y colores de la app |
| `AlertFormatter.kt` | Formato del aviso guardado al mostrarlo |
| `CheckWorker.kt` | Revisión en segundo plano, descarga de la imagen y notificaciones |
| `AlarmScheduler.kt` | Alarmas exactas para las revisiones periódicas |
| `WatcherReceivers.kt` | Receptores de la alarma y del reinicio del teléfono |
| `ClimaApp.kt` | Detecta si la app está en primer plano (para no notificar) |
| `Departments.kt` | Lista de departamentos y coincidencia de nombres |
| `SnapshotStore.kt` | Extracción y guardado del texto y de la imagen del aviso |
| `Prefs.kt` | Preferencias y configuración |
| `release.ps1` | Compila el APK y crea el release en GitHub |
| `java17.ps1` | Configura JDK 17 en la ventana de PowerShell (y compila con `-Compilar`) |

## Seguridad

No subas al repositorio archivos `.jks`, `.keystore` ni `local.properties`. El `.gitignore` ya los excluye.

## Aviso

Esta app no es oficial ni está afiliada a la Dirección de Meteorología e Hidrología. Ante emergencias, consulta siempre los canales oficiales.

## Licencia

Distribuido bajo la licencia MIT. Consulta el archivo [LICENSE](LICENSE) para más detalles.
