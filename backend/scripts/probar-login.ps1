# Prueba del limite de intentos de login (rama feature/limite-login).
# Crea un paciente de prueba desechable, falla la contrasena 5 veces y revisa que:
#   intentos 1-2 -> 401, intentos 3-4 -> 401 avisando cuantos quedan, intento 5 -> 429 con Retry-After,
#   y que despues ni la contrasena correcta entra (sigue 429).
# Si el quinto intento NO da 429, Redis no esta respondiendo (el login sigue sin limite a proposito).
# Uso, con la app corriendo:  powershell -ExecutionPolicy Bypass -File scripts\probar-login.ps1
# Contra otro servidor:       powershell -ExecutionPolicy Bypass -File scripts\probar-login.ps1 https://...
# La cuenta de prueba queda bloqueada 15 minutos; no afecta a ninguna cuenta real.
param([string]$Base = "http://localhost:8080")

$ok = 0; $fallas = 0

# Devuelve codigo HTTP, mensaje y Retry-After (funciona en Windows PowerShell 5 y en PowerShell 7)
function Login($correo, $clave) {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes((@{ correo = $correo; password = $clave } | ConvertTo-Json -Compress))
    try {
        $r = Invoke-WebRequest -Uri "$Base/api/auth/login" -Method POST -Body $bytes `
            -ContentType "application/json; charset=utf-8" -UseBasicParsing -ErrorAction Stop
        return @{ codigo = [int]$r.StatusCode; mensaje = "login correcto"; espera = $null }
    } catch {
        $resp = $_.Exception.Response
        if (-not $resp) { return @{ codigo = 0; mensaje = "sin respuesta del servidor"; espera = $null } }
        $espera = $null
        try { $espera = $resp.Headers["Retry-After"] } catch {}
        if (-not $espera) { try { $espera = [int]$resp.Headers.RetryAfter.Delta.TotalSeconds } catch {} }
        # Cuerpo del error: en PowerShell 7 viene en ErrorDetails; en Windows PowerShell 5 a veces hay que leerlo del stream
        $cuerpo = $_.ErrorDetails.Message
        if (-not $cuerpo) {
            try {
                $lector = New-Object System.IO.StreamReader($resp.GetResponseStream(), [System.Text.Encoding]::UTF8)
                $cuerpo = $lector.ReadToEnd()
            } catch {}
        }
        $mensaje = ""
        if ($cuerpo) {
            try { $mensaje = ($cuerpo | ConvertFrom-Json -ErrorAction Stop).mensaje } catch { $mensaje = $cuerpo }
        }
        return @{ codigo = [int]$resp.StatusCode; mensaje = $mensaje; espera = $espera }
    }
}

function Revisar($titulo, $r, $codigoEsperado, $debeContener) {
    $texto = "$titulo -> $($r.codigo): $($r.mensaje)"
    if ($r.espera) { $texto += "  (Retry-After: $($r.espera) s)" }
    $bien = ($r.codigo -eq $codigoEsperado) -and (-not $debeContener -or ($r.mensaje -match $debeContener))
    if ($bien) { Write-Host "[OK]    $texto" -ForegroundColor Green; $script:ok++ }
    else { Write-Host "[FALLA] $texto  (se esperaba $codigoEsperado$(if ($debeContener) { " con '$debeContener'" }))" -ForegroundColor Red; $script:fallas++ }
}

Write-Host "Probando el limite de intentos de login en $Base`n"

# 1. Paciente de prueba nuevo (correo y contrasena al azar; la contrasena no se muestra)
$sufijo = Get-Random -Minimum 1000000 -Maximum 9999999
$correo = "prueba.login.$sufijo@odontosystem.pe"
$clave = "Prb-" + [guid]::NewGuid().ToString("N").Substring(0, 16)
$registro = @{ numero_documento = "8$sufijo"; tipo_documento = "DNI"; nombre_completo = "Paciente Prueba Login"
    telefono = "910$sufijo".Substring(0, 9); correo = $correo; password = $clave; rol = "PACIENTE"; acepta_privacidad = $true } | ConvertTo-Json -Compress
try {
    Invoke-WebRequest -Uri "$Base/api/auth/register" -Method POST -Body ([System.Text.Encoding]::UTF8.GetBytes($registro)) `
        -ContentType "application/json; charset=utf-8" -UseBasicParsing -ErrorAction Stop | Out-Null
    Write-Host "[OK]    Paciente de prueba creado: $correo" -ForegroundColor Green; $ok++
} catch {
    Write-Host "[FALLA] No se pudo crear el paciente de prueba: $($_.ErrorDetails.Message)" -ForegroundColor Red
    exit 1
}

# 2. Login correcto (deja el contador en cero)
Revisar "Login con la contrasena correcta" (Login $correo $clave) 200 $null

# 3. Cinco contrasenas incorrectas
Revisar "Fallo 1" (Login $correo "incorrecta-1") 401 $null
Revisar "Fallo 2" (Login $correo "incorrecta-2") 401 $null
Revisar "Fallo 3" (Login $correo "incorrecta-3") 401 "quedan 2"
Revisar "Fallo 4" (Login $correo "incorrecta-4") 401 "queda 1"
$quinto = Login $correo "incorrecta-5"
Revisar "Fallo 5" $quinto 429 $null
if ($quinto.codigo -eq 401) {
    Write-Host "        El quinto intento no bloqueo: Redis no esta respondiendo (revisa REDIS_* y Upstash)." -ForegroundColor Yellow
}

# 4. Bloqueada: ni la contrasena correcta entra
Revisar "Contrasena correcta durante el bloqueo" (Login $correo $clave) 429 $null
$clave = $null

Write-Host "`nResultado: $ok OK, $fallas con falla"
Write-Host "La cuenta $correo queda bloqueada 15 minutos (es solo de prueba)."
