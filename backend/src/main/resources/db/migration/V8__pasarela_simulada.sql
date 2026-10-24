-- Se retira la integración con Culqi (necesitaba RUC). Los pagos de la demo (PAGOS_MODO=simulado) dejan de
-- registrarse como CULQI y usan su propio valor. CULQI se queda en el tipo: PostgreSQL no permite quitar valores
-- de un enum sin rehacerlo, y los pagos de prueba antiguos lo usan.
-- PostgreSQL permite ADD VALUE dentro de una transacción; el valor nuevo se usa recién desde la siguiente.
ALTER TYPE pasarela_pago ADD VALUE IF NOT EXISTS 'SIMULADA';

COMMENT ON COLUMN pagos.pasarela_pago IS
    'Procesador técnico: MERCADO_PAGO (real) o SIMULADA (demo). CULQI y NIUBIZ quedan solo por historial. Obligatorio para TARJETA.';
