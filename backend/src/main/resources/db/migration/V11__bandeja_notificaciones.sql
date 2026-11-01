-- Bandeja de notificaciones dentro de la app (la campanita de la web y la app).
-- Cada aviso que el backend manda por correo/push también queda aquí, con su texto, para que el usuario lo vea
-- en la app y lo marque como leído. registro_notificaciones sigue siendo el rastro técnico de cada envío por canal.
CREATE TABLE notificaciones_app (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    usuario_id UUID         NOT NULL REFERENCES usuarios (id) ON DELETE CASCADE,
    tipo       VARCHAR(40)  NOT NULL,
    titulo     VARCHAR(150) NOT NULL,
    mensaje    VARCHAR(300) NOT NULL,
    cita_id    UUID         REFERENCES citas (id) ON DELETE SET NULL,
    creado_en  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    leida_en   TIMESTAMPTZ
);

CREATE INDEX idx_notificaciones_app_usuario_creado ON notificaciones_app (usuario_id, creado_en DESC);
CREATE INDEX idx_notificaciones_app_no_leidas ON notificaciones_app (usuario_id) WHERE leida_en IS NULL;

COMMENT ON TABLE notificaciones_app IS
    'Bandeja de avisos de la app (cita confirmada, cancelada, reprogramada, recordatorio, strike, reclamo). leida_en NULL = no leída.';

-- Igual que las demás tablas: fuera del alcance de la Data API de Supabase (solo Spring Boot la usa).
ALTER TABLE notificaciones_app ENABLE ROW LEVEL SECURITY;
