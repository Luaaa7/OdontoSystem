-- Asistencia del odontólogo (respuesta a "¿qué pasa si el odontólogo no confirma su agenda o no llega?").
-- 1. Confirmación diaria de agenda: cada mañana el odontólogo dice si atiende ese día.
--    Sin confirmación no se aceptan reservas para el mismo día.
-- 2. Reporte "el odontólogo no llegó": lo hace el paciente y abre una disputa; el odontólogo responde
--    con prueba (historia clínica de la cita) en 48 h. Quien no responde a tiempo pierde.
-- 3. Faltas del odontólogo: 3 reportes confirmados en 90 días lo suspenden 30 días (no aparece en el buscador).
-- Solo agrega tablas y una columna: no cambia datos existentes.

CREATE TABLE confirmaciones_agenda (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    odontologo_id UUID NOT NULL REFERENCES perfiles_odontologo (usuario_id) ON DELETE CASCADE,
    fecha         DATE NOT NULL,
    atiende       BOOLEAN NOT NULL,
    motivo        VARCHAR(255),
    respondido_en TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_confirmaciones_agenda_dia UNIQUE (odontologo_id, fecha)
);

COMMENT ON TABLE confirmaciones_agenda IS
    'Respuesta diaria del odontólogo a "¿atiendes hoy?". Sin fila con atiende = true no se reserva para ese mismo día.';

CREATE TABLE reportes_ausencia (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cita_id              UUID NOT NULL UNIQUE REFERENCES citas (id) ON DELETE RESTRICT,
    estado               VARCHAR(20) NOT NULL DEFAULT 'PENDIENTE',
    distancia_metros     INTEGER,
    respuesta_odontologo TEXT,
    respondido_en        TIMESTAMPTZ,
    creado_en            TIMESTAMPTZ NOT NULL DEFAULT now(),
    resuelto_en          TIMESTAMPTZ,

    CONSTRAINT ck_reportes_ausencia_estado
        CHECK (estado IN ('PENDIENTE', 'CONFIRMADO', 'RECHAZADO', 'EN_RECLAMO')),
    CONSTRAINT ck_reportes_ausencia_distancia CHECK (distancia_metros IS NULL OR distancia_metros >= 0)
);

CREATE INDEX idx_reportes_ausencia_estado ON reportes_ausencia (estado, creado_en);

COMMENT ON TABLE reportes_ausencia IS
    'Reporte del paciente: "el odontólogo no llegó". PENDIENTE = disputa abierta (48 h para responder); '
    'CONFIRMADO = el odontólogo faltó (reembolso + falta); RECHAZADO = el odontólogo probó que atendió '
    '(strike al paciente); EN_RECLAMO = sin prueba clara, sigue como reclamo.';

ALTER TABLE perfiles_odontologo
    ADD COLUMN suspendido_hasta TIMESTAMPTZ;

COMMENT ON COLUMN perfiles_odontologo.suspendido_hasta IS
    'Suspensión por faltas (3 reportes de ausencia confirmados en 90 días): hasta esta fecha no aparece en el buscador.';
