param(
    [string]$ServerIp,
    [switch]$RegenerateCertificate
)

$ErrorActionPreference = 'Stop'
$appDirectory = $PSScriptRoot
$certificateDirectory = Join-Path $env:USERPROFILE '.cctv'
$keystorePath = Join-Path $certificateDirectory 'local-development.p12'
$publicCertificatePath = Join-Path $certificateDirectory 'local-development.cer'
$passwordPath = Join-Path $certificateDirectory 'local-development.password'
$serverIpPath = Join-Path $certificateDirectory 'local-development.ip'
$keystorePassword = $null

if (-not $ServerIp) {
    $route = Get-NetRoute -AddressFamily IPv4 -DestinationPrefix '0.0.0.0/0' |
        Sort-Object RouteMetric |
        Select-Object -First 1
    if ($route) {
        $ServerIp = Get-NetIPAddress -AddressFamily IPv4 -InterfaceIndex $route.InterfaceIndex |
            Where-Object { $_.IPAddress -notlike '169.254.*' } |
            Select-Object -First 1 -ExpandProperty IPAddress
    }
}

$parsedIp = $null
if (-not $ServerIp -or -not [System.Net.IPAddress]::TryParse($ServerIp, [ref]$parsedIp) -or
    $parsedIp.AddressFamily -ne [System.Net.Sockets.AddressFamily]::InterNetwork) {
    throw 'Supply the server laptop IPv4 address with -ServerIp, for example -ServerIp 192.168.1.20.'
}

$keytool = (Get-Command keytool.exe -ErrorAction SilentlyContinue).Source
if (-not $keytool) {
    throw 'keytool.exe was not found. Install a Java 17 JDK and ensure its bin directory is on PATH.'
}

$pom = [xml](Get-Content (Join-Path $appDirectory 'pom.xml') -Raw)
$jarPath = Join-Path $appDirectory ("target\{0}-{1}.jar" -f $pom.project.artifactId, $pom.project.version)
if (-not (Test-Path -LiteralPath $jarPath)) {
    throw "Application JAR not found at '$jarPath'. Build it first with .\mvnw.cmd package."
}

New-Item -ItemType Directory -Path $certificateDirectory -Force | Out-Null
if ($RegenerateCertificate) {
    foreach ($path in @($keystorePath, $publicCertificatePath, $passwordPath, $serverIpPath)) {
        if (Test-Path -LiteralPath $path) {
            Remove-Item -LiteralPath $path -Force
        }
    }
}

if (Test-Path -LiteralPath $keystorePath) {
    if (-not (Test-Path -LiteralPath $passwordPath) -or
        -not (Test-Path -LiteralPath $serverIpPath) -or
        (Get-Content -LiteralPath $serverIpPath -Raw).Trim() -ne $ServerIp) {
        throw "The saved certificate does not match $ServerIp. Re-run with -RegenerateCertificate and re-import the new .cer file on the second laptop."
    }
    $keystorePassword = (Get-Content -LiteralPath $passwordPath -Raw).Trim()
} else {
    $randomBytes = New-Object byte[] 24
    $randomGenerator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $randomGenerator.GetBytes($randomBytes)
    } finally {
        $randomGenerator.Dispose()
    }
    $keystorePassword = ([Convert]::ToBase64String($randomBytes)).Replace('+', 'A').Replace('/', 'B').TrimEnd('=')
    [System.IO.File]::WriteAllText($passwordPath, $keystorePassword)
    [System.IO.File]::WriteAllText($serverIpPath, $ServerIp)

    & $keytool -genkeypair -noprompt -alias cctv-local -keyalg RSA -keysize 2048 `
        -storetype PKCS12 -keystore $keystorePath -storepass $keystorePassword `
        -keypass $keystorePassword -validity 3650 -dname "CN=$ServerIp" `
        -ext "SAN=IP:$ServerIp,IP:127.0.0.1,DNS:localhost"
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not generate the local HTTPS certificate.'
    }

    & $keytool -exportcert -noprompt -alias cctv-local -keystore $keystorePath `
        -storepass $keystorePassword -file $publicCertificatePath
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not export the public certificate.'
    }
}

Write-Host "Local CCTV HTTPS: https://${ServerIp}:8443"
Write-Host "Public certificate to copy to the second laptop: $publicCertificatePath"
Write-Host 'On the second laptop, import that certificate into the Current User Trusted Root Certification Authorities store, restart the browser, and open the HTTPS URL.'
Write-Host 'Allow Java through Windows Firewall on the Private network if prompted.'

$httpsProperties = @(
    '--streaming.mode=local',
    '--server.address=0.0.0.0',
    '--server.port=8443',
    '--server.ssl.enabled=true',
    "--server.ssl.key-store=file:$($keystorePath.Replace('\', '/'))",
    '--server.ssl.key-store-type=PKCS12',
    '--server.ssl.key-alias=cctv-local',
    "--server.ssl.key-store-password=$keystorePassword"
)

Push-Location $appDirectory
try {
    & java -jar $jarPath @httpsProperties
} finally {
    Pop-Location
    $keystorePassword = $null
}
