-- Tabla de refresh tokens usada por el backend (entidad TokenRefresco).
-- Se creó manualmente en Supabase después del script v9; aquí queda versionada.
CREATE TABLE tokens_refresco (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    usuario_id       UUID NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
    token_hash       VARCHAR(255) NOT NULL,
    fecha_creacion   TIMESTAMPTZ NOT NULL DEFAULT now(),
    fecha_expiracion TIMESTAMPTZ NOT NULL,
    revocado         BOOLEAN NOT NULL DEFAULT false,
    fecha_revocacion TIMESTAMPTZ,
    reemplazado_por  UUID REFERENCES tokens_refresco(id),
    ip_origen        VARCHAR(45),
    user_agent       TEXT
);

CREATE INDEX idx_tokens_refresco_usuario ON tokens_refresco(usuario_id);
CREATE INDEX idx_tokens_refresco_hash ON tokens_refresco(token_hash);
