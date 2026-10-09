# Pruebas de humo de la API (solo lectura: no crea ni modifica datos).
# Uso, con la app corriendo:   powershell -ExecutionPolicy Bypass -File scripts\probar-api.ps1
# Contra Railway:              powershell -ExecutionPolicy Bypass -File scripts\probar-api.ps1 https://odontosystem-production.up.railway.app
param([string]$Base = "http://localhost:8080")

$ok = 0; $fallas = 0
function Probar($nombre, $url, $esperado) {
    try {
        $r = Invoke-WebRequest -Uri ($Base + $url) -UseBasicParsing -ErrorAction Stop
        $codigo = [int]$r.StatusCode; $cuerpo = [System.Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray())
    } catch {
        $resp = $_.Exception.Response
        if ($null -eq $resp) { Write-Host "[FALLA] $nombre -> sin respuesta: $($_.Exception.Message)" -ForegroundColor Red; $script:fallas++; return $null }
        $codigo = [int]$resp.StatusCode; $cuerpo = $_.ErrorDetails.Message
    }
    if ($codigo -eq $esperado) { Write-Host "[OK]    $nombre -> $codigo" -ForegroundColor Green; $script:ok++ }
    else { Write-Host "[FALLA] $nombre -> $codigo (esperado $esperado) $cuerpo" -ForegroundColor Red; $script:fallas++ }
    return $cuerpo
}

Write-Host "Probando $Base`n"

Probar "Salud de la app (Railway)" "/actuator/health" 200 | Out-Null
$esp = Probar "Catalogo de especialidades" "/api/catalogos/especialidades" 200
if ($esp) { Write-Host "        $((($esp | ConvertFrom-Json) | ForEach-Object { $_.nombre }) -join ', ')" }
Probar "Catalogo de categorias de servicio" "/api/catalogos/categorias-servicio" 200 | Out-Null
$dis = Probar "Catalogo de distritos" "/api/catalogos/distritos" 200
if ($dis) { Write-Host "        Distritos con odontologos: $dis" }

$busq = Probar "Buscador sin filtros (sin login)" "/api/odontologos" 200
Probar "Buscador por distrito sin tildes" "/api/odontologos?distrito=la%20tinguina" 200 | Out-Null
Probar "Buscador cerca de Plaza de Armas de Ica" "/api/odontologos?lat=-14.0639&lng=-75.7292&radio_km=10" 200 | Out-Null
Probar "Buscador con lat sin lng -> 400" "/api/odontologos?lat=-14.06" 400 | Out-Null
Probar "Perfil que no existe -> 404" "/api/odontologos/00000000-0000-0000-0000-000000000000" 404 | Out-Null

Probar "Mis datos sin token -> 401" "/api/usuarios/yo" 401 | Out-Null
Probar "Mi consultorio sin token -> 401" "/api/odontologos/yo/perfil" 401 | Out-Null
Probar "Mis citas sin token -> 401" "/api/citas/mias" 401 | Out-Null
Probar "Pagar sin token -> 401" "/api/citas/00000000-0000-0000-0000-000000000000/pagar" 401 | Out-Null

# Si hay odontologos, revisa el primero: perfil y turnos libres de los proximos 7 dias
if ($busq) {
    $pagina = $busq | ConvertFrom-Json
    Write-Host "        Odontologos encontrados: $($pagina.total_elementos)"
    if ($pagina.total_elementos -gt 0) {
        $o = $pagina.contenido[0]
        $perfil = Probar "Perfil publico de $($o.nombre)" "/api/odontologos/$($o.id)" 200
        $p = $perfil | ConvertFrom-Json
        Write-Host "        Servicios: $($p.servicios.Count)  Tramos de horario: $($p.horarios.Count)"
        if ($p.servicios.Count -gt 0) {
            $s = $p.servicios[0]
            for ($d = 0; $d -lt 7; $d++) {
                $fecha = (Get-Date).AddDays($d).ToString("yyyy-MM-dd")
                $disp = Probar "Turnos libres $fecha ($($s.titulo))" "/api/odontologos/$($o.id)/disponibilidad?servicio_id=$($s.id)&fecha=$fecha" 200
                if ($disp) { Write-Host "        $((($disp | ConvertFrom-Json).turnos).Count) turnos" }
            }
        } else { Write-Host "        (Sin servicios activos: no se puede probar la disponibilidad)" -ForegroundColor Yellow }
    } else { Write-Host "        (No hay odontologos VERIFICADOS todavia: el perfil y la disponibilidad se prueban cuando haya datos)" -ForegroundColor Yellow }
}

Write-Host "`nResultado: $ok OK, $fallas fallas"
