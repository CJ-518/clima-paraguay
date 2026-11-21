# Clima Paraguay

App Android que vigila los avisos meteorológicos de la Dirección de Meteorología e Hidrología de Paraguay y envía una notificación cuando un aviso incluye alguno de los departamentos que elegiste.

Fuente de datos: <https://www.meteorologia.gov.py/avisos/>

## Funciones

- Selección de uno o varios de los 18 departamentos (incluye Asunción como capital).
- Revisión periódica en segundo plano (cada 15 minutos o más).
- Notificación solo cuando el aviso es nuevo o cambió, para no repetir alertas.
- Botón **Revisar ahora** para probar manualmente (notifica siempre que haya coincidencia).
- Guarda el texto del aviso y permite verlo desde la notificación o con **Ver último aviso guardado**.
- Coincidencia por palabra completa, por lo que "Alto Paraguay" no se confunde con "Alto Paraná".

## Requisitos

- Android 8.0 (API 26) o superior.
- Permiso de notificaciones (Android 13+).
- Conexión a internet.

## Instalación

1. Ve a la sección **Releases** de este repositorio.
2. Descarga el archivo `.apk` de la última versión.
3. Ábrelo en tu teléfono y permite la instalación desde orígenes desconocidos si Android lo pide.
4. Abre la app y concede el permiso de notificaciones.

## Uso

1. Marca los departamentos que quieres vigilar.
2. Indica cada cuántos minutos revisar (mínimo 15).
3. Pulsa **Guardar** para activar el monitoreo.
4. Opcional: pulsa **Revisar ahora** para comprobar que funciona.

Android ejecuta las revisiones periódicas de forma aproximada. La hora exacta depende de la batería y de la red.

## Compilar desde el código

Requisitos: Android Studio reciente y JDK 17.

```powershell
git clone https://github.com/TU_USUARIO/clima-paraguay.git
cd clima-paraguay
.\gradlew assembleDebug
```

El APK queda en `app/build/outputs/apk/debug/app-debug.apk`.

Para un APK de release firmado, usa en Android Studio *Build > Generate Signed App Bundle / APK*.

## Publicar una versión

1. Sube `versionCode` y `versionName` en `app/build.gradle.kts`.
2. Compila y firma el APK.
3. Publica el release:

```powershell
gh release create v1.1 ruta\al\app-release.apk --title "Clima Paraguay v1.1" --notes "Descripción de los cambios"
```

## Estructura

| Archivo | Función |
| --- | --- |
| `MainActivity.kt` | Pantalla principal y ajustes |
| `CheckWorker.kt` | Revisión en segundo plano y notificaciones |
| `Departments.kt` | Lista de departamentos y coincidencia de nombres |
| `SnapshotStore.kt` | Extracción y guardado del texto del aviso |
| `SnapshotActivity.kt` | Pantalla para ver el aviso guardado |
| `Prefs.kt` | Preferencias y configuración |

## Seguridad

No subas al repositorio archivos `.jks`, `.keystore` ni `local.properties`. El `.gitignore` ya los excluye.

## Aviso

Esta app no es oficial ni está afiliada a la Dirección de Meteorología e Hidrología. Ante emergencias, consulta siempre los canales oficiales.

## Licencia

Distribuido bajo la licencia MIT. Consulta el archivo [LICENSE](LICENSE) para más detalles.
