# release.ps1
# Compila el APK y crea un release en GitHub con "gh release create".
# Ejecutalo desde la carpeta del proyecto (o desde cualquier lado: el script se ubica solo).
#
# Requisitos: JDK 17 (ver java17.ps1) y GitHub CLI (gh) con sesion iniciada: gh auth login
#
# Ejemplos:
#   .\release.ps1 -Notas "Imagen del aviso y nuevo boton de actualizar"
#       -> publica la version que ya figura en app\build.gradle.kts (por ejemplo v1.3)
#
#   .\release.ps1 -Version 1.4 -Notas "Imagen del aviso" -SubirCambios
#       -> sube versionName a 1.4 y versionCode en 1, hace commit y push de todo,
#          compila y publica v1.4
#
#   .\release.ps1 -Version 1.4 -NotasArchivo .\notas.txt -Borrador
#       -> igual pero las notas salen de un archivo y el release queda como borrador
#
#   .\release.ps1 -SinCompilar -Notas "..."
#       -> no compila: usa el APK de debug que ya existe

param(
    # Version nueva (ej. 1.4). Si se omite, se usa la que ya esta en app\build.gradle.kts
    [string]$Version,
    # Texto de las notas del release
    [string]$Notas,
    # Archivo de texto con las notas del release (alternativa a -Notas)
    [string]$NotasArchivo,
    # Crea el release como borrador (no se ve publico hasta que lo publiques en GitHub)
    [switch]$Borrador,
    # Lo marca como pre-lanzamiento
    [switch]$PreRelease,
    # No compila: usa el APK de debug ya existente
    [switch]$SinCompilar,
    # Hace git add -A, commit y push antes de publicar, para que la etiqueta incluya tus cambios
    [switch]$SubirCambios
)

$ErrorActionPreference = "Stop"

function Fallar($mensaje) {
    Write-Host $mensaje -ForegroundColor Red
    exit 1
}

# Ejecuta gh y devuelve solo su codigo de salida (sin mostrar nada ni lanzar errores por stderr)
function CodigoGh {
    $anterior = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & gh @args 2>&1 | Out-Null
    $codigo = $LASTEXITCODE
    $ErrorActionPreference = $anterior
    return $codigo
}

