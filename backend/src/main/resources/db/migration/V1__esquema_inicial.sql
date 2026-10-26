-- ============================================================================
-- OdontoSystem — Marketplace Dental Admin-less (Ica, Perú)
-- DDL DE PRODUCCIÓN v9.0 — PostgreSQL 16 — TODO EN ESPAÑOL
--
-- v7.0 FUSIONA:
--   - Script original del equipo ("v6.0"): cifrado AES-256 de historias
--     clínicas, bitácora de auditoría inmutable, chatbot/PLN, gestión
--     documental del odontólogo, notificaciones push, metodo_pago separado
--     de pasarela_pago.
--   - Corrección del compañero ("v5.1/v6.0" mal rotulada): autenticación
--     híbrida reforzada (password_hash + CHECK BCrypt, google_oauth_sub,
--     OTP con tabla propia, sesiones JWT persistentes), historial de
--     verificación de colegiatura (verificaciones_cop), tabla de reclamos,
--     estado REPROGRAMADA para citas, reputación de paciente granular.
--
-- v9.0 TOMA v8.0 COMO BASE Y CORRIGE SEGURIDAD, INTEGRIDAD, PAGOS, AUDITORÍA Y SUPABASE:
--   - Mantiene RF/RNF y funcionalidades de v8.0, pero elimina el RLS global automático sobre public.
--   - Agrega intentos de pago, eventos webhook idempotentes y reembolsos como entidades separadas.
--   - El estado VERIFICADO de un pago exige evidencia de webhook firmado procesado y el monto queda limitado al precio histórico de la cita.
--   - Congela en cada cita el precio, depósito, duración y zona horaria del servicio/odontólogo.
--   - Refuerza integridad de roles: solo PACIENTE puede ser paciente y solo ODONTOLOGO puede tener perfil odontológico.
--   - Impide cambios de rol posteriores a la creación de la cuenta.
--   - Registra automáticamente los cambios de estado de citas en la bitácora inmutable.
--   - Mejora la verificación de la cadena SHA-256 comprobando también el enlace inmediato entre registros.
--   - Valida en BD que una cita activa no caiga dentro de un bloqueo de agenda.
--   - Endurece eliminación de información histórica mediante RESTRICT/soft-delete donde corresponde.
--   - Añade idempotencia y trazabilidad para System Jobs y webhooks.
--   - Mantiene PostGIS, btree_gist, autenticación híbrida, OTP, JWT, COP, documentos, chat,
--     notificaciones, recordatorios, reputación, reprogramación, reseñas y vistas analíticas.
--
-- ÚNICOS ACTORES: Paciente y Odontólogo. Sin rol administrador humano.
-- Uso: ejecutar en una base de datos nueva (Supabase SQL Editor). Spring Boot actúa como gateway de negocio; RLS no se activa globalmente.
-- ============================================================================


-- ----------------------------------------------------------------------------
-- 0. EXTENSIONES
-- ----------------------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
-- btree_gist: habilita GiST para tipos "normales" (uuid, enum) combinados con
-- un rango (tsrange/tstzrange) dentro de un EXCLUDE. Sin esto, los anti-solape
-- de horarios_atencion y citas no compilan.
CREATE EXTENSION IF NOT EXISTS "btree_gist";
-- PostGIS: columna espacial ubicacion_geografica + cercanía (ST_DWithin/KNN).
CREATE EXTENSION IF NOT EXISTS "postgis";

-- ----------------------------------------------------------------------------
-- 1. TIPOS ENUMERADOS
-- ----------------------------------------------------------------------------
CREATE TYPE rol_usuario AS ENUM ('PACIENTE', 'ODONTOLOGO');

CREATE TYPE estado_cita AS ENUM (
    'PENDIENTE_PAGO',
    'PENDIENTE_CONFIRMACION',      -- v8 (RF11): esperando aprobación manual del odontólogo; NO bloquea la agenda
    'CONFIRMADA',
    'COMPLETADA',
    'CANCELADA_CON_REEMBOLSO',
    'CANCELADA_SIN_REEMBOLSO',
    'INASISTENCIA_PENALIZADA',
    'REPROGRAMADA'                 -- v7: incorporado del script del compañero
);

-- metodo_pago = instrumento que elige el paciente.
-- pasarela_pago = procesador técnico (solo aplica a pagos con TARJETA).
-- v7: se mantiene SEPARADO (decisión explícita del equipo, no se unifica).
CREATE TYPE metodo_pago   AS ENUM ('YAPE', 'PLIN', 'TARJETA');
CREATE TYPE pasarela_pago AS ENUM ('CULQI', 'NIUBIZ');

CREATE TYPE estado_pago        AS ENUM ('PENDIENTE', 'VERIFICADO', 'RECHAZADO', 'REEMBOLSADO');
CREATE TYPE estado_intento_pago AS ENUM ('CREADO', 'PENDIENTE', 'APROBADO', 'RECHAZADO', 'EXPIRADO', 'CANCELADO');
CREATE TYPE estado_webhook_pago AS ENUM ('RECIBIDO', 'PROCESANDO', 'PROCESADO', 'IGNORADO', 'ERROR');
CREATE TYPE estado_reembolso    AS ENUM ('SOLICITADO', 'PROCESANDO', 'REEMBOLSADO', 'RECHAZADO', 'ERROR');
CREATE TYPE estado_liquidacion AS ENUM ('PENDIENTE', 'LIQUIDADA', 'CANCELADA');
CREATE TYPE estado_apelacion   AS ENUM ('PENDIENTE', 'APROBADA', 'RECHAZADA');

-- v7: reputación granular (del compañero) en vez de ACTIVA/RESTRINGIDA
CREATE TYPE estado_reputacion AS ENUM ('EXCELENTE', 'REGULAR', 'BLOQUEADO');

CREATE TYPE dia_semana AS ENUM (
    'LUNES', 'MARTES', 'MIERCOLES', 'JUEVES',
    'VIERNES', 'SABADO', 'DOMINGO'
);

-- v7: canal ampliado con EMAIL y PUSH para cubrir OTP por correo y
-- notificaciones push (dispositivos_usuario) dentro del mismo catálogo.
CREATE TYPE canal_notificacion  AS ENUM ('WHATSAPP', 'SMS', 'EMAIL', 'PUSH');
CREATE TYPE tipo_notificacion   AS ENUM (
    'OTP', 'RECORDATORIO_CITA', 'CONFIRMACION_PAGO',
    'AVISO_CANCELACION', 'AVISO_REPROGRAMACION', 'ALERTA_STRIKE'
);
CREATE TYPE estado_notificacion AS ENUM ('PENDIENTE', 'ENVIADA', 'ENTREGADA', 'FALLIDA');

CREATE TYPE canal_chat          AS ENUM ('WEB', 'MOVIL');
CREATE TYPE estado_chat         AS ENUM ('ACTIVA', 'CERRADA');
CREATE TYPE tipo_emisor_mensaje AS ENUM ('USUARIO', 'BOT', 'AGENTE');

CREATE TYPE estado_verificacion_odontologo AS ENUM ('PENDIENTE', 'OBSERVADO', 'VERIFICADO', 'RECHAZADO');
CREATE TYPE tipo_documento_odontologo      AS ENUM ('DNI', 'TITULO_PROFESIONAL', 'COLEGIATURA', 'CV');

-- v7: catálogo de resultado por CADA corrida del job de verificación COP
-- (tabla verificaciones_cop), distinto del estado "actual" cacheado en
-- perfiles_odontologo.estado_verificacion.
CREATE TYPE resultado_verificacion_cop AS ENUM ('PENDIENTE', 'VERIFICADO', 'RECHAZADO', 'ERROR');

-- v7: método con el que se creó/autenticó la cuenta (usuarios.metodo_registro
-- y sesiones_usuario.metodo)
CREATE TYPE metodo_autenticacion AS ENUM ('PASSWORD', 'OTP', 'GOOGLE_OAUTH');

-- v8 (RF06): cada cuánto el odontólogo se compromete a actualizar su disponibilidad
CREATE TYPE frecuencia_disponibilidad AS ENUM ('DIARIA', 'SEMANAL');

-- v7: soporte/reclamos, incorporado del script del compañero
CREATE TYPE tipo_reclamo    AS ENUM ('CONSULTA', 'INCIDENCIA', 'RECLAMO');
CREATE TYPE estado_reclamo  AS ENUM ('ABIERTO', 'EN_PROCESO', 'RESUELTO', 'CERRADO');

-- ----------------------------------------------------------------------------
-- 2. FUNCIONES GENÉRICAS
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fijar_actualizado_en()
RETURNS TRIGGER AS $$
BEGIN
    NEW.actualizado_en = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION evitar_mutacion_bitacora()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION
        'bitacora_auditoria_citas es de SOLO INSERCIÓN: % no está permitido (Ley N.º 29733 / trazabilidad).',
        TG_OP;
END;
$$ LANGUAGE plpgsql;

-- ============================================================================
-- Integridad de roles y referencias de negocio — v9
-- ============================================================================
CREATE OR REPLACE FUNCTION validar_rol_odontologo()
RETURNS TRIGGER AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM usuarios u
        WHERE u.id = NEW.usuario_id AND u.rol = 'ODONTOLOGO'
    ) THEN
        RAISE EXCEPTION 'El usuario % debe tener rol ODONTOLOGO para poseer un perfil odontológico.', NEW.usuario_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION validar_rol_paciente_cita()
RETURNS TRIGGER AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM usuarios u
        WHERE u.id = NEW.paciente_id AND u.rol = 'PACIENTE'
    ) THEN
        RAISE EXCEPTION 'El usuario % debe tener rol PACIENTE para registrar una cita como paciente.', NEW.paciente_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION impedir_cambio_rol_usuario()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.rol IS DISTINCT FROM OLD.rol THEN
        RAISE EXCEPTION 'El rol de un usuario es inmutable. Cree una cuenta nueva si se necesita otro rol.';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- v8: lee TOLERANCIA_MINUTOS_TARDANZA de configuracion_sistema (antes: 15 minutos fijos).
CREATE OR REPLACE FUNCTION fijar_vencimiento_tolerancia()
RETURNS TRIGGER AS $$
DECLARE
    v_min INTEGER;
BEGIN
    IF NEW.tolerancia_vence_en IS NULL THEN
        SELECT valor_parametro::INTEGER INTO v_min
          FROM configuracion_sistema
         WHERE clave_parametro = 'TOLERANCIA_MINUTOS_TARDANZA';
        NEW.tolerancia_vence_en := NEW.inicio_programado + make_interval(mins => COALESCE(v_min, 15));
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION actualizar_cache_calificacion_odontologo()
RETURNS TRIGGER AS $$
DECLARE
    v_odontologo_id UUID := COALESCE(NEW.odontologo_id, OLD.odontologo_id);
