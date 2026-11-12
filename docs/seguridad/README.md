# Pruebas de seguridad de OdontoSystem (OWASP)

Responde a la consigna de la fase de pruebas de seguridad: SAST/SCA, matriz de control de acceso (A01) y DAST con OWASP ZAP. Todo lo automático corre en GitHub Actions con el workflow [`seguridad.yml`](../../.github/workflows/seguridad.yml) (en cada PR a `main`, cada lunes y a mano).

## 1. Análisis de dependencias (SCA) y estático (SAST)

| Parte | Herramienta | Cómo se corre | Evidencia |
|---|---|---|---|
| Backend (Java/Maven) | OSV-Scanner v2.6.0 (Google; base OSV que reúne GitHub Advisories y NVD) | Job *SCA - OSV-Scanner* del workflow | [`evidencias/osv-backend-antes.md`](evidencias/osv-backend-antes.md) y el resumen del job |
| Backend (Dependency-Check) | OWASP Dependency-Check (plugin de Maven, con `backend/dependency-check-suppressions.xml`) | `cd backend && ./mvnw dependency-check:check` o el job *SCA - Dependency-Check* | Corrida de Sebastián Salas (11/11/2026): [`antes`](../security/dependency-check-report.html), [`después sin supresiones`](../security/dependency-check-DESPUES-sin-supresiones.html), [`después final`](../security/dependency-check-DESPUES-final.html). En CI depende de que la API del NVD responda (el 05/11 dio 522/524) |
| Web (React) | `npm audit` | `npm audit --omit=dev` en el repo de la web | [`evidencias/npm-audit-web-antes.txt`](evidencias/npm-audit-web-antes.txt) |
| App (Capacitor) | `npm audit` | `npm audit --omit=dev` en el repo de la app | [`evidencias/npm-audit-app-antes.txt`](evidencias/npm-audit-app-antes.txt) |

Resultado inicial del 05/11/2026 (solo librerías que llegan a producción, `--omit=dev`):

| Repo | Crítica | Alta | Moderada | Baja | Causa principal | Acción |
|---|---:|---:|---:|---:|---|---|
| Web | 1 | 5 | 0 | 7 | `dompurify` (crítica) y versiones viejas de `vite`, `zod`, `undici`, `seroval` | `npm audit fix` y volver a probar la web |
| App | 0 | 9 | 8 | 1 | El CLI `shadcn` está en `dependencies` y arrastra librerías de servidor (`@modelcontextprotocol/sdk`, `body-parser`, `systeminformation`) | Mover `shadcn` a `devDependencies` (es un generador de componentes, no se usa en la app) y `npm audit fix` |
| Backend | 8 | 16 | 16 | 0 | 15 librerías: Tomcat embebido 11.0.22 (4 críticas), Spring WebMVC 7.0.8 (2 críticas), Netty 4.2.15 (1 crítica), Jackson 2 y 3, PostgreSQL, HttpClient5, Log4j, Guava 33.4.8 y jsoup 1.17.2 | Subir Spring Boot de 4.1.0 a **4.1.2** (trae Tomcat 11.0.27, Spring 7.0.10, Jackson 2.21.7/3.1.7, Netty 4.2.19, PostgreSQL 42.7.14, HttpClient5 5.6.5, HttpCore5 5.4.4, Log4j 2.25.5), Guava a **33.7.2-jre** y jsoup a **1.23.2** |

Resultado de OWASP Dependency-Check en el backend (207 librerías analizadas):

| Momento | Librerías vulnerables | Vulnerabilidades | Qué cambió |
|---|---:|---:|---|
| Antes | 18 | 151 | Spring Boot 4.1.0, springdoc 3.0.0, pdfbox 3.0.5 |
| Después de actualizar | 4 | 8 | Spring Boot **4.1.2**, springdoc **3.2.0**, pdfbox **3.0.8** (más Guava y jsoup de la fila anterior) |
| Después, con supresiones justificadas | 0 | 0 (8 suprimidas) | DOMPurify 3.4.16 dentro de swagger-ui: riesgo aceptado hasta el 31/12/2026 (CVSS 6.1, Swagger apagado en producción con `SWAGGER_ENABLED=false`); `angus-activation` y `hibernate-validator`: falsos positivos por CPE genérico |