Push-Location $PSScriptRoot
try {
    # ---------- 1. Comprobaciones ----------
    if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
        Fallar "No se encontro GitHub CLI (gh). Instalalo con: winget install GitHub.cli"
    }
    if ((CodigoGh auth status) -ne 0) { Fallar "gh no tiene sesion iniciada. Ejecuta: gh auth login" }

    $gradleFile = Join-Path $PSScriptRoot "app\build.gradle.kts"
    if (-not (Test-Path $gradleFile)) { Fallar "No se encontro app\build.gradle.kts" }

    # ---------- 2. Version ----------
    $gradle = [System.IO.File]::ReadAllText($gradleFile)
    if ($gradle -notmatch 'versionCode\s*=\s*(\d+)') { Fallar "No se encontro versionCode en build.gradle.kts" }
    $versionCode = [int]$Matches[1]
    if ($gradle -notmatch 'versionName\s*=\s*"([^"]+)"') { Fallar "No se encontro versionName en build.gradle.kts" }
    $versionName = $Matches[1]

    if ($Version) {
        $Version = $Version -replace '^[vV]', ''
        if ($Version -notmatch '^\d+(\.\d+)*$') { Fallar "Version invalida: '$Version'. Usa un formato como 1.4 o 1.4.1" }
        if ($Version -ne $versionName) {
            $versionCode++
            $gradle = $gradle -replace 'versionCode\s*=\s*\d+', "versionCode = $versionCode"
            $gradle = $gradle -replace 'versionName\s*=\s*"[^"]+"', "versionName = `"$Version`""
            [System.IO.File]::WriteAllText($gradleFile, $gradle, (New-Object System.Text.UTF8Encoding($false)))
            $versionName = $Version
            Write-Host "build.gradle.kts actualizado: versionName $versionName, versionCode $versionCode" -ForegroundColor Green
        }
    }

    $tag = "v$versionName"
    $titulo = "Clima Paraguay $tag"

    # Si el release ya existe, no se sigue (evita pisar uno publicado)
    if ((CodigoGh release view $tag) -eq 0) {
        Fallar "El release $tag ya existe en GitHub. Usa -Version con un numero nuevo."
    }

    # ---------- 3. Notas ----------
    if ($NotasArchivo) {
        if (-not (Test-Path $NotasArchivo)) { Fallar "No se encontro el archivo de notas: $NotasArchivo" }
        $Notas = [System.IO.File]::ReadAllText((Resolve-Path $NotasArchivo))
    }
    if (-not $Notas) {
        $Notas = Read-Host "Notas del release (Enter para generarlas automaticamente desde los commits)"
    }

    # ---------- 4. Git ----------
    if (Get-Command git -ErrorAction SilentlyContinue) {
        $pendientes = git status --porcelain
        if ($SubirCambios) {
            if ($pendientes) {
                git add -A
                git commit -m "Version $versionName"
                if ($LASTEXITCODE -ne 0) { Fallar "Fallo el commit." }
            }
            git push
            if ($LASTEXITCODE -ne 0) { Fallar "Fallo el push." }
        }
        elseif ($pendientes) {
            Write-Host "Hay cambios sin subir a GitHub. La etiqueta $tag se crea sobre el ultimo commit subido," -ForegroundColor Yellow
            Write-Host "asi que esos cambios no quedarian en el codigo del release. Usa -SubirCambios para subirlos." -ForegroundColor Yellow
            $resp = Read-Host "Continuar igual? (s/N)"
            if ($resp -notmatch '^[sSyY]') { Fallar "Cancelado." }
        }
    }

    # ---------- 5. Compilar ----------
    $apk = Join-Path $PSScriptRoot "app\build\outputs\apk\debug\app-debug.apk"
    if (-not $SinCompilar) {
        # Si Java no es la version 17, se intenta configurar con java17.ps1 (esta junto a este script)
        $javaInfo = (& cmd /c "java -version 2>&1" | Select-Object -First 1)
        if ($javaInfo -notmatch '"17\.') {
            $java17 = Join-Path $PSScriptRoot "java17.ps1"
            if (Test-Path $java17) { . $java17 }
        }
        if (-not (Test-Path ".\gradlew.bat")) { Fallar "No se encontro gradlew.bat en la carpeta del proyecto." }
        & .\gradlew.bat assembleDebug
        if ($LASTEXITCODE -ne 0) { Fallar "La compilacion fallo." }
    }
    if (-not (Test-Path $apk)) { Fallar "No se encontro el APK: $apk" }

    # Copia con un nombre claro para que se descargue como ClimaParaguay-v1.3.apk
    $apkRelease = Join-Path (Split-Path $apk) "ClimaParaguay-$tag.apk"
    Copy-Item $apk $apkRelease -Force

    # ---------- 6. Crear el release ----------
    $ghArgs = @("release", "create", $tag, $apkRelease, "--title", $titulo)
    if ($Notas) {
        # Las notas van por archivo para evitar problemas de comillas y acentos
        $notasTmp = Join-Path ([System.IO.Path]::GetTempPath()) "notas-$tag.md"
        [System.IO.File]::WriteAllText($notasTmp, $Notas, (New-Object System.Text.UTF8Encoding($false)))
        $ghArgs += @("--notes-file", $notasTmp)
    }
    else {
        $ghArgs += "--generate-notes"
    }
    if ($Borrador)   { $ghArgs += "--draft" }
    if ($PreRelease) { $ghArgs += "--prerelease" }

    Write-Host "Creando release $tag..." -ForegroundColor Cyan
    & gh @ghArgs
    if ($LASTEXITCODE -ne 0) { Fallar "gh no pudo crear el release." }

    Write-Host "Listo: $titulo publicado." -ForegroundColor Green
}
finally {
    Pop-Location
}
