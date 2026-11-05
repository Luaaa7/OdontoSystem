# Pruebas de seguridad de OdontoSystem (OWASP)

Responde a la consigna de la fase de pruebas de seguridad: SAST/SCA, matriz de control de acceso (A01) y DAST con OWASP ZAP. Todo lo automático corre en GitHub Actions con el workflow [`seguridad.yml`](../../.github/workflows/seguridad.yml) (en cada PR a `main`, cada lunes y a mano).

## 1. Análisis de dependencias (SCA) y estático (SAST)

| Parte | Herramienta | Cómo se corre | Evidencia |
|---|---|---|---|
| Backend (Java/Maven) | OWASP Dependency-Check 12.1.0 (plugin de Maven) | `cd backend && ./mvnw dependency-check:check` o el job *SCA* del workflow | `backend/target/dependency-check-report.html` y el resumen del job |
| Web (React) | `npm audit` | `npm audit --omit=dev` en el repo de la web | [`evidencias/npm-audit-web-antes.txt`](evidencias/npm-audit-web-antes.txt) |
| App (Capacitor) | `npm audit` | `npm audit --omit=dev` en el repo de la app | [`evidencias/npm-audit-app-antes.txt`](evidencias/npm-audit-app-antes.txt) |

Resultado inicial del 05/11/2026 (solo librerías que llegan a producción, `--omit=dev`):

| Repo | Crítica | Alta | Moderada | Baja | Causa principal | Acción |
|---|---:|---:|---:|---:|---|---|
| Web | 1 | 5 | 0 | 7 | `dompurify` (crítica) y versiones viejas de `vite`, `zod`, `undici`, `seroval` | `npm audit fix` y volver a probar la web |
| App | 0 | 9 | 8 | 1 | El CLI `shadcn` está en `dependencies` y arrastra librerías de servidor (`@modelcontextprotocol/sdk`, `body-parser`, `systeminformation`) | Mover `shadcn` a `devDependencies` (es un generador de componentes, no se usa en la app) y `npm audit fix` |

Después de corregir se guardan `npm-audit-web-despues.txt` y `npm-audit-app-despues.txt` como evidencia.

## 2. Matriz de control de acceso (A01: Broken Access Control)

Roles: **Invitado** (sin sesión), **Paciente** y **Odontólogo**. No hay administrador: el equivalente a "ruta de administrador" son las rutas `/api/odontologos/yo/**` y `/api/pacientes/{id}`.

La prueba automática [`MatrizControlAccesoTest`](../../backend/src/test/java/com/odontosystem/OdontoSystem/security/MatrizControlAccesoTest.java) carga la configuración de seguridad real y comprueba cada ruta con los tres roles. Al terminar escribe la matriz obtenida en `backend/target/seguridad/matriz-control-acceso.md` (también aparece en el resumen del job).

| Endpoint | Invitado | Paciente | Odontólogo |
|---|---|---|---|
| `GET /api/odontologos` (catálogo) | 200 | 200 | 200 |
| `GET /api/public/pagos/config` | 200 | 200 | 200 |
| `GET /api/usuarios/yo` | 401 | 200 | 200 |
| `GET /api/usuarios/yo/notificaciones` | 401 | 200 | 200 |
| `GET /api/citas/mias` | 401 | 200 | 200 |
| `POST /api/citas` (reservar) | 401 | 200 | **403** |
| `POST /api/citas/{id}/pagar` | 401 | 200 | **403** |
| `GET /api/historias/mias` | 401 | 200 | **403** |
| `GET /api/usuarios/yo/reputacion` | 401 | 200 | **403** |
| `GET /api/odontologos/yo/pacientes` | 401 | **403** | 200 |
| `GET /api/pacientes/{id}` | 401 | **403** | 200 |
| `GET /api/odontologos/yo/liquidaciones` | 401 | **403** | 200 |
| `PATCH /api/odontologos/yo/citas/{id}/confirmar` | 401 | **403** | 200 |
| `POST /api/citas/{id}/historia` | 401 | **403** | 200 |

**Prueba obligatoria:** un paciente llama por API a `GET /api/odontologos/yo/pacientes` → **403 Forbidden** con `{"mensaje":"No tienes permiso para esta acción"}`. Se repite en producción con el token del paciente (F12 → Network) y se guarda la captura.

Además, cada servicio filtra por dueño: un paciente que pide la cita, la historia o la notificación de otro recibe **404** (pruebas unitarias de cada servicio).

## 3. Pruebas dinámicas (DAST) con OWASP ZAP

| Escaneo | Dónde | Tipo | Cómo |
|---|---|---|---|
| Baseline de la web | Vercel (producción) | Pasivo: solo observa las respuestas | Job *ZAP* del workflow; reporte en el artefacto `zap-baseline-web` |
| Escaneo de la API | Backend en **localhost** | Activo (ataca) | ZAP Desktop → *Import* → *OpenAPI* desde `http://localhost:8080/v3/api-docs` → *Active Scan* |

El escaneo activo se hace solo en local: contra producción crearía datos basura.

Cabeceras de seguridad ya aplicadas en el backend (`SecurityConfig`): `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Content-Security-Policy`, `Referrer-Policy: no-referrer`, `Permissions-Policy` y HSTS. La API no usa cookies (el token va en la cabecera `Authorization`), así que la alerta "Cookie sin atributo Secure" no aplica.

Formato del reporte (se llena con los resultados de ZAP):

| Hallazgo | Gravedad | Acción de mitigación |
|---|---|---|
| | | |