Resultado después de corregir (12/11/2026, Seguridad #9 en el PR #11):

| Repo | Antes | Después | Evidencia |
|---|---|---|---|
| Backend (OSV-Scanner) | 40 vulnerabilidades (8 críticas, 16 altas, 16 moderadas) | **0** (*No issues found*) | [`evidencias/osv-backend-antes.md`](evidencias/osv-backend-antes.md) → [`evidencias/osv-backend-despues.png`](evidencias/osv-backend-despues.png) |
| Web (`npm audit --omit=dev`) | 13 (1 crítica, 5 altas, 7 bajas) | **8** (1 alta, 7 bajas) (12/11/2026, Nicole: `npm audit fix` sin `--force` y vite 8.1.5 → 8.1.7). Corregidas: dompurify (crítica), seroval, source-map-js, undici y vite | [`evidencias/npm-audit-web-antes.txt`](evidencias/npm-audit-web-antes.txt) → [`evidencias/npm-audit-web-despues.txt`](evidencias/npm-audit-web-despues.txt). Riesgo aceptado: zod (alta) solo se corrige en la versión mayor 4; katex (7 bajas, vía mermaid/streamdown) no tiene corrección publicada |
| App (`npm audit --omit=dev`) | 18 (1 baja, 8 moderadas, 9 altas) | **0** (12/11/2026, Nicole: `shadcn` pasó a `devDependencies` + `npm audit fix` sin `--force`; 95/95 pruebas, build y APK probados en el teléfono) | [`evidencias/npm-audit-app-antes.txt`](evidencias/npm-audit-app-antes.txt) → [`evidencias/npm-audit-app-despues.txt`](evidencias/npm-audit-app-despues.txt); paquetes cambiados en [`evidencias/npm-app-paquetes-cambiados.txt`](evidencias/npm-app-paquetes-cambiados.txt). Riesgo aceptado: el audit completo (con herramientas de desarrollo) aún marca 18 en Vitest, Vite 5 y `shadcn`; no entran en el APK y corregirlas exige subir de versión mayor (Vitest 5, Vite 8), queda planificado |

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
| Escaneo de la API | Railway (producción) | Activo, solo inyección (SQLi y XSS) | ZAP Desktop → *Importar* 12 URL de la API → política `Inyeccion` → *Escaneo activo* sobre la búsqueda `GET /api/odontologos?q=` |

El escaneo activo contra producción se limitó a una ruta de **solo lectura** (la búsqueda pública de odontólogos) y a tres reglas: inyección SQL, inyección SQL de PostgreSQL basada en tiempo y XSS reflejado. No se atacaron formularios, registro ni pagos, para no crear datos basura en la base real.

Cabeceras de seguridad ya aplicadas en el backend (`SecurityConfig`): `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Content-Security-Policy`, `Referrer-Policy: no-referrer`, `Permissions-Policy` y HSTS. La API no usa cookies (el token va en la cabecera `Authorization`), así que la alerta "Cookie sin atributo Secure" no aplica.

Formato del reporte (se llena con los resultados de ZAP):

### Resultado 1: web en Vercel (ZAP 2.17.0, Spider + escaneo pasivo, 05/11/2026)

61 URL recorridas. **0 altas, 4 medias, 1 baja, 4 informativas.** Reporte completo: [`evidencias/zap-web-antes.html`](evidencias/zap-web-antes.html).

| Hallazgo | Gravedad | Acción de mitigación |
|---|---|---|
| Cabecera Content Security Policy (CSP) no configurada | Media | `src/server.ts` de la web agrega una CSP que solo permite la API de Railway, Google (fuentes e inicio de sesión), Mercado Pago, Culqi y los mapas de OpenStreetMap. |
| Falta de cabecera anti-clickjacking | Media | `X-Frame-Options: DENY` y `frame-ancestors 'none'` en la CSP: nadie puede incrustar la web en un iframe. |
| Configuración incorrecta cross-domain (`Access-Control-Allow-Origin: *` en archivos `/assets/*.js`) | Media | Riesgo aceptado: son los archivos JavaScript públicos de la web, que no contienen datos ni requieren sesión. La API sí restringe CORS a los dominios de la web (`CORS_ALLOWED_ORIGINS`). |
| Sub Resource Integrity ausente (hoja de estilos de Google Fonts) | Media | Riesgo aceptado con control compensatorio: Google genera ese CSS distinto según el navegador, así que no admite un hash fijo; la CSP limita los estilos a `fonts.googleapis.com`. Mejora futura: alojar las fuentes en la propia web. |
| Falta encabezado X-Content-Type-Options | Baja | `nosniff` en `src/server.ts` (páginas) y en `vercel.json` (archivos de `/assets`). |
| Cross-Origin-Embedder-Policy ausente (solo lo reporta ZAP Baseline en GitHub Actions) | Baja | Riesgo aceptado: `COEP: require-corp` bloquearía el inicio de sesión con Google, el checkout de Mercado Pago/Culqi y las imágenes de Cloudinary, que no envían la cabecera `Cross-Origin-Resource-Policy`. La CSP y `frame-ancestors 'none'` ya cubren el aislamiento que importa. |
| Comentarios sospechosos en el JavaScript (`user`, `select`, `from`) | Informativa | Falso positivo: son palabras del código minificado (por ejemplo, el campo "user" de la sesión), no consultas SQL ni datos sensibles. |
| Recuperado de la caché / directivas de caché | Informativa | Esperado: Vercel guarda en caché los archivos estáticos para que la web cargue rápido. |
| Aplicación web moderna | Informativa | Solo informa que la web es una SPA de React. |

ZAP Baseline en GitHub Actions (Seguridad #4, mismo sitio): **FAIL 0, WARN 11, PASS 57**, mismas alertas más la de COEP. Reporte en el artefacto `zap-baseline-web`.

Después de desplegar la corrección se repite el escaneo y se guarda `evidencias/zap-web-despues.html`.

### Resultado 2: API en Railway (ZAP 2.17.0, escaneo activo de inyección, 05/11/2026)

Ruta atacada: `GET https://odontosystem-production.up.railway.app/api/odontologos?q=torres`. Política `Inyeccion` (umbral Medio, 2 hilos, sin recursión). **40 peticiones de ataque, 0 alertas nuevas.** Reporte completo: [`evidencias/zap-api-inyeccion.html`](evidencias/zap-api-inyeccion.html) (incluye también las alertas pasivas de la web del Resultado 1, porque ambas pruebas se hicieron en la misma sesión de ZAP).

| Hallazgo | Gravedad | Acción de mitigación |
|---|---|---|
| Inyección SQL en el parámetro `q` | No encontrada | Control existente: `BusquedaOdontologosService` no manda `q` a la base de datos; lo normaliza y lo compara en memoria contra los perfiles ya cargados. El resto de consultas usa Spring Data JPA con parámetros enlazados (`:parametro`), nunca texto concatenado. |
| Inyección SQL ciega por tiempo (PostgreSQL) | No encontrada | Mismo control: como `q` no llega al SQL, un `pg_sleep` inyectado no se ejecuta y los tiempos de respuesta no cambiaron. |
| XSS reflejado en el parámetro `q` | No encontrada | La API responde JSON (`Content-Type: application/json`) con `X-Content-Type-Options: nosniff`, así que el navegador no interpreta la respuesta como HTML. |

Ninguna alerta de la API apareció en el reporte: los 4 hallazgos medios y el bajo corresponden solo a la web de Vercel.

### Resultado 3: web en Vercel después de las cabeceras (ZAP Baseline 2.17, Seguridad #14, 12/11/2026)

Reporte: [`evidencias/zap-web-despues.html`](evidencias/zap-web-despues.html). Resumen: 0 altas, 5 medias, 3 bajas, 5 informativas (el 05/11: 0 altas, 4 medias, 1 baja con el escaneo de ZAP Desktop).

| Hallazgo del 05/11 | Después | Qué pasó |
|---|---|---|
| CSP no configurada (Media) | **Resuelto** | `server.ts` envía la CSP en cada página. Al existir, ZAP revisa sus directivas y marca tres detalles (filas siguientes) |
| Falta anti-clickjacking (Media) | **Resuelto** | `X-Frame-Options: DENY` y `frame-ancestors 'none'` |
| Falta X-Content-Type-Options (Baja) | **Resuelto** | `server.ts` en las páginas y `vercel.json` en todas las rutas (`/(.*)`), incluidos favicon, logo y `robots.txt` |
| Cross-Domain (`Access-Control-Allow-Origin: *` en `/assets`) (Media) | Se mantiene | Riesgo aceptado: archivos públicos y estáticos de Vercel, sin datos del usuario |
| SRI ausente en Google Fonts (Media) | Se mantiene | Riesgo aceptado: Google genera el CSS según el navegador, no admite un hash fijo |
| *Nuevo:* CSP `script-src 'unsafe-inline'` (Media) | Riesgo aceptado | TanStack Start hidrata con scripts en línea. Mejora planificada: nonce por respuesta en `server.ts` |
| *Nuevo:* CSP `style-src 'unsafe-inline'` (Media) | Riesgo aceptado | Estilos en línea de Tailwind/Radix y de Leaflet |
| *Nuevo:* CSP con comodín en `img-src` (Media) | Riesgo aceptado | `img-src https:` cubre fotos de Cloudinary, Google y mosaicos de OpenStreetMap. Mejora: listar esos dominios |
| COEP / COOP / CORP (Baja) | Riesgo aceptado | COOP ya se envía como `same-origin-allow-popups` (web, commit `d422018`; login con Google probado con paciente y odontólogo). ZAP solo acepta `same-origin`, que cerraría la ventana de inicio de sesión con Google. COEP rompería Google, pagos y Cloudinary. CORP queda planificado |
| Permissions-Policy en `/assets/*.js` (Baja) | **Resuelto** | `vercel.json` la envía en todas las rutas |

## 4. Pruebas del plan de auditoría (backend)

Pruebas automáticas que corren en cada push (*Backend - pruebas*); las de CORS también en el job *Control de acceso* del workflow de seguridad.

| ID | Prueba | Resultado esperado | Dónde se prueba | Estado |
|---|---|---|---|---|
| B4 | IDOR: un usuario pide la cita, reembolso, reseña o notificación de otro | 404 (no revela que existe) | `CitaServiceTest`, `ReembolsoServiceTest`, `ResenaServiceTest`, `BandejaNotificacionesTest`, entre otras | Cumple |
| B5 | JWT alterado, firmado con otra clave, vencido o de otro usuario | Rechazado | `JwtServiceTest` | Cumple |
| B9 | Registro con `rol: "ADMIN"`, JSON mal formado o campos fuera de rango | 400 sin detalles internos | `BaseDeDatosIntegracionTest` (app real) | **Corregido**: antes respondía 500; se agregó el manejo de `HttpMessageNotReadableException` en `GlobalExceptionHandler` |
| B10 | Archivo disfrazado (.html/.svg renombrado a .jpg) o de más de 5 MB | Rechazado | `ArchivoServiceTest` (valida el contenido real, no la extensión) | Cumple |
| B11 | Fuerza bruta: 5 fallos por cuenta y 20 por IP | Bloqueo de 15 min (429) | `LimiteLoginServiceTest` | Cumple |
| B11 | ¿Se puede falsear la IP con `X-Forwarded-For`? | La IP guardada es la real | `PruebasEntradaYCorsTest` (Spring toma el primer valor) + prueba en producción del 12/11/2026: login con `X-Forwarded-For: 203.0.113.7` → `tokens_refresco.ip_origen` guardó la IP real del cliente | **Mitigado por la plataforma**: la entrada de Railway reemplaza la cabecera que manda el cliente. Si se cambia de hosting, volver a probarlo |
| B12 | Aviso de pago (webhook) falso o de una orden ajena | No confirma nada | `PagoServiceMercadoPagoTest` (el aviso se verifica consultando a Mercado Pago) | Cumple |
| I1 | CORS desde `http://evil.com` o con una cabecera no permitida | 403 sin `Access-Control-Allow-Origin` | `PruebasEntradaYCorsTest` | Cumple |
| I3 | Reusar un refresh token ya usado | 401 y se cierran todas las sesiones del usuario | `TokenRefrescoServiceTest` | Cumple |
| I2 | Access token después de cerrar sesión | — | Revisión de código (`JwtService.isTokenValid`) | **Riesgo aceptado**: el access token sigue válido hasta su vencimiento (15 min); el refresh sí se revoca |
