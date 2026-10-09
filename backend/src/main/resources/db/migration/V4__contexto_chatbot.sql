-- Memoria de contexto del chatbot (Hito 2).
-- Guarda en qué paso de la conversación va el usuario: distrito, odontólogo, servicio,
-- fecha y turno que eligió. Así el bot entiende respuestas cortas como "1", "a las 10"
-- o "¿y el lunes?" aunque lleguen en otro mensaje o después de reiniciar el servidor.
-- Solo lo lee y escribe el backend. Columna nueva con valor por defecto: no cambia datos existentes.
ALTER TABLE sesiones_chat
    ADD COLUMN contexto JSONB NOT NULL DEFAULT '{}'::jsonb;

COMMENT ON COLUMN sesiones_chat.contexto IS
    'Memoria de la conversación del chatbot (paso actual y elecciones del usuario). La maneja el backend.';
