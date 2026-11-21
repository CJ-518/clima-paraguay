# java17.ps1
# Configura JDK 17 (Temurin) en la ventana actual de PowerShell.
# Solo afecta a esta ventana; al cerrarla todo vuelve a como estaba.
#
# Uso:
#   C:\Android\java17.ps1             -> solo configura Java 17
#   C:\Android\java17.ps1 -Compilar   -> configura Java 17 y compila el APK de debug
#                                        (ejecutalo desde la carpeta del proyecto)

param(
    [switch]$Compilar
)

$base = "C:\Program Files\Eclipse Adoptium"

# Busca la carpeta jdk-17* mas reciente
$jdk = Get-ChildItem $base -Directory -Filter "jdk-17*" -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending |
    Select-Object -First 1

if (-not $jdk) {
    Write-Host "No se encontro ningun JDK 17 en $base" -ForegroundColor Red
    Write-Host "Instalalo con: winget install EclipseAdoptium.Temurin.17.JDK" -ForegroundColor Yellow
    return
}

# Quita del Path el JAVA_HOME anterior para no acumular entradas si se ejecuta varias veces
if ($env:JAVA_HOME) {
    $env:Path = ($env:Path -split ";" | Where-Object { $_ -ne "$env:JAVA_HOME\bin" }) -join ";"
}

$env:JAVA_HOME = $jdk.FullName
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

Write-Host "JAVA_HOME = $env:JAVA_HOME" -ForegroundColor Green
java -version

if ($Compilar) {
    if (-not (Test-Path ".\gradlew.bat")) {
        Write-Host "No se encontro gradlew.bat aqui. Ve primero a la carpeta del proyecto." -ForegroundColor Red
        return
    }
    .\gradlew.bat --stop
    .\gradlew.bat assembleDebug
}