BEGIN
    UPDATE perfiles_odontologo po
       SET calificacion_promedio = COALESCE((SELECT ROUND(AVG(r.calificacion)::numeric, 2) FROM resenas r WHERE r.odontologo_id = v_odontologo_id), 0.00),
           total_resenas         = (SELECT COUNT(*) FROM resenas r WHERE r.odontologo_id = v_odontologo_id)
     WHERE po.usuario_id = v_odontologo_id;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION sincronizar_ubicacion_geografica()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.latitud_consultorio IS NOT NULL AND NEW.longitud_consultorio IS NOT NULL THEN
        NEW.ubicacion_geografica := ST_SetSRID(
            ST_MakePoint(NEW.longitud_consultorio::double precision, NEW.latitud_consultorio::double precision),
            4326
        );
    ELSE
        NEW.ubicacion_geografica := NULL;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- v7: cálculo automático del depósito de garantía (20% configurable),
-- incorporado del script del compañero — evita que la app deba calcularlo.
CREATE OR REPLACE FUNCTION fn_calcular_monto_deposito()
RETURNS TRIGGER AS $$
DECLARE
    v_pct NUMERIC;
BEGIN
    SELECT valor_parametro::NUMERIC INTO v_pct
      FROM configuracion_sistema
     WHERE clave_parametro = 'PORCENTAJE_DEPOSITO_GARANTIA';
    NEW.monto_deposito := ROUND(NEW.precio_total * COALESCE(v_pct, 20) / 100, 2);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ============================================================================
-- 3. MÓDULO DE USUARIOS Y AUTENTICACIÓN HÍBRIDA
-- ============================================================================
-- v7: renombrado contrasena_hash -> password_hash e id_oauth_google ->
-- google_oauth_sub (alineado a la terminología del informe). Se agregan
-- metodo_registro, email_verificado_en y ultimo_acceso_en del script del
-- compañero. correo pasa a NOT NULL UNIQUE (login híbrido lo requiere).

CREATE TABLE usuarios (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    numero_documento       VARCHAR(20)  NOT NULL,
    tipo_documento         VARCHAR(5)   NOT NULL DEFAULT 'DNI',
    nombre_completo        VARCHAR(150) NOT NULL,
    telefono               VARCHAR(20)  NOT NULL,
    correo                 VARCHAR(150) NOT NULL,
    rol                    rol_usuario  NOT NULL,
    password_hash          VARCHAR(255),          -- Hash BCrypt (60 car.). NULL si la cuenta solo entra por Google.
    google_oauth_sub       VARCHAR(255),           -- Claim "sub" del token OIDC de Google. UNIQUE: una cuenta Google = un usuario.
    metodo_registro        metodo_autenticacion NOT NULL DEFAULT 'PASSWORD',
    foto_perfil_url        TEXT,
    biometria_habilitada   BOOLEAN      NOT NULL DEFAULT FALSE,
    telefono_verificado_en TIMESTAMPTZ,
    email_verificado_en    TIMESTAMPTZ,
    ultimo_acceso_en       TIMESTAMPTZ,
    esta_activo            BOOLEAN      NOT NULL DEFAULT TRUE,
    creado_en              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    actualizado_en         TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_usuarios_numero_documento UNIQUE (numero_documento),
    CONSTRAINT uq_usuarios_telefono         UNIQUE (telefono),
    CONSTRAINT uq_usuarios_correo           UNIQUE (correo),
    CONSTRAINT uq_usuarios_google_oauth_sub UNIQUE (google_oauth_sub),
    CONSTRAINT ck_usuarios_tipo_documento   CHECK (tipo_documento IN ('DNI', 'CE')),
    -- Formato BCrypt válido: $2a$/$2b$/$2y$ + costo de 2 dígitos + 53 caracteres (60 en total)
    CONSTRAINT ck_usuarios_password_bcrypt CHECK (
        password_hash IS NULL OR password_hash ~ '^\$2[aby]\$[0-9]{2}\$[./A-Za-z0-9]{53}$'
    ),
    -- v8: toda cuenta debe tener contraseña o vínculo Google. El OTP verifica teléfono/correo o actúa
    -- como segundo factor; NO es credencial única (en v7 este CHECK era siempre verdadero porque
    -- telefono es NOT NULL).
    CONSTRAINT ck_usuarios_medio_acceso CHECK (
        password_hash IS NOT NULL OR google_oauth_sub IS NOT NULL
    )
);

COMMENT ON COLUMN usuarios.password_hash IS
    'Hash BCrypt de la contraseña. NULL solo para cuentas que entran únicamente con Google OAuth2 (RF16). Nunca se guarda la contraseña en claro.';
COMMENT ON COLUMN usuarios.google_oauth_sub IS
    'Identificador estable (claim "sub") del token OIDC de Google.';
COMMENT ON COLUMN usuarios.foto_perfil_url IS
    'URL de la foto de perfil, válida tanto para pacientes como para odontólogos.';

CREATE TRIGGER trg_usuarios_actualizado_en
    BEFORE UPDATE ON usuarios
    FOR EACH ROW EXECUTE FUNCTION fijar_actualizado_en();

CREATE TRIGGER trg_usuarios_rol_inmutable
    BEFORE UPDATE OF rol ON usuarios
    FOR EACH ROW EXECUTE FUNCTION impedir_cambio_rol_usuario();

CREATE INDEX idx_usuarios_rol ON usuarios (rol);

-- ----------------------------------------------------------------------------
-- Sesiones persistentes (JWT + refresh token) — v7, del script del compañero
-- ----------------------------------------------------------------------------
-- Cada login (password, OTP o Google) emite un JWT de acceso de vida corta
-- (sin estado) y un refresh token opaco. Solo se guarda el HASH SHA-256 del
-- refresh token, nunca el token en claro, para poder rotarlo y revocarlo.
CREATE TABLE sesiones_usuario (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    usuario_id          UUID NOT NULL REFERENCES usuarios (id) ON DELETE CASCADE,
    metodo              metodo_autenticacion NOT NULL,
    refresh_token_hash  CHAR(64) NOT NULL UNIQUE,   -- SHA-256 en hexadecimal
    jti_ultimo_jwt      VARCHAR(64),                -- claim "jti" del último JWT, para listas de revocación
    dispositivo         VARCHAR(150),
    ip_origen           INET,
    creada_en           TIMESTAMPTZ NOT NULL DEFAULT now(),
    ultimo_uso_en       TIMESTAMPTZ,
    expira_en           TIMESTAMPTZ NOT NULL,
    revocada_en         TIMESTAMPTZ,

    CONSTRAINT ck_sesiones_expiracion CHECK (expira_en > creada_en)
);

COMMENT ON TABLE sesiones_usuario IS
    'Sesiones persistentes (refresh tokens hasheados). Un System Job purga las expiradas/revocadas.';

CREATE INDEX idx_sesiones_usuario_activas ON sesiones_usuario (usuario_id) WHERE revocada_en IS NULL;
CREATE INDEX idx_sesiones_expiracion      ON sesiones_usuario (expira_en);

-- ----------------------------------------------------------------------------
-- OTP (WhatsApp o correo) — v7, del script del compañero
-- ----------------------------------------------------------------------------
-- La validación en caliente ocurre en Redis (TTL corto); esta tabla conserva
-- solo el hash del código para trazabilidad y límite de intentos.
CREATE TABLE otp_verificaciones (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    usuario_id   UUID REFERENCES usuarios (id) ON DELETE CASCADE,  -- NULL mientras el usuario aún no existe (registro)
    destino      VARCHAR(120) NOT NULL,        -- teléfono o correo al que se envió el código
    canal        canal_notificacion NOT NULL,
    codigo_hash  CHAR(64) NOT NULL,             -- SHA-256 del OTP; nunca el código en claro
    intentos     SMALLINT NOT NULL DEFAULT 0,
    creado_en    TIMESTAMPTZ NOT NULL DEFAULT now(),
    expira_en    TIMESTAMPTZ NOT NULL,
    consumido_en TIMESTAMPTZ,

    CONSTRAINT ck_otp_canal        CHECK (canal IN ('WHATSAPP', 'EMAIL')),
    CONSTRAINT ck_otp_intentos     CHECK (intentos BETWEEN 0 AND 5),
    CONSTRAINT ck_otp_expiracion   CHECK (expira_en > creado_en)
);

CREATE INDEX idx_otp_destino_vigente ON otp_verificaciones (destino, creado_en DESC) WHERE consumido_en IS NULL;

-- ----------------------------------------------------------------------------
-- Consentimiento de privacidad (Ley N.º 29733 / RNF07) — v8
-- ----------------------------------------------------------------------------
-- Evidencia del consentimiento explícito del titular. RESTRICT: es evidencia legal,
-- no se borra en cascada.
CREATE TABLE consentimientos_privacidad (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    usuario_id       UUID        NOT NULL REFERENCES usuarios (id) ON DELETE RESTRICT,
    version_politica VARCHAR(20) NOT NULL,          -- ej. '2026-08-v1'
    aceptado_en      TIMESTAMPTZ NOT NULL DEFAULT now(),
    ip_origen        INET,

    CONSTRAINT uq_consentimiento_usuario_version UNIQUE (usuario_id, version_politica)
);

COMMENT ON TABLE consentimientos_privacidad IS
    'RNF07 / Ley N.º 29733: registro del consentimiento explícito de privacidad (versión de la política + fecha + IP).';

-- ============================================================================
-- 4. PERFILES DE ODONTÓLOGO Y VERIFICACIÓN COP
-- ============================================================================

