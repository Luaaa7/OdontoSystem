# Prueba real de los correos (Brevo): reserva y paga una cita con TU cuenta de paciente.
# Te deben llegar: "Tu cita esta confirmada" al instante y el recordatorio ~2 h antes de la cita.
# El turno se elige ~2 h 15 min desde ahora, asi el recordatorio llega en unos 15-20 minutos.
#
# Uso:       powershell -ExecutionPolicy Bypass -File scripts\probar-correo.ps1
# Cancelar:  powershell -ExecutionPolicy Bypass -File scripts\probar-correo.ps1 -Cancelar <id-de-la-cita>
#
# Pide tu correo real y una contrasena (no se muestra ni se guarda). Si la cuenta no existe, la crea como PACIENTE.
param(
    [string]$Base = "https://odontosystem-production.up.railway.app",
    [string]$Cancelar = ""
)

function Llamar($metodo, $url, $cuerpo, $token) {
    $headers = @{}
    if ($token) { $headers["Authorization"] = "Bearer $token" }
    $params = @{ Uri = ($Base + $url); Method = $metodo; Headers = $headers; UseBasicParsing = $true; ErrorAction = "Stop" }
    if ($null -ne $cuerpo) {
        $params.Body = [System.Text.Encoding]::UTF8.GetBytes(($cuerpo | ConvertTo-Json -Compress))
        $params.ContentType = "application/json; charset=utf-8"
    }
    try {
        $r = Invoke-WebRequest @params
        return [System.Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray()) | ConvertFrom-Json
    } catch {
        $resp = $_.Exception.Response
        $script:ultimoCodigo = if ($resp) { [int]$resp.StatusCode } else { 0 }
        $script:ultimoError = $_.ErrorDetails.Message
        return $null
    }
}

# Fechas: PowerShell a veces las convierte a DateTime; se vuelven a texto ISO con zona
function ComoTexto($valor) {
    if ($valor -is [datetime]) { return ([DateTimeOffset]$valor).ToString("yyyy-MM-ddTHH:mm:sszzz") }
    return [string]$valor
}

Write-Host "Prueba de correos en $Base`n"
$correo = Read-Host "Tu correo REAL (donde quieres recibir los correos)"
$seguro = Read-Host "Contrasena de esa cuenta en OdontoSystem (si no existe, se crea con esta; minimo 8)" -AsSecureString
$clave = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($seguro))

$login = Llamar "POST" "/api/auth/login" @{ correo = $correo; password = $clave } $null
if (-not $login) {
    if ($script:ultimoCodigo -ne 401) { Write-Host "[FALLA] login -> $script:ultimoCodigo $script:ultimoError" -ForegroundColor Red; exit 1 }
    Write-Host "No existe esa cuenta (o la contrasena no coincide). Intento crearla como PACIENTE..."
    $dni = "7" + (Get-Random -Minimum 1000000 -Maximum 9999999)
    $login = Llamar "POST" "/api/auth/register" @{ numero_documento = $dni; tipo_documento = "DNI"
        nombre_completo = "Paciente Prueba Correos"; telefono = "9" + (Get-Random -Minimum 10000000 -Maximum 99999999)
        correo = $correo; password = $clave; rol = "PACIENTE" } $null
    if (-not $login) {
        Write-Host "[FALLA] No se pudo crear la cuenta -> $script:ultimoCodigo $script:ultimoError" -ForegroundColor Red
        Write-Host "Si el correo ya existe, la contrasena que escribiste no es la correcta." -ForegroundColor Yellow
        exit 1
    }
    Write-Host "[OK]    Cuenta creada" -ForegroundColor Green
}
$clave = $null
if ($login.rol -ne "PACIENTE") { Write-Host "Esa cuenta es $($login.rol); usa una cuenta PACIENTE." -ForegroundColor Yellow; exit 1 }
$token = $login.access_token
Write-Host "[OK]    Sesion iniciada como $($login.nombre_completo)" -ForegroundColor Green

# ---------------------------------------------------------------- cancelar
if ($Cancelar) {
    $c = Llamar "PATCH" "/api/citas/$Cancelar/cancelar" @{ motivo = "Prueba del sistema" } $token
    if (-not $c) { Write-Host "[FALLA] cancelar -> $script:ultimoCodigo $script:ultimoError" -ForegroundColor Red; exit 1 }
    Write-Host "[OK]    Cita cancelada: $($c.estado)" -ForegroundColor Green
    Write-Host "`nRevisa tu correo: 'Tu cita ... fue cancelada'."
    exit 0
}

# ---------------------------------------------------------------- buscar un turno ~2 h 15 min adelante
$desde = [DateTimeOffset]::Now.AddMinutes(135)
$pagina = Llamar "GET" "/api/odontologos?tamano=50" $null $null
$elegido = $null
foreach ($o in $pagina.contenido) {
    $perfil = Llamar "GET" "/api/odontologos/$($o.id)" $null $null
    if (-not $perfil -or $perfil.servicios.Count -eq 0) { continue }
    $s = $perfil.servicios[0]
    foreach ($d in 0..1) {
        $fecha = $desde.AddDays($d).ToString("yyyy-MM-dd")
        $disp = Llamar "GET" "/api/odontologos/$($o.id)/disponibilidad?servicio_id=$($s.id)&fecha=$fecha" $null $null
        if (-not $disp) { continue }
        foreach ($t in $disp.turnos) {
            $inicio = ComoTexto $t.inicio
            if ([DateTimeOffset]::Parse($inicio) -ge $desde) { $elegido = @{ o = $o; s = $s; inicio = $inicio }; break }
        }
        if ($elegido) { break }
    }
    if ($elegido) { break }
}
if (-not $elegido) { Write-Host "No encontre turnos libres en las proximas horas." -ForegroundColor Yellow; exit 1 }
Write-Host "        Turno: $($elegido.inicio) con $($elegido.o.nombre) ($($elegido.s.titulo))"

# ---------------------------------------------------------------- reservar y pagar
$cita = Llamar "POST" "/api/citas" @{ odontologo_id = $elegido.o.id; servicio_id = $elegido.s.id; inicio = $elegido.inicio } $token
if (-not $cita) { Write-Host "[FALLA] reservar -> $script:ultimoCodigo $script:ultimoError" -ForegroundColor Red; exit 1 }
Write-Host "[OK]    Cita reservada: $($cita.estado)" -ForegroundColor Green

$pago = Llamar "POST" "/api/citas/$($cita.id)/pagar" @{ metodo = "TARJETA" } $token
if (-not $pago) { Write-Host "[FALLA] pagar -> $script:ultimoCodigo $script:ultimoError" -ForegroundColor Red; exit 1 }
Write-Host "[OK]    Deposito pagado (simulado): $($pago.estado), cita $($pago.cita.estado)" -ForegroundColor Green

$recordatorio = [DateTimeOffset]::Parse($elegido.inicio).AddHours(-2).ToLocalTime().ToString("HH:mm")
Write-Host "`nRevisa tu correo $correo (tambien Spam):"
Write-Host "  1. Ahora:            'Tu cita esta confirmada'  (o 'Recibimos tu pago' si el odontologo confirma a mano)"
Write-Host "  2. Cerca de las $recordatorio : 'Recordatorio: tu cita de hoy a las ...'"
Write-Host "`nCuando termines de probar, cancela la cita para liberar el turno:"
Write-Host "  powershell -ExecutionPolicy Bypass -File scripts\probar-correo.ps1 -Cancelar $($cita.id)" -ForegroundColor Cyan
