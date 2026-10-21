-- Mercado Pago (API de Orders) como pasarela de pagos, junto a Culqi y Niubiz.
-- PostgreSQL permite ADD VALUE dentro de una transacción; el valor nuevo se usa recién desde la siguiente.
ALTER TYPE pasarela_pago ADD VALUE IF NOT EXISTS 'MERCADO_PAGO';

COMMENT ON COLUMN pagos.pasarela_pago IS
    'Procesador técnico (CULQI, NIUBIZ o MERCADO_PAGO). Obligatorio para TARJETA y opcional para YAPE/PLIN cuando el QR es generado por la pasarela.';