CREATE TABLE perfiles_odontologo (
    usuario_id                 UUID PRIMARY KEY
                               REFERENCES usuarios (id) ON DELETE RESTRICT,
    numero_colegiatura         VARCHAR(30) NOT NULL,
    colegiatura_verificada     BOOLEAN     NOT NULL DEFAULT FALSE,
    colegiatura_verificada_en  TIMESTAMPTZ,
    ruc                        VARCHAR(11),
    cci                        VARCHAR(20),
    nombre_consultorio         VARCHAR(150),
    direccion_consultorio      VARCHAR(255),
    distrito_consultorio       VARCHAR(100),
    zona_horaria                VARCHAR(50) NOT NULL DEFAULT 'America/Lima',
    latitud_consultorio        NUMERIC(9, 6),
    longitud_consultorio       NUMERIC(9, 6),
    ubicacion_geografica       GEOMETRY(Point, 4326),
    token_google_calendar_cifrado BYTEA,        -- reservado (extensibilidad futura); Google OAuth es solo para login (RF16 no sincroniza calendarios externos)
    calificacion_promedio      NUMERIC(3, 2) NOT NULL DEFAULT 0.00,
    total_resenas              INTEGER NOT NULL DEFAULT 0,
    requiere_confirmacion_manual  BOOLEAN NOT NULL DEFAULT FALSE,                         -- v8 (RF11)
    frecuencia_disponibilidad     frecuencia_disponibilidad NOT NULL DEFAULT 'SEMANAL',   -- v8 (RF06)
    disponibilidad_actualizada_en TIMESTAMPTZ,                                            -- v8 (RF06)
    desactivado_en             TIMESTAMPTZ,
    creado_en                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    actualizado_en             TIMESTAMPTZ NOT NULL DEFAULT now(),
    estado_verificacion        estado_verificacion_odontologo NOT NULL DEFAULT 'PENDIENTE',
    verificado_en              TIMESTAMPTZ,
    nota_observacion           TEXT,

    CONSTRAINT uq_perfiles_odontologo_colegiatura UNIQUE (numero_colegiatura),
    CONSTRAINT ck_perfiles_odontologo_zona_horaria CHECK (btrim(zona_horaria) <> ''),
    CONSTRAINT ck_perfiles_odontologo_calificacion CHECK (calificacion_promedio BETWEEN 0 AND 5)
);

CREATE TRIGGER trg_perfiles_odontologo_validar_rol
    BEFORE INSERT OR UPDATE OF usuario_id ON perfiles_odontologo
    FOR EACH ROW EXECUTE FUNCTION validar_rol_odontologo();

CREATE TRIGGER trg_perfiles_odontologo_actualizado_en
    BEFORE UPDATE ON perfiles_odontologo
    FOR EACH ROW EXECUTE FUNCTION fijar_actualizado_en();

CREATE TRIGGER trg_perfiles_odontologo_sincronizar_ubicacion
    BEFORE INSERT OR UPDATE OF latitud_consultorio, longitud_consultorio ON perfiles_odontologo
    FOR EACH ROW EXECUTE FUNCTION sincronizar_ubicacion_geografica();

CREATE INDEX idx_perfiles_odontologo_activos ON perfiles_odontologo (usuario_id) WHERE desactivado_en IS NULL;
CREATE INDEX idx_perfiles_odontologo_estado_verificacion ON perfiles_odontologo (estado_verificacion);

COMMENT ON COLUMN perfiles_odontologo.total_resenas IS
    'Caché denormalizado, recalculado por los triggers trg_resenas_actualizar_calificacion_*. Fuente de verdad: tabla resenas.';
COMMENT ON COLUMN perfiles_odontologo.desactivado_en IS
    'Baja lógica (soft delete). NULL = activo.';
COMMENT ON COLUMN perfiles_odontologo.distrito_consultorio IS
    'Distrito del consultorio dentro de la provincia de Ica (ej. "Ica Centro", "Parcona", "La Tinguiña", "Subtanjalla"). Texto libre: no hay admin que mantenga un catálogo de distritos. Permite un filtrado rápido por jurisdicción antes de ejecutar funciones espaciales radiales en PostGIS.';
COMMENT ON COLUMN perfiles_odontologo.ubicacion_geografica IS
    'Columna espacial PostGIS (SRID 4326 = WGS84). Se recalcula automáticamente vía trigger; la aplicación nunca la escribe directamente.';
COMMENT ON COLUMN perfiles_odontologo.estado_verificacion IS
    'Estado ACTUAL (cacheado) del job de verificación de colegiatura. El historial de cada corrida vive en verificaciones_cop.';
COMMENT ON COLUMN perfiles_odontologo.nota_observacion IS
    'Motivo generado automáticamente por el job de verificación cuando estado_verificacion = OBSERVADO o RECHAZADO.';
COMMENT ON COLUMN perfiles_odontologo.token_google_calendar_cifrado IS
    'Columna reservada para extensibilidad futura. RF16: la agenda es 100% nativa en PostgreSQL (btree_gist); Google OAuth solo se usa para inicio de sesión.';

COMMENT ON COLUMN perfiles_odontologo.requiere_confirmacion_manual IS
    'RF11: si es TRUE, las reservas nuevas entran como PENDIENTE_CONFIRMACION (no bloquean la agenda) hasta que el odontólogo las apruebe; si es FALSE pasan directo a CONFIRMADA tras el pago.';
COMMENT ON COLUMN perfiles_odontologo.frecuencia_disponibilidad IS
    'RF06: frecuencia (DIARIA/SEMANAL) con la que el odontólogo se compromete a actualizar su disponibilidad. Un System Job compara con disponibilidad_actualizada_en para avisar/expirar disponibilidad desactualizada.';
COMMENT ON COLUMN perfiles_odontologo.zona_horaria IS
    'Zona horaria IANA usada para presentar horarios y ejecutar reglas de agenda. Por defecto America/Lima.';

COMMENT ON COLUMN perfiles_odontologo.disponibilidad_actualizada_en IS
    'Última modificación de horarios_atencion del odontólogo (la mantiene el trigger trg_horarios_atencion_marca_actualizacion).';

