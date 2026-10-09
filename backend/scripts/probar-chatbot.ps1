# Prueba de punta a punta del chatbot: conversa como un paciente real, paso a paso.
# NO crea citas: al llegar a "Confirmas?" responde "cancelar".
# Uso, con la app corriendo:  powershell -ExecutionPolicy Bypass -File scripts\probar-chatbot.ps1
# Contra otro servidor:       powershell -ExecutionPolicy Bypass -File scripts\probar-chatbot.ps1 https://...
# Pide el correo y la contrasena de una cuenta PACIENTE (la contrasena no se muestra ni se guarda).
# Si se deja el correo vacio, crea un paciente de prueba (prueba.chatbot.NNNNNNN@odontosystem.pe).
param([string]$Base = "http://localhost:8080")

$ok = 0; $fallas = 0

function Llamar($metodo, $url, $cuerpo, $token) {
    $headers = @{}
    if ($token) { $headers["Authorization"] = "Bearer $token" }
    $bytes = [System.Text.Encoding]::UTF8.GetBytes(($cuerpo | ConvertTo-Json -Compress))
    try {
        $r = Invoke-WebRequest -Uri ($Base + $url) -Method $metodo -Headers $headers -Body $bytes `
            -ContentType "application/json; charset=utf-8" -UseBasicParsing -ErrorAction Stop
        return [System.Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray()) | ConvertFrom-Json
    } catch {
        $resp = $_.Exception.Response
        $codigo = if ($resp) { [int]$resp.StatusCode } else { "sin respuesta" }
        Write-Host "[FALLA] $metodo $url -> $codigo $($_.ErrorDetails.Message)" -ForegroundColor Red
        $script:fallas++
        return $null
    }
}

# Manda un mensaje al bot, muestra la respuesta y revisa que el tipo sea el esperado
function Decir($texto, $tiposEsperados) {
    Write-Host "`nPaciente: $texto" -ForegroundColor Cyan
    $r = Llamar "POST" "/api/chatbot/mensaje" @{ mensaje = $texto } $script:token
    if (-not $r) { return $null }
    Write-Host "Bot ($($r.tipo)): $($r.mensaje)"
    if ($r.opciones) {
        $botones = ($r.opciones | Select-Object -First 6 | ForEach-Object { "[$($_.etiqueta)]" }) -join " "
        Write-Host "        Botones: $botones" -ForegroundColor DarkGray
    }
    if ($tiposEsperados -contains $r.tipo) { Write-Host "[OK]    tipo $($r.tipo)" -ForegroundColor Green; $script:ok++ }
    else { Write-Host "[FALLA] tipo $($r.tipo), se esperaba $($tiposEsperados -join ' o ')" -ForegroundColor Red; $script:fallas++ }
    return $r
}

Write-Host "Probando el chatbot en $Base`n"
$correo = Read-Host "Correo de una cuenta PACIENTE (Enter = crear un paciente de prueba)"
if ([string]::IsNullOrWhiteSpace($correo)) {
    # Paciente de prueba nuevo: correo y contrasena al azar, la contrasena no se muestra
    $sufijo = Get-Random -Minimum 1000000 -Maximum 9999999
    $correo = "prueba.chatbot.$sufijo@odontosystem.pe"
    $clave = "Prb-" + [guid]::NewGuid().ToString("N").Substring(0, 16)
    $registro = Llamar "POST" "/api/auth/register" @{
        numero_documento = "9$sufijo"; tipo_documento = "DNI"; nombre_completo = "Paciente Prueba Chatbot"
        telefono = "900$sufijo".Substring(0, 9); correo = $correo; password = $clave; rol = "PACIENTE" } $null
    if (-not $registro) { Write-Host "No se pudo crear el paciente de prueba."; exit 1 }
    Write-Host "[OK]    Paciente de prueba creado: $correo" -ForegroundColor Green; $ok++
} else {
    $seguro = Read-Host "Contrasena" -AsSecureString
    $clave = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($seguro))
}

$login = Llamar "POST" "/api/auth/login" @{ correo = $correo; password = $clave } $null
$clave = $null
if (-not $login) { Write-Host "No se pudo iniciar sesion."; exit 1 }
if ($login.rol -ne "PACIENTE") { Write-Host "Esa cuenta es $($login.rol); usa una cuenta PACIENTE." -ForegroundColor Yellow; exit 1 }
$token = $login.access_token
Write-Host "[OK]    Login como $($login.nombre_completo)" -ForegroundColor Green; $ok++

Decir "hola" @("texto_simple") | Out-Null

# Por si quedo un flujo a medias de otra prueba
Decir "cancelar" @("texto_simple") | Out-Null

$r = Decir "busco un dentista" @("texto_simple")
$distrito = ($r.opciones | Where-Object { $_.accion -eq "elegir_distrito" } | Select-Object -First 1).valor
if (-not $distrito) { Write-Host "No hay distritos con odontologos: no se puede seguir." -ForegroundColor Yellow; exit 1 }

# Memoria de contexto: el distrito solo, sin "busco..."
$r = Decir $distrito @("lista_odontologos")
$r = Decir "1" @("lista_servicios", "lista_horarios", "texto_simple")
if ($r.tipo -eq "lista_servicios") { $r = Decir "1" @("lista_horarios", "texto_simple") }

if ($r.tipo -eq "lista_horarios") {
    # Memoria de contexto: otro dia con el mismo odontologo y servicio
    $dia = ([datetime]::Parse($r.datos.fecha)).DayOfWeek
    $otro = if ($dia -eq "Monday") { "y el martes?" } else { "y el lunes?" }
    $r2 = Decir $otro @("lista_horarios", "texto_simple")
    if ($r2.tipo -eq "lista_horarios") { $r = $r2 }

    $hora = ($r.opciones | Where-Object { $_.accion -eq "elegir_turno" } | Select-Object -First 1).etiqueta
    $r = Decir "a las $hora" @("confirmacion")
    # No reservamos: se cancela antes de confirmar
    Decir "cancelar" @("texto_simple") | Out-Null
} else {
    Write-Host "        (Ese odontologo no tiene turnos libres en 2 semanas; se salta la parte de horarios)" -ForegroundColor Yellow
}

Decir "mis citas" @("lista_citas", "texto_simple") | Out-Null
Decir "asdfgh" @("texto_simple") | Out-Null

Write-Host "`nResultado: $ok OK, $fallas fallas"
