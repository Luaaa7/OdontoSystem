-- 1) El hash de un refresh token debe ser único (findByTokenHash devuelve un solo registro).
ALTER TABLE tokens_refresco
    ADD CONSTRAINT uq_tokens_refresco_hash UNIQUE (token_hash);
-- El índice anterior queda redundante: UNIQUE ya crea su propio índice.
DROP INDEX IF EXISTS idx_tokens_refresco_hash;

-- 2) Cierra la API automática de Supabase (Data API / PostgREST).
--    Solo Spring Boot accede a los datos (se conecta como postgres, no le afecta).
--    Cubre tablas Y vistas; no toca la configuración de PostGIS.
REVOKE ALL ON ALL TABLES    IN SCHEMA public FROM anon, authenticated;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM anon, authenticated;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA public FROM anon, authenticated;

-- Lo mismo para todo lo que se cree en migraciones futuras.
ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON TABLES    FROM anon, authenticated;
ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON SEQUENCES FROM anon, authenticated;
ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON FUNCTIONS FROM anon, authenticated;