-- v7: historial append-only de cada corrida del job JobVerificacionCOP,
-- del script del compañero. Complementa el estado cacheado de arriba.
CREATE TABLE verificaciones_cop (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    odontologo_id  UUID NOT NULL REFERENCES perfiles_odontologo (usuario_id) ON DELETE RESTRICT,
    numero_cop     VARCHAR(30) NOT NULL,
    resultado      resultado_verificacion_cop NOT NULL,
    detalle        TEXT,                    -- mensaje del job (causa de RECHAZADO/ERROR, para permitir reintento)
    ejecutado_en   TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE verificaciones_cop IS
    'Historial de intentos del job autónomo JobVerificacionCOP. Permite reintentos al odontólogo cuando resultado = ERROR.';

CREATE INDEX idx_verificaciones_cop_odontologo ON verificaciones_cop (odontologo_id, ejecutado_en DESC);

CREATE TABLE documentos_odontologo (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    odontologo_id  UUID NOT NULL REFERENCES perfiles_odontologo (usuario_id) ON DELETE RESTRICT,
    tipo_documento tipo_documento_odontologo NOT NULL,
    url_archivo    TEXT NOT NULL,
    subido_en      TIMESTAMPTZ NOT NULL DEFAULT now(),
    reemplazado_en TIMESTAMPTZ
);

CREATE INDEX idx_documentos_odontologo_odontologo_id ON documentos_odontologo (odontologo_id);

CREATE UNIQUE INDEX idx_documentos_odontologo_activo_unico
    ON documentos_odontologo (odontologo_id, tipo_documento)
    WHERE reemplazado_en IS NULL;

COMMENT ON TABLE documentos_odontologo IS
    'Historial de documentos (DNI, título, colegiatura, CV). RF08 permite re-subir un documento cuando el perfil queda "Observado", sin reiniciar todo el trámite.';

CREATE TABLE fotos_consultorio (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    odontologo_id UUID NOT NULL REFERENCES perfiles_odontologo (usuario_id) ON DELETE RESTRICT,
    url_foto      TEXT NOT NULL,
    descripcion   VARCHAR(255),
    orden         SMALLINT NOT NULL DEFAULT 0,
    creado_en     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_fotos_consultorio_orden_no_negativo CHECK (orden >= 0),
    -- v8 (restaurado de v5.0): permite reordenar la galería dentro de una transacción
    CONSTRAINT uq_fotos_consultorio_orden UNIQUE (odontologo_id, orden) DEFERRABLE INITIALLY DEFERRED
);

-- ----------------------------------------------------------------------------
-- Reputación del paciente — v7: estados granulares del compañero
-- ----------------------------------------------------------------------------
CREATE TABLE reputacion_paciente (
    usuario_id        UUID PRIMARY KEY REFERENCES usuarios (id) ON DELETE RESTRICT,
    cantidad_strikes  SMALLINT           NOT NULL DEFAULT 0,
    estado            estado_reputacion  NOT NULL DEFAULT 'EXCELENTE',
    restringido_hasta TIMESTAMPTZ,
    actualizado_en    TIMESTAMPTZ        NOT NULL DEFAULT now(),

    CONSTRAINT ck_reputacion_paciente_strikes CHECK (cantidad_strikes BETWEEN 0 AND 3)
);

CREATE TRIGGER trg_reputacion_paciente_validar_rol
    BEFORE INSERT OR UPDATE OF usuario_id ON reputacion_paciente
    FOR EACH ROW EXECUTE FUNCTION validar_rol_paciente_cita();

CREATE TRIGGER trg_reputacion_paciente_actualizado_en
    BEFORE UPDATE ON reputacion_paciente
    FOR EACH ROW EXECUTE FUNCTION fijar_actualizado_en();

COMMENT ON TABLE reputacion_paciente IS
    'EXCELENTE (0 strikes) -> REGULAR (1-2 strikes) -> BLOQUEADO (3 strikes, LIMITE_MAXIMO_STRIKES). El System Job de No-Show actualiza este registro.';

-- ----------------------------------------------------------------------------
-- Especialidades del odontólogo (título profesional) — RF02, RF12
-- ----------------------------------------------------------------------------
CREATE TABLE especialidades (
    id     SMALLINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nombre VARCHAR(100) NOT NULL,

    CONSTRAINT uq_especialidades_nombre UNIQUE (nombre)
);

INSERT INTO especialidades (nombre) VALUES
    ('Odontología General'), ('Ortodoncia'), ('Endodoncia'), ('Periodoncia'),
    ('Cirugía Oral y Maxilofacial'), ('Odontopediatría'), ('Prótesis Dental'),
    ('Estética Dental')
ON CONFLICT (nombre) DO NOTHING;

CREATE TABLE especialidades_odontologo (
    odontologo_id   UUID     NOT NULL REFERENCES perfiles_odontologo (usuario_id) ON DELETE CASCADE,
    especialidad_id SMALLINT NOT NULL REFERENCES especialidades (id) ON DELETE RESTRICT,

    PRIMARY KEY (odontologo_id, especialidad_id)
);

CREATE INDEX idx_especialidades_odontologo_especialidad_id ON especialidades_odontologo (especialidad_id);

-- ----------------------------------------------------------------------------
-- Categorías de servicio (tipo de tratamiento) — distinto de "especialidad"
-- ----------------------------------------------------------------------------
CREATE TABLE categorias_servicio (
    id          SMALLINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nombre      VARCHAR(80) NOT NULL,
    esta_activo BOOLEAN NOT NULL DEFAULT TRUE,

    CONSTRAINT uq_categorias_servicio_nombre UNIQUE (nombre)
);

INSERT INTO categorias_servicio (nombre) VALUES
    ('Consulta y diagnóstico'), ('Limpieza y prevención'), ('Restauraciones y empastes'),
    ('Endodoncia'), ('Ortodoncia'), ('Estética dental'), ('Cirugía y extracciones'),
    ('Implantes y prótesis'), ('Odontopediatría'), ('Otros')
ON CONFLICT (nombre) DO NOTHING;

-- ----------------------------------------------------------------------------
-- Notificaciones (WhatsApp / SMS / correo / push)
-- ----------------------------------------------------------------------------
CREATE TABLE registro_notificaciones (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    usuario_id UUID NOT NULL REFERENCES usuarios (id) ON DELETE CASCADE,
    tipo       tipo_notificacion   NOT NULL,
    canal      canal_notificacion  NOT NULL,
    estado     estado_notificacion NOT NULL DEFAULT 'PENDIENTE',
    enviado_en TIMESTAMPTZ,
    creado_en  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_registro_notificaciones_usuario_creado ON registro_notificaciones (usuario_id, creado_en);

-- ============================================================================
-- 5. MÓDULO DE APP MÓVIL / NOTIFICACIONES PUSH
-- ============================================================================
CREATE TABLE dispositivos_usuario (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    usuario_id          UUID NOT NULL REFERENCES usuarios (id) ON DELETE CASCADE,
    token_fcm           TEXT NOT NULL,
    sistema_operativo   VARCHAR(20),
    ultima_actividad_en TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_dispositivos_usuario_usuario_token UNIQUE (usuario_id, token_fcm)
);

CREATE INDEX idx_dispositivos_usuario_usuario_id ON dispositivos_usuario (usuario_id);

-- ============================================================================
-- 6. MÓDULO DE CHATBOT E IA (SOPORTE CONVERSACIONAL)
-- ============================================================================
CREATE TABLE sesiones_chat (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    usuario_id     UUID REFERENCES usuarios (id) ON DELETE SET NULL,
    canal          canal_chat  NOT NULL,
    estado         estado_chat NOT NULL DEFAULT 'ACTIVA',
    creado_en      TIMESTAMPTZ NOT NULL DEFAULT now(),
    actualizado_en TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TRIGGER trg_sesiones_chat_actualizado_en
    BEFORE UPDATE ON sesiones_chat
    FOR EACH ROW EXECUTE FUNCTION fijar_actualizado_en();

CREATE INDEX idx_sesiones_chat_usuario_id ON sesiones_chat (usuario_id);
CREATE INDEX idx_sesiones_chat_estado     ON sesiones_chat (estado);

CREATE TABLE mensajes_chat (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sesion_id           UUID NOT NULL REFERENCES sesiones_chat (id) ON DELETE CASCADE,
    tipo_emisor         tipo_emisor_mensaje NOT NULL,
    texto_mensaje       TEXT NOT NULL,
    intencion_detectada VARCHAR(100),
    creado_en           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_mensajes_chat_sesion_creado ON mensajes_chat (sesion_id, creado_en);
CREATE INDEX idx_mensajes_chat_intencion ON mensajes_chat (intencion_detectada) WHERE intencion_detectada IS NOT NULL;

-- ============================================================================
-- 7. MÓDULO DE SERVICIOS Y DISPONIBILIDAD
-- ============================================================================
CREATE TABLE servicios (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    odontologo_id     UUID NOT NULL REFERENCES perfiles_odontologo (usuario_id) ON DELETE RESTRICT,
    categoria_id      SMALLINT NOT NULL REFERENCES categorias_servicio (id) ON DELETE RESTRICT,
    titulo            VARCHAR(150) NOT NULL,
    descripcion       TEXT,
    precio_total      NUMERIC(10, 2) NOT NULL,
    monto_deposito    NUMERIC(10, 2) NOT NULL DEFAULT 20.00,  -- recalculado por trigger según config. del sistema
    duracion_minutos  SMALLINT NOT NULL DEFAULT 30,
    esta_activo       BOOLEAN NOT NULL DEFAULT TRUE,
    creado_en         TIMESTAMPTZ NOT NULL DEFAULT now(),
    actualizado_en    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_servicios_precio_positivo    CHECK (precio_total >= 0),
    CONSTRAINT ck_servicios_deposito_positivo  CHECK (monto_deposito >= 0),
    CONSTRAINT ck_servicios_deposito_no_supera_precio CHECK (monto_deposito <= precio_total),
    CONSTRAINT ck_servicios_duracion_positiva  CHECK (duracion_minutos > 0),
    CONSTRAINT uq_servicios_id_odontologo UNIQUE (id, odontologo_id)
);

CREATE TRIGGER trg_servicios_actualizado_en
    BEFORE UPDATE ON servicios
    FOR EACH ROW EXECUTE FUNCTION fijar_actualizado_en();

-- v7: cálculo automático del 20% de garantía, del script del compañero
CREATE TRIGGER trg_servicios_calcular_deposito
    BEFORE INSERT OR UPDATE OF precio_total ON servicios
    FOR EACH ROW EXECUTE FUNCTION fn_calcular_monto_deposito();

CREATE INDEX idx_servicios_odontologo_id ON servicios (odontologo_id);
CREATE INDEX idx_servicios_categoria_id  ON servicios (categoria_id);
CREATE INDEX idx_servicios_esta_activo   ON servicios (esta_activo) WHERE esta_activo = TRUE;

-- ----------------------------------------------------------------------------
CREATE TABLE horarios_atencion (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    odontologo_id UUID NOT NULL REFERENCES perfiles_odontologo (usuario_id) ON DELETE CASCADE,
    dia_semana    dia_semana NOT NULL,
    hora_inicio   TIME NOT NULL,
    hora_fin      TIME NOT NULL,
    intervalo_minutos SMALLINT NOT NULL DEFAULT 30,   -- v8: granularidad de los turnos (clase HorarioTrabajo.intervaloMinutos)

    CONSTRAINT ck_horarios_atencion_rango CHECK (hora_fin > hora_inicio),
    CONSTRAINT ck_horarios_atencion_intervalo CHECK (intervalo_minutos > 0),
    CONSTRAINT ex_horarios_atencion_sin_solape EXCLUDE USING gist (
        odontologo_id WITH =,
        dia_semana WITH =,
        tsrange(DATE '2000-01-01' + hora_inicio, DATE '2000-01-01' + hora_fin, '[)') WITH &&
    )
);

CREATE INDEX idx_horarios_atencion_odontologo_dia ON horarios_atencion (odontologo_id, dia_semana);

-- v8 (RF06): marca cuándo el odontólogo tocó su disponibilidad por última vez.
CREATE OR REPLACE FUNCTION marcar_disponibilidad_actualizada()
RETURNS TRIGGER AS $$
DECLARE
    v_id UUID := COALESCE(NEW.odontologo_id, OLD.odontologo_id);
BEGIN
    UPDATE perfiles_odontologo SET disponibilidad_actualizada_en = now() WHERE usuario_id = v_id;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_horarios_atencion_marca_actualizacion
    AFTER INSERT OR UPDATE OR DELETE ON horarios_atencion
    FOR EACH ROW EXECUTE FUNCTION marcar_disponibilidad_actualizada();

-- ----------------------------------------------------------------------------
-- Bloqueo express de agenda (RF07)
-- ----------------------------------------------------------------------------
CREATE TABLE bloqueos_agenda (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    odontologo_id UUID NOT NULL REFERENCES perfiles_odontologo (usuario_id) ON DELETE CASCADE,
    inicio        TIMESTAMPTZ NOT NULL,
    fin           TIMESTAMPTZ NOT NULL,
    motivo        VARCHAR(150),
    creado_en     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_bloqueos_agenda_rango CHECK (fin > inicio)
);

CREATE INDEX idx_bloqueos_agenda_odontologo_inicio ON bloqueos_agenda (odontologo_id, inicio);

COMMENT ON TABLE bloqueos_agenda IS
    'RF07: bloqueo puntual de agenda. La validación de que una cita nueva no caiga dentro de un bloqueo activo se hace en la capa de servicio (Spring), no como constraint de BD.';

-- ----------------------------------------------------------------------------
-- Geolocalización
-- ----------------------------------------------------------------------------
CREATE INDEX idx_perfiles_odontologo_distrito ON perfiles_odontologo (distrito_consultorio) WHERE desactivado_en IS NULL;

CREATE INDEX idx_perfiles_odontologo_lat_long
    ON perfiles_odontologo (latitud_consultorio, longitud_consultorio)
    WHERE desactivado_en IS NULL;

-- v8 (restaurado de v5.0): índice de EXPRESIÓN sobre el cast ::geography. Las consultas deben usar
-- exactamente ST_DWithin(ubicacion_geografica::geography, <punto>::geography, metros) y
-- WHERE desactivado_en IS NULL para que el planner use este índice parcial.
CREATE INDEX idx_perfiles_odontologo_ubicacion_geografica
    ON perfiles_odontologo USING GIST ((ubicacion_geografica::geography))
    WHERE desactivado_en IS NULL;

-- ============================================================================
-- 8. MÓDULO DE CITAS
-- ============================================================================
-- v7: se agrega cita_origen_id (trazabilidad reprogramación->cita original)
-- y el estado REPROGRAMADA, del script del compañero.
CREATE TABLE citas (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    paciente_id              UUID NOT NULL REFERENCES usuarios (id) ON DELETE RESTRICT,
    odontologo_id            UUID NOT NULL REFERENCES perfiles_odontologo (usuario_id) ON DELETE RESTRICT,
    servicio_id              UUID NOT NULL,
    cita_origen_id           UUID REFERENCES citas (id) ON DELETE RESTRICT,
    inicio_programado        TIMESTAMPTZ NOT NULL,
    fin_programado           TIMESTAMPTZ NOT NULL,
    zona_horaria             VARCHAR(50) NOT NULL DEFAULT 'America/Lima',
    precio_servicio          NUMERIC(10, 2) NOT NULL,
    monto_deposito_requerido NUMERIC(10, 2) NOT NULL,
    duracion_minutos         SMALLINT NOT NULL,
    estado                   estado_cita NOT NULL DEFAULT 'PENDIENTE_PAGO',
    referencia_bloqueo       VARCHAR(100),
    id_evento_google         VARCHAR(255),
    registrado_en            TIMESTAMPTZ,
    tolerancia_vence_en      TIMESTAMPTZ,
    completado_en            TIMESTAMPTZ,
    cancelado_en             TIMESTAMPTZ,
    motivo_cancelacion       TEXT,
    creado_en                TIMESTAMPTZ NOT NULL DEFAULT now(),
    actualizado_en           TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_citas_rango_horario CHECK (fin_programado > inicio_programado),
    CONSTRAINT ck_citas_origen_distinta CHECK (cita_origen_id IS NULL OR cita_origen_id <> id),
    CONSTRAINT ck_citas_precio_snapshot CHECK (precio_servicio >= 0),
    CONSTRAINT ck_citas_deposito_snapshot CHECK (monto_deposito_requerido >= 0),
    CONSTRAINT ck_citas_duracion_snapshot CHECK (duracion_minutos > 0),
    CONSTRAINT ck_citas_zona_horaria CHECK (btrim(zona_horaria) <> ''),
    CONSTRAINT uq_citas_id_paciente UNIQUE (id, paciente_id),
    CONSTRAINT uq_citas_id_odontologo UNIQUE (id, odontologo_id),
    CONSTRAINT fk_citas_servicio_odontologo FOREIGN KEY (servicio_id, odontologo_id)
        REFERENCES servicios (id, odontologo_id) ON DELETE RESTRICT,
    CONSTRAINT ex_citas_sin_solape EXCLUDE USING gist (
        odontologo_id WITH =,
        tstzrange(inicio_programado, fin_programado, '[)') WITH &&
    ) WHERE (estado IN ('PENDIENTE_PAGO', 'CONFIRMADA'))
);

-- Máximo una cita "hija" de reprogramación por cita origen (evita cadenas)
CREATE UNIQUE INDEX uq_citas_cita_origen ON citas (cita_origen_id) WHERE cita_origen_id IS NOT NULL;

CREATE OR REPLACE FUNCTION fn_snapshot_servicio_en_cita()
RETURNS TRIGGER AS $$
DECLARE
    v_precio NUMERIC(10,2);
    v_deposito NUMERIC(10,2);
    v_duracion SMALLINT;
    v_zona VARCHAR(50);
BEGIN
    SELECT s.precio_total, s.monto_deposito, s.duracion_minutos, po.zona_horaria
      INTO v_precio, v_deposito, v_duracion, v_zona
      FROM servicios s
      JOIN perfiles_odontologo po ON po.usuario_id = s.odontologo_id
     WHERE s.id = NEW.servicio_id
       AND s.odontologo_id = NEW.odontologo_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'El servicio % no pertenece al odontólogo %.', NEW.servicio_id, NEW.odontologo_id;
    END IF;

    IF TG_OP = 'INSERT' THEN
        NEW.precio_servicio := v_precio;
        NEW.monto_deposito_requerido := v_deposito;
        NEW.duracion_minutos := v_duracion;
        NEW.zona_horaria := COALESCE(v_zona, 'America/Lima');
    ELSE
        -- Los datos económicos/históricos no se cambian al editar el servicio.
        -- Solo se permite conservar el snapshot original.
        IF NEW.servicio_id IS DISTINCT FROM OLD.servicio_id THEN
            RAISE EXCEPTION 'No se puede cambiar el servicio de una cita existente; use reprogramación.';
        END IF;
        NEW.precio_servicio := OLD.precio_servicio;
        NEW.monto_deposito_requerido := OLD.monto_deposito_requerido;
        NEW.duracion_minutos := OLD.duracion_minutos;
        NEW.zona_horaria := OLD.zona_horaria;
    END IF;

    IF ROUND(EXTRACT(EPOCH FROM (NEW.fin_programado - NEW.inicio_programado)) / 60.0) <> NEW.duracion_minutos THEN
        RAISE EXCEPTION 'La duración de la cita (%) no coincide con la duración del servicio (%) minutos.',
            ROUND(EXTRACT(EPOCH FROM (NEW.fin_programado - NEW.inicio_programado)) / 60.0), NEW.duracion_minutos;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION fn_validar_cita_no_bloqueada()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.estado IN ('PENDIENTE_PAGO', 'CONFIRMADA', 'PENDIENTE_CONFIRMACION')
       AND EXISTS (
           SELECT 1
             FROM bloqueos_agenda b
            WHERE b.odontologo_id = NEW.odontologo_id
              AND tstzrange(b.inicio, b.fin, '[)') && tstzrange(NEW.inicio_programado, NEW.fin_programado, '[)')
       ) THEN
        RAISE EXCEPTION 'La cita % se superpone con un bloqueo de agenda del odontólogo %.', NEW.id, NEW.odontologo_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_citas_validar_rol_paciente
    BEFORE INSERT OR UPDATE OF paciente_id ON citas
    FOR EACH ROW EXECUTE FUNCTION validar_rol_paciente_cita();

CREATE TRIGGER trg_citas_snapshot_servicio
    BEFORE INSERT OR UPDATE OF servicio_id, odontologo_id ON citas
    FOR EACH ROW EXECUTE FUNCTION fn_snapshot_servicio_en_cita();

CREATE TRIGGER trg_citas_validar_bloqueo
    BEFORE INSERT OR UPDATE OF odontologo_id, inicio_programado, fin_programado, estado ON citas
    FOR EACH ROW EXECUTE FUNCTION fn_validar_cita_no_bloqueada();

CREATE TRIGGER trg_citas_actualizado_en
    BEFORE UPDATE ON citas
    FOR EACH ROW EXECUTE FUNCTION fijar_actualizado_en();

CREATE TRIGGER trg_citas_tolerancia
    BEFORE INSERT ON citas
    FOR EACH ROW EXECUTE FUNCTION fijar_vencimiento_tolerancia();

CREATE INDEX idx_citas_odontologo_horario ON citas (odontologo_id, inicio_programado);
CREATE INDEX idx_citas_estado             ON citas (estado);
CREATE INDEX idx_citas_paciente_id        ON citas (paciente_id);

COMMENT ON COLUMN citas.precio_servicio IS
    'Snapshot del precio vigente al momento de reservar; evita que cambios posteriores del servicio alteren el histórico.';
COMMENT ON COLUMN citas.monto_deposito_requerido IS
    'Snapshot del depósito requerido al reservar; fuente histórica para el pago de esta cita.';
COMMENT ON COLUMN citas.duracion_minutos IS
    'Snapshot de la duración del servicio al reservar.';
COMMENT ON COLUMN citas.zona_horaria IS
    'Snapshot de la zona horaria del odontólogo al reservar; evita reinterpretaciones históricas.';

COMMENT ON COLUMN citas.cita_origen_id IS
    'RF05: si la cita nace de una reprogramación, apunta a la cita original (que queda en estado REPROGRAMADA). La capa de servicio debe pasar la original a REPROGRAMADA ANTES de insertar la nueva (REPROGRAMADA no participa en ex_citas_sin_solape). Límites: MAX_REPROGRAMACIONES_POR_CITA y HORAS_MINIMAS_REPROGRAMACION, validados en la capa de servicio.';
COMMENT ON COLUMN citas.estado IS
    'PENDIENTE_PAGO y CONFIRMADA bloquean la agenda (ex_citas_sin_solape). PENDIENTE_CONFIRMACION (RF11) NO la bloquea: al aprobar, el paso a CONFIRMADA vuelve a validar el solape.';
COMMENT ON COLUMN citas.id_evento_google IS
    'Reservada para extensibilidad futura (igual que perfiles_odontologo.token_google_calendar_cifrado). La agenda es 100% nativa en PostgreSQL; Google OAuth solo se usa para login (RF16).';

-- ============================================================================
-- 9. MÓDULO DE PAGOS Y AUDITORÍA
-- ============================================================================
-- v9: pagos es el agregado de negocio. Los intentos, webhooks y reembolsos
-- viven separados para permitir reintentos, idempotencia y trazabilidad.
CREATE TABLE pagos (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cita_id                     UUID NOT NULL REFERENCES citas (id) ON DELETE RESTRICT,
    metodo_pago                 metodo_pago NOT NULL,
    pasarela_pago               pasarela_pago,
    monto                       NUMERIC(10, 2) NOT NULL,
    numero_operacion            VARCHAR(50),
    url_comprobante             TEXT,
    estado                      estado_pago NOT NULL DEFAULT 'PENDIENTE',
    verificado_en               TIMESTAMPTZ,
    reembolsado_en              TIMESTAMPTZ,
    numero_referencia_reembolso VARCHAR(100),
    creado_en                   TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_pagos_monto_positivo CHECK (monto >= 0),
    CONSTRAINT uq_pagos_metodo_operacion UNIQUE (metodo_pago, numero_operacion),
    CONSTRAINT ck_pagos_pasarela_requerida_tarjeta CHECK (
        metodo_pago <> 'TARJETA' OR pasarela_pago IS NOT NULL
    )
);

CREATE INDEX idx_pagos_cita_id ON pagos (cita_id);
CREATE INDEX idx_pagos_estado ON pagos (estado);
CREATE UNIQUE INDEX uq_pagos_unico_verificado_por_cita
    ON pagos (cita_id) WHERE estado = 'VERIFICADO';

COMMENT ON COLUMN pagos.pasarela_pago IS
    'Procesador técnico (CULQI o NIUBIZ). Obligatorio para TARJETA y opcional para YAPE/PLIN cuando el QR es generado por la pasarela.';
COMMENT ON COLUMN pagos.numero_operacion IS
    'Referencia externa de la operación cuando ya existe. Es NULL mientras el pago esté PENDIENTE; no se usa como mecanismo de idempotencia de webhooks.';
COMMENT ON TABLE pagos IS
    'Agregado de pago por cita. La confirmación de VERIFICADO se realiza únicamente después de procesar un webhook firmado y verificado por Spring Boot.';

CREATE TABLE intentos_pago (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pago_id             UUID NOT NULL REFERENCES pagos (id) ON DELETE RESTRICT,
    pasarela_pago       pasarela_pago NOT NULL,
    clave_idempotencia  VARCHAR(100) NOT NULL,
    id_externo          VARCHAR(150),
    monto               NUMERIC(10,2) NOT NULL,
    estado              estado_intento_pago NOT NULL DEFAULT 'CREADO',
    mensaje_error       TEXT,
    solicitado_en       TIMESTAMPTZ NOT NULL DEFAULT now(),
    actualizado_en      TIMESTAMPTZ NOT NULL DEFAULT now(),
    confirmado_en       TIMESTAMPTZ,

    CONSTRAINT ck_intentos_pago_monto CHECK (monto >= 0),
    CONSTRAINT uq_intento_pago_idempotencia UNIQUE (pasarela_pago, clave_idempotencia),
    CONSTRAINT uq_intento_pago_externo UNIQUE (pasarela_pago, id_externo)
);

CREATE TRIGGER trg_intentos_pago_actualizado_en
    BEFORE UPDATE ON intentos_pago
    FOR EACH ROW EXECUTE FUNCTION fijar_actualizado_en();

CREATE INDEX idx_intentos_pago_pago ON intentos_pago (pago_id, solicitado_en DESC);
CREATE INDEX idx_intentos_pago_estado ON intentos_pago (estado);

CREATE TABLE eventos_pago_webhook (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pago_id               UUID REFERENCES pagos (id) ON DELETE RESTRICT,
    intento_pago_id       UUID REFERENCES intentos_pago (id) ON DELETE RESTRICT,
    pasarela_pago         pasarela_pago NOT NULL,
    evento_externo_id     VARCHAR(150) NOT NULL,
    tipo_evento           VARCHAR(100) NOT NULL,
    payload_json          JSONB NOT NULL,
    payload_hash          CHAR(64) NOT NULL,
    firma_recibida        TEXT,
    firma_verificada      BOOLEAN NOT NULL DEFAULT FALSE,
    estado                 estado_webhook_pago NOT NULL DEFAULT 'RECIBIDO',
    recibido_en            TIMESTAMPTZ NOT NULL DEFAULT now(),
    procesado_en           TIMESTAMPTZ,
    mensaje_error          TEXT,

    CONSTRAINT uq_webhook_pasarela_evento UNIQUE (pasarela_pago, evento_externo_id),
    CONSTRAINT ck_webhook_procesado_firmado CHECK (
        estado <> 'PROCESADO' OR (firma_verificada = TRUE AND procesado_en IS NOT NULL)
    )
);

CREATE OR REPLACE FUNCTION fijar_hash_payload_webhook()
RETURNS TRIGGER AS $$
BEGIN
    NEW.payload_hash := encode(digest(NEW.payload_json::text, 'sha256'), 'hex');
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SET search_path = public, extensions;

CREATE TRIGGER trg_webhook_hash_payload
    BEFORE INSERT OR UPDATE OF payload_json ON eventos_pago_webhook
    FOR EACH ROW EXECUTE FUNCTION fijar_hash_payload_webhook();

CREATE INDEX idx_webhook_pago_pendientes
    ON eventos_pago_webhook (recibido_en)
    WHERE estado IN ('RECIBIDO', 'ERROR');
CREATE INDEX idx_webhook_pago_pago ON eventos_pago_webhook (pago_id, recibido_en DESC);

COMMENT ON TABLE eventos_pago_webhook IS
    'Registro idempotente de webhooks de Culqi/Niubiz. Spring Boot valida la firma con el secreto oficial del gateway antes de marcar firma_verificada=TRUE. Nunca almacenar datos completos de tarjeta.';
COMMENT ON COLUMN eventos_pago_webhook.payload_hash IS
    'SHA-256 del payload recibido para trazabilidad e integridad. No sustituye la firma criptográfica del gateway.';

CREATE TABLE reembolsos (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pago_id             UUID NOT NULL REFERENCES pagos (id) ON DELETE RESTRICT,
    pasarela_pago       pasarela_pago NOT NULL,
    clave_idempotencia  VARCHAR(100) NOT NULL,
    id_externo          VARCHAR(150),
    monto               NUMERIC(10,2) NOT NULL,
    estado              estado_reembolso NOT NULL DEFAULT 'SOLICITADO',
    motivo              TEXT,
    solicitado_en       TIMESTAMPTZ NOT NULL DEFAULT now(),
    procesado_en        TIMESTAMPTZ,
    mensaje_error       TEXT,

    CONSTRAINT ck_reembolsos_monto CHECK (monto > 0),
    CONSTRAINT uq_reembolso_idempotencia UNIQUE (pago_id, clave_idempotencia),
    CONSTRAINT uq_reembolso_externo UNIQUE (pasarela_pago, id_externo)
);

CREATE OR REPLACE FUNCTION validar_reembolso_pago()
RETURNS TRIGGER AS $$
DECLARE
    v_estado estado_pago;
    v_pasarela pasarela_pago;
    v_total NUMERIC(10,2);
BEGIN
    SELECT estado, pasarela_pago INTO v_estado, v_pasarela
      FROM pagos WHERE id = NEW.pago_id;

    IF v_estado <> 'VERIFICADO' AND NEW.estado IN ('PROCESANDO', 'REEMBOLSADO') THEN
        RAISE EXCEPTION 'Solo se puede procesar un reembolso sobre un pago VERIFICADO (pago %).', NEW.pago_id;
    END IF;

    IF NEW.pasarela_pago IS DISTINCT FROM v_pasarela THEN
        RAISE EXCEPTION 'La pasarela del reembolso debe coincidir con la pasarela del pago %.', NEW.pago_id;
    END IF;

    SELECT COALESCE(SUM(r.monto) FILTER (WHERE r.estado IN ('SOLICITADO','PROCESANDO','REEMBOLSADO')), 0)
      INTO v_total
      FROM reembolsos r
     WHERE r.pago_id = NEW.pago_id
       AND r.id <> NEW.id;

    IF v_total + NEW.monto > (SELECT p.monto FROM pagos p WHERE p.id = NEW.pago_id) THEN
        RAISE EXCEPTION 'El total de reembolsos no puede superar el monto original del pago %.', NEW.pago_id;
    END IF;

    IF NEW.estado = 'REEMBOLSADO' THEN
        NEW.procesado_en := COALESCE(NEW.procesado_en, now());
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_reembolsos_validar
    BEFORE INSERT OR UPDATE OF pago_id, pasarela_pago, monto, estado ON reembolsos
    FOR EACH ROW EXECUTE FUNCTION validar_reembolso_pago();

CREATE INDEX idx_reembolsos_pago ON reembolsos (pago_id, solicitado_en DESC);
CREATE INDEX idx_reembolsos_estado ON reembolsos (estado);

COMMENT ON TABLE reembolsos IS
    'RF15: reembolsos separados de pagos para soportar reintentos, idempotencia y trazabilidad. Nunca se almacenan datos de tarjeta.';

-- Solo un pago confirmado por cita. Los reintentos RECHAZADOS/EXPIRADOS se conservan.
CREATE OR REPLACE FUNCTION validar_estado_pago_por_webhook()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.estado = 'VERIFICADO' THEN
        IF NOT EXISTS (
            SELECT 1 FROM eventos_pago_webhook w
             WHERE w.pago_id = NEW.id
               AND w.firma_verificada = TRUE
               AND w.estado = 'PROCESADO'
        ) THEN
            RAISE EXCEPTION 'Un pago solo puede pasar a VERIFICADO después de procesar un webhook firmado y verificado (pago %).', NEW.id;
        END IF;
        NEW.verificado_en := COALESCE(NEW.verificado_en, now());
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION validar_monto_pago_cita()
RETURNS TRIGGER AS $$
DECLARE
    v_precio NUMERIC(10,2);
BEGIN
    SELECT precio_servicio INTO v_precio FROM citas WHERE id = NEW.cita_id;
    IF NEW.monto <= 0 OR NEW.monto > v_precio THEN
        RAISE EXCEPTION 'El monto del pago (%) debe ser mayor que 0 y no superar el precio histórico de la cita (%).', NEW.monto, v_precio;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_pagos_validar_monto
    BEFORE INSERT OR UPDATE OF cita_id, monto ON pagos
    FOR EACH ROW EXECUTE FUNCTION validar_monto_pago_cita();

CREATE TRIGGER trg_pagos_verificacion_webhook
    BEFORE INSERT OR UPDATE OF estado ON pagos
    FOR EACH ROW EXECUTE FUNCTION validar_estado_pago_por_webhook();

CREATE OR REPLACE FUNCTION validar_estado_reembolso()
RETURNS TRIGGER AS $$
DECLARE
    v_monto_pago NUMERIC(10,2);
    v_total_reembolsado NUMERIC(10,2);
BEGIN
    IF NEW.estado = 'REEMBOLSADO' THEN
        SELECT monto INTO v_monto_pago FROM pagos WHERE id = NEW.pago_id;
        SELECT COALESCE(SUM(r.monto), 0)
          INTO v_total_reembolsado
          FROM reembolsos r
         WHERE r.pago_id = NEW.pago_id
           AND r.estado = 'REEMBOLSADO';

        IF v_total_reembolsado >= v_monto_pago THEN
            UPDATE pagos
               SET estado = 'REEMBOLSADO',
                   reembolsado_en = COALESCE(reembolsado_en, now()),
                   numero_referencia_reembolso = COALESCE(NEW.id_externo, numero_referencia_reembolso)
             WHERE id = NEW.pago_id;
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_reembolsos_actualiza_pago
    AFTER INSERT OR UPDATE OF estado ON reembolsos
    FOR EACH ROW EXECUTE FUNCTION validar_estado_reembolso();

-- ----------------------------------------------------------------------------
-- Recordatorios de cita (RF10) — v8. Clase "Recordatorio" del diagrama de clases.
-- ----------------------------------------------------------------------------
CREATE TABLE recordatorios_cita (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cita_id         UUID NOT NULL REFERENCES citas (id) ON DELETE RESTRICT,
    canal           canal_notificacion  NOT NULL,
    programado_para TIMESTAMPTZ         NOT NULL,
    estado          estado_notificacion NOT NULL DEFAULT 'PENDIENTE',
    enviado_en      TIMESTAMPTZ,
    creado_en       TIMESTAMPTZ         NOT NULL DEFAULT now(),

    CONSTRAINT uq_recordatorios_cita_canal_hora UNIQUE (cita_id, canal, programado_para)
);

CREATE INDEX idx_recordatorios_pendientes ON recordatorios_cita (programado_para) WHERE estado = 'PENDIENTE';

COMMENT ON TABLE recordatorios_cita IS
    'RF10: recordatorios programados por cita y canal (EMAIL, PUSH, WHATSAPP-Sandbox). Un System Job procesa los PENDIENTE cuyo programado_para ya venció y deja el rastro de envío en registro_notificaciones.';

-- ----------------------------------------------------------------------------
CREATE TABLE liquidaciones (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    odontologo_id UUID NOT NULL REFERENCES perfiles_odontologo (usuario_id) ON DELETE RESTRICT,
    cita_id       UUID REFERENCES citas (id) ON DELETE SET NULL,
    monto         NUMERIC(10, 2) NOT NULL,
    estado        estado_liquidacion NOT NULL DEFAULT 'PENDIENTE',
    referencia_pago VARCHAR(80),     -- N.º de operación de la transferencia; UNIQUE = idempotencia del job
    motivo_fallo  TEXT,
    liquidado_en  TIMESTAMPTZ,
    creado_en     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_liquidaciones_monto_positivo CHECK (monto >= 0),
    CONSTRAINT uq_liquidaciones_referencia_pago UNIQUE (referencia_pago)
);

CREATE INDEX idx_liquidaciones_odontologo_id ON liquidaciones (odontologo_id);
CREATE INDEX idx_liquidaciones_estado        ON liquidaciones (estado);

-- ----------------------------------------------------------------------------
CREATE TABLE bitacora_auditoria_citas (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cita_id          UUID NOT NULL REFERENCES citas (id) ON DELETE RESTRICT,
    estado_anterior  estado_cita,
    estado_nuevo     estado_cita NOT NULL,
    usuario_actor_id UUID REFERENCES usuarios (id) ON DELETE SET NULL,
    nota             TEXT,
    creado_en        TIMESTAMPTZ NOT NULL DEFAULT now(),
    hash_anterior    CHAR(64),            -- v8: hash_registro del registro previo de la cadena (NULL en el primero)
    hash_registro    CHAR(64) NOT NULL    -- v8: SHA-256 (hex) de este registro + hash_anterior, lo fija el trigger
);

CREATE INDEX idx_bitacora_auditoria_cita_id   ON bitacora_auditoria_citas (cita_id);
CREATE INDEX idx_bitacora_auditoria_creado_en ON bitacora_auditoria_citas (creado_en);

COMMENT ON COLUMN bitacora_auditoria_citas.usuario_actor_id IS
    'Paciente u odontólogo que originó el cambio. NULL cuando la transición la dispara el propio sistema (System Job).';

CREATE TRIGGER trg_bitacora_auditoria_inmutable
    BEFORE UPDATE OR DELETE ON bitacora_auditoria_citas
    FOR EACH ROW EXECUTE FUNCTION evitar_mutacion_bitacora();

COMMENT ON TABLE bitacora_auditoria_citas IS
    'Bitácora de SOLO INSERCIÓN. Bloqueada por trigger trg_bitacora_auditoria_inmutable (RNF07 / Ley N.º 29733). Cadena de hashes SHA-256 (hash_anterior -> hash_registro): fn_verificar_bitacora() detecta cualquier alteración.';

-- v8 (RNF07): cadena de hashes SHA-256. Cada registro incluye el hash del anterior.
-- El advisory lock serializa las inserciones para que la cadena no se bifurque.
CREATE OR REPLACE FUNCTION fijar_hash_bitacora()
RETURNS TRIGGER AS $$
DECLARE
    v_prev CHAR(64);
BEGIN
    PERFORM pg_advisory_xact_lock(hashtext('bitacora_auditoria_citas'));
    SELECT b.hash_registro INTO v_prev FROM bitacora_auditoria_citas b ORDER BY b.id DESC LIMIT 1;
    NEW.hash_anterior := v_prev;
    NEW.hash_registro := encode(digest(concat_ws('|',
        COALESCE(v_prev, ''), NEW.id, NEW.cita_id,
        COALESCE(NEW.estado_anterior::text, ''), NEW.estado_nuevo::text,
        COALESCE(NEW.usuario_actor_id::text, ''), COALESCE(NEW.nota, ''),
        to_char(NEW.creado_en AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US')
    ), 'sha256'), 'hex');
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SET search_path = public, extensions;

CREATE TRIGGER trg_bitacora_auditoria_hash
    BEFORE INSERT ON bitacora_auditoria_citas
    FOR EACH ROW EXECUTE FUNCTION fijar_hash_bitacora();

CREATE OR REPLACE FUNCTION registrar_auditoria_creacion_cita()
RETURNS TRIGGER AS $$
DECLARE
    v_actor UUID;
BEGIN
    BEGIN
        v_actor := NULLIF(current_setting('app.usuario_id', true), '')::UUID;
    EXCEPTION WHEN invalid_text_representation THEN
        v_actor := NULL;
    END;

    INSERT INTO bitacora_auditoria_citas (
        cita_id, estado_anterior, estado_nuevo, usuario_actor_id, nota
    ) VALUES (
        NEW.id, NULL, NEW.estado, v_actor,
        'Creación de cita.'
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_citas_auditar_creacion
    AFTER INSERT ON citas
    FOR EACH ROW EXECUTE FUNCTION registrar_auditoria_creacion_cita();

-- v9: registra automáticamente cada transición de estado de una cita.
-- Spring Boot puede establecer SET LOCAL app.usuario_id = '<UUID>' antes del UPDATE.
CREATE OR REPLACE FUNCTION registrar_auditoria_cambio_cita()
RETURNS TRIGGER AS $$
DECLARE
    v_actor UUID;
BEGIN
    IF NEW.estado IS DISTINCT FROM OLD.estado THEN
        BEGIN
            v_actor := NULLIF(current_setting('app.usuario_id', true), '')::UUID;
        EXCEPTION WHEN invalid_text_representation THEN
            v_actor := NULL;
        END;

        INSERT INTO bitacora_auditoria_citas (
            cita_id, estado_anterior, estado_nuevo, usuario_actor_id, nota
        ) VALUES (
            NEW.id, OLD.estado, NEW.estado, v_actor,
            COALESCE(NEW.motivo_cancelacion, 'Transición automática registrada por la base de datos.')
        );
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_citas_auditar_cambio_estado
    AFTER UPDATE OF estado ON citas
    FOR EACH ROW EXECUTE FUNCTION registrar_auditoria_cambio_cita();

-- Devuelve las filas cuya huella o enlace no cuadra (vacío = bitácora íntegra).
CREATE OR REPLACE FUNCTION fn_verificar_bitacora()
RETURNS TABLE (id BIGINT, hash_valido BOOLEAN, enlace_valido BOOLEAN) AS $$
    WITH cadena AS (
        SELECT b.*,
               LAG(b.hash_registro) OVER (ORDER BY b.id) AS hash_esperado_anterior
          FROM bitacora_auditoria_citas b
    )
    SELECT c.id,
           c.hash_registro = encode(digest(concat_ws('|',
               COALESCE(c.hash_anterior, ''), c.id, c.cita_id,
               COALESCE(c.estado_anterior::text, ''), c.estado_nuevo::text,
               COALESCE(c.usuario_actor_id::text, ''), COALESCE(c.nota, ''),
               to_char(c.creado_en AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US')
           ), 'sha256'), 'hex') AS hash_valido,
           c.hash_anterior IS NOT DISTINCT FROM c.hash_esperado_anterior AS enlace_valido
      FROM cadena c
     WHERE NOT (
           c.hash_registro = encode(digest(concat_ws('|',
               COALESCE(c.hash_anterior, ''), c.id, c.cita_id,
               COALESCE(c.estado_anterior::text, ''), c.estado_nuevo::text,
               COALESCE(c.usuario_actor_id::text, ''), COALESCE(c.nota, ''),
               to_char(c.creado_en AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US')
           ), 'sha256'), 'hex')
       AND c.hash_anterior IS NOT DISTINCT FROM c.hash_esperado_anterior
     )
     ORDER BY c.id;
$$ LANGUAGE sql STABLE SET search_path = public, extensions;

-- ============================================================================
-- 10. HISTORIAS CLÍNICAS Y APELACIONES — Ley N.º 29733
-- ============================================================================
-- v7: se conserva el cifrado a nivel de aplicación (pgcrypto/AES-256) sobre
-- BYTEA, y se amplía a diagnóstico y tratamiento por separado (antes solo
-- "notas_clinicas_cifradas"), para que el informe pueda describir ambos
-- campos como cifrados según lo pedido.
CREATE OR REPLACE FUNCTION validar_integridad_clinica_cita()
RETURNS TRIGGER AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM usuarios u WHERE u.id = NEW.paciente_id AND u.rol = 'PACIENTE') THEN
        RAISE EXCEPTION 'El paciente % no tiene rol PACIENTE.', NEW.paciente_id;
    END IF;
    IF NEW.creado_por_odontologo_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM usuarios u WHERE u.id = NEW.creado_por_odontologo_id AND u.rol = 'ODONTOLOGO'
    ) THEN
        RAISE EXCEPTION 'El creador % no tiene rol ODONTOLOGO.', NEW.creado_por_odontologo_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TABLE historias_clinicas (
    id                       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cita_id                  UUID NOT NULL REFERENCES citas (id) ON DELETE RESTRICT,
    paciente_id              UUID NOT NULL REFERENCES usuarios (id) ON DELETE RESTRICT,
    creado_por_odontologo_id UUID REFERENCES perfiles_odontologo (usuario_id) ON DELETE SET NULL,
    diagnostico_cifrado      BYTEA NOT NULL,
    tratamiento_cifrado      BYTEA NOT NULL,
    notas_clinicas_cifradas  BYTEA,             -- observaciones adicionales, opcional
    creado_en                TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_historias_clinicas_cita UNIQUE (cita_id),
    -- v8: el paciente de la historia debe ser el paciente de la cita
    CONSTRAINT fk_historias_cita_paciente FOREIGN KEY (cita_id, paciente_id)
        REFERENCES citas (id, paciente_id) ON DELETE RESTRICT,
    CONSTRAINT fk_historias_cita_odontologo FOREIGN KEY (cita_id, creado_por_odontologo_id)
        REFERENCES citas (id, odontologo_id) ON DELETE RESTRICT
);

CREATE INDEX idx_historias_clinicas_paciente_id ON historias_clinicas (paciente_id);

COMMENT ON TABLE historias_clinicas IS
    'Contenido clínico cifrado en AES-256 mediante pgcrypto antes de insertarse (cifrado/descifrado orquestado por el backend Spring Boot, no por triggers de BD). Cumple Ley N.º 29733.';
COMMENT ON COLUMN historias_clinicas.diagnostico_cifrado IS 'BYTEA: diagnóstico cifrado en AES-256 (pgcrypto).';
COMMENT ON COLUMN historias_clinicas.tratamiento_cifrado  IS 'BYTEA: tratamiento realizado, cifrado en AES-256 (pgcrypto).';

CREATE TRIGGER trg_historias_clinicas_validar_roles
    BEFORE INSERT OR UPDATE OF paciente_id, creado_por_odontologo_id ON historias_clinicas
    FOR EACH ROW EXECUTE FUNCTION validar_integridad_clinica_cita();

-- ----------------------------------------------------------------------------
CREATE TABLE apelaciones (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cita_id                     UUID NOT NULL UNIQUE REFERENCES citas (id) ON DELETE RESTRICT,
    url_certificado             TEXT NOT NULL,
    estado                      estado_apelacion NOT NULL DEFAULT 'PENDIENTE',
    resuelto_por_odontologo_id  UUID,
    nota_resolucion             TEXT,
    creado_en                   TIMESTAMPTZ NOT NULL DEFAULT now(),
    resuelto_en                 TIMESTAMPTZ
);

ALTER TABLE apelaciones
    ADD CONSTRAINT fk_apelaciones_resolutor_cita
    FOREIGN KEY (cita_id, resuelto_por_odontologo_id)
    REFERENCES citas (id, odontologo_id) ON DELETE RESTRICT;

CREATE OR REPLACE FUNCTION validar_apelacion_inasistencia()
RETURNS TRIGGER AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM citas c WHERE c.id = NEW.cita_id AND c.estado = 'INASISTENCIA_PENALIZADA'
    ) THEN
        RAISE EXCEPTION 'La apelación % solo puede corresponder a una cita con INASISTENCIA_PENALIZADA.', NEW.id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_apelaciones_validar_inasistencia
    BEFORE INSERT OR UPDATE OF cita_id ON apelaciones
    FOR EACH ROW EXECUTE FUNCTION validar_apelacion_inasistencia();

CREATE INDEX idx_apelaciones_estado ON apelaciones (estado);

COMMENT ON TABLE apelaciones IS
    'Apelación del paciente sobre una INASISTENCIA_PENALIZADA (adjunta certificado). La resuelve el odontólogo de la cita afectada; arquitectura admin-less, sin mediador humano externo.';

-- ============================================================================
-- 11. RESEÑAS Y CALIFICACIONES (ANTI-SPAM: 1 reseña por cita)
-- ============================================================================
CREATE TABLE resenas (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cita_id        UUID NOT NULL UNIQUE REFERENCES citas (id) ON DELETE RESTRICT,
    odontologo_id  UUID NOT NULL REFERENCES perfiles_odontologo (usuario_id) ON DELETE RESTRICT,
    paciente_id    UUID NOT NULL REFERENCES usuarios (id) ON DELETE RESTRICT,
    calificacion   SMALLINT NOT NULL,
    comentario     TEXT,
    creado_en      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_resenas_calificacion_rango CHECK (calificacion BETWEEN 1 AND 5),
    -- v8: la reseña la escribe el paciente de la cita y va dirigida al odontólogo de la cita
    CONSTRAINT fk_resenas_cita_paciente   FOREIGN KEY (cita_id, paciente_id)
        REFERENCES citas (id, paciente_id)   ON DELETE RESTRICT,
    CONSTRAINT fk_resenas_cita_odontologo FOREIGN KEY (cita_id, odontologo_id)
        REFERENCES citas (id, odontologo_id) ON DELETE RESTRICT
);

CREATE INDEX idx_resenas_odontologo_id ON resenas (odontologo_id);
CREATE INDEX idx_resenas_paciente_id   ON resenas (paciente_id);

CREATE TRIGGER trg_resenas_actualizar_calificacion_ins
    AFTER INSERT ON resenas
    FOR EACH ROW EXECUTE FUNCTION actualizar_cache_calificacion_odontologo();

CREATE TRIGGER trg_resenas_actualizar_calificacion_del
    AFTER DELETE ON resenas
    FOR EACH ROW EXECUTE FUNCTION actualizar_cache_calificacion_odontologo();

-- v8: el caché también debe recalcularse si se edita la calificación
CREATE TRIGGER trg_resenas_actualizar_calificacion_upd
    AFTER UPDATE OF calificacion ON resenas
    FOR EACH ROW EXECUTE FUNCTION actualizar_cache_calificacion_odontologo();

-- v8: "reseñas verificadas" — solo sobre citas efectivamente atendidas
CREATE OR REPLACE FUNCTION validar_resena_cita_completada()
RETURNS TRIGGER AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM citas c WHERE c.id = NEW.cita_id AND c.estado = 'COMPLETADA') THEN
        RAISE EXCEPTION 'Solo se puede reseñar una cita en estado COMPLETADA (cita %).', NEW.cita_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_resenas_solo_completadas
    BEFORE INSERT ON resenas
    FOR EACH ROW EXECUTE FUNCTION validar_resena_cita_completada();

-- ============================================================================
-- 12. RECLAMOS Y SOPORTE — v7, del script del compañero
-- ============================================================================
-- Consultas/incidencias/reclamos generales (no específicos de una
-- inasistencia, para eso está "apelaciones"). En arquitectura admin-less se
-- atienden primero por el chatbot (sesiones_chat) y, si no se resuelven,
-- quedan aquí para seguimiento asíncrono por System Job / el propio
-- odontólogo según corresponda al asunto.
CREATE TABLE reclamos (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    usuario_id     UUID NOT NULL REFERENCES usuarios (id) ON DELETE RESTRICT,
    cita_id        UUID REFERENCES citas (id) ON DELETE SET NULL,
    tipo           tipo_reclamo NOT NULL,
    estado         estado_reclamo NOT NULL DEFAULT 'ABIERTO',
    asunto         VARCHAR(150) NOT NULL,
    descripcion    TEXT NOT NULL,
    respuesta      TEXT,
    resuelto_en    TIMESTAMPTZ,
    creado_en      TIMESTAMPTZ NOT NULL DEFAULT now(),
    actualizado_en TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_reclamos_resuelto CHECK (resuelto_en IS NULL OR estado IN ('RESUELTO', 'CERRADO'))
);

CREATE TRIGGER trg_reclamos_actualizado_en
    BEFORE UPDATE ON reclamos
    FOR EACH ROW EXECUTE FUNCTION fijar_actualizado_en();

CREATE INDEX idx_reclamos_usuario   ON reclamos (usuario_id);
CREATE INDEX idx_reclamos_abiertos  ON reclamos (estado) WHERE estado IN ('ABIERTO', 'EN_PROCESO');

-- ============================================================================
-- 13. PARAMETRIZACIÓN DINÁMICA (fusión de ambos scripts)
-- ============================================================================
CREATE TABLE configuracion_sistema (
    clave_parametro VARCHAR(50) PRIMARY KEY,
    valor_parametro VARCHAR(255) NOT NULL,
    descripcion     TEXT,
    actualizado_en  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TRIGGER trg_configuracion_sistema_actualizado_en
    BEFORE UPDATE ON configuracion_sistema
    FOR EACH ROW EXECUTE FUNCTION fijar_actualizado_en();

INSERT INTO configuracion_sistema (clave_parametro, valor_parametro, descripcion) VALUES
    ('TOLERANCIA_MINUTOS_TARDANZA',        '15', 'Minutos de tolerancia tras inicio_programado antes de habilitar el botón "Inasistencia".'),
    ('LIMITE_MAXIMO_STRIKES',              '3',  'Número máximo de inasistencias antes de bloquear la cuenta del paciente.'),
    ('PORCENTAJE_DEPOSITO_GARANTIA',       '20', 'Porcentaje del precio total del servicio cobrado como depósito de garantía al reservar.'),
    ('RADIO_BUSQUEDA_CERCANIA_KM',         '10', 'Radio por defecto, en kilómetros, usado al buscar odontólogos "cerca de mí".'),
    ('HORAS_MINIMAS_CANCELACION_REEMBOLSO','12', 'Horas mínimas de anticipación para cancelar con reembolso (ventana de cancelación gratuita).'),
    ('HORAS_MINIMAS_REPROGRAMACION',       '24', 'Horas mínimas de anticipación para reprogramar una cita.'),
    ('MAX_REPROGRAMACIONES_POR_CITA',      '1',  'Límite de reprogramaciones permitidas por cita (evita cadenas indefinidas).')
ON CONFLICT (clave_parametro) DO NOTHING;

-- Índices de soporte para jobs, agenda y pagos.
CREATE INDEX idx_citas_paciente_estado_horario
    ON citas (paciente_id, estado, inicio_programado);
CREATE INDEX idx_citas_odontologo_estado_horario
    ON citas (odontologo_id, estado, inicio_programado);
CREATE INDEX idx_bloqueos_agenda_rango
    ON bloqueos_agenda USING gist (odontologo_id, tstzrange(inicio, fin, '[)'));
CREATE INDEX idx_pagos_operacion
    ON pagos (numero_operacion) WHERE numero_operacion IS NOT NULL;

-- ============================================================================
-- 14. VISTAS ANALÍTICAS (KPIs para RF12 y dashboards)
-- ============================================================================
CREATE OR REPLACE VIEW vw_kpis_mensuales_odontologo AS
SELECT
    c.odontologo_id,
    DATE_TRUNC('month', c.inicio_programado) AS mes,
    COUNT(DISTINCT c.id) FILTER (WHERE c.estado <> 'REPROGRAMADA')             AS total_citas,
    COUNT(DISTINCT c.id) FILTER (WHERE c.estado = 'COMPLETADA')                AS citas_completadas,
    COUNT(DISTINCT c.id) FILTER (WHERE c.estado = 'INASISTENCIA_PENALIZADA')   AS ausencias_pacientes,
    COALESCE(SUM(p.monto) FILTER (WHERE p.estado = 'VERIFICADO'), 0) AS ingresos_totales
FROM citas c
LEFT JOIN pagos p ON p.cita_id = c.id
GROUP BY c.odontologo_id, DATE_TRUNC('month', c.inicio_programado);

CREATE OR REPLACE VIEW vw_rendimiento_servicios AS
SELECT
    s.odontologo_id,
    s.id AS servicio_id,
    s.titulo AS servicio,
    COUNT(DISTINCT c.id) FILTER (WHERE c.estado <> 'REPROGRAMADA')    AS total_reservas,
    COALESCE(SUM(p.monto) FILTER (WHERE p.estado = 'VERIFICADO'), 0)  AS total_recaudado
FROM servicios s
LEFT JOIN citas c ON c.servicio_id = s.id
LEFT JOIN pagos p ON p.cita_id = c.id
GROUP BY s.odontologo_id, s.id, s.titulo;

CREATE OR REPLACE VIEW vw_resumen_paciente AS
SELECT
    u.id AS paciente_id,
    u.nombre_completo,
    rp.cantidad_strikes,
    rp.estado AS estado_reputacion,
    COUNT(c.id) FILTER (WHERE c.estado <> 'REPROGRAMADA') AS total_citas_historico
FROM usuarios u
LEFT JOIN reputacion_paciente rp ON rp.usuario_id = u.id
LEFT JOIN citas c ON c.paciente_id = u.id
WHERE u.rol = 'PACIENTE'
GROUP BY u.id, u.nombre_completo, rp.cantidad_strikes, rp.estado;

-- ============================================================================
-- 15. SEGURIDAD DE SUPABASE / RLS
-- ============================================================================
-- v9: NO se habilita RLS global automáticamente. La arquitectura del proyecto
-- usa Spring Boot como gateway central y la aplicación se conecta a PostgreSQL
-- mediante credenciales de backend. Activar RLS sin políticas bloquearía el acceso
-- y el bucle genérico de v8 también podía alcanzar objetos de soporte de PostGIS.
--
-- Si en el futuro React/Kotlin acceden directamente a la Data API de Supabase,
-- se deben crear políticas RLS EXPLÍCITAS por tabla basadas en auth.uid(),
-- nunca habilitar RLS masivamente sin políticas.
--
-- Recomendación de despliegue: ejecutar este DDL con el rol propietario de la BD;
-- mantener las credenciales de PostgreSQL exclusivamente en Spring Boot/Secrets.
-- Nunca exponer la contraseña de BD, service_role key ni secretos de gateways al frontend.
-- ============================================================================
-- FIN DEL SCRIPT — v9.0 (base v8 depurada para producción)
-- ============================================================================
