-- V10: el trigger de reputacion_paciente se conectaba (en V1) a validar_rol_paciente_cita(), que lee NEW.paciente_id.
-- Esa tabla no tiene paciente_id (su columna es usuario_id): en una base creada desde cero el registro de pacientes fallaba.
-- En producción se corrigió a mano; esta migración deja el arreglo versionado. Es idempotente.

CREATE OR REPLACE FUNCTION validar_rol_paciente()
RETURNS TRIGGER AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM usuarios u
        WHERE u.id = NEW.usuario_id AND u.rol = 'PACIENTE'
    ) THEN
        RAISE EXCEPTION 'El usuario % debe tener rol PACIENTE para tener una reputación de paciente.', NEW.usuario_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_reputacion_paciente_validar_rol ON reputacion_paciente;

CREATE TRIGGER trg_reputacion_paciente_validar_rol
    BEFORE INSERT OR UPDATE OF usuario_id ON reputacion_paciente
    FOR EACH ROW EXECUTE FUNCTION validar_rol_paciente();

COMMENT ON FUNCTION validar_rol_paciente() IS
    'Solo un usuario con rol PACIENTE puede tener fila en reputacion_paciente (strikes y suspensiones).';