-- Control de consumo de APIs externas de pago por consulta (Factiliza / RENIEC).
-- Igual que "tokens_disponibles": cada consulta descuenta 1 del cupo del mes; sin cupo, no se llama a la API.
CREATE TABLE consumo_api_externa (
    servicio          VARCHAR(40)  NOT NULL,
    periodo           CHAR(7)      NOT NULL,          -- 'AAAA-MM'
    consultas_usadas  INTEGER      NOT NULL DEFAULT 0,
    actualizado_en    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_consumo_api_externa PRIMARY KEY (servicio, periodo),
    CONSTRAINT ck_consumo_no_negativo CHECK (consultas_usadas >= 0),
    CONSTRAINT ck_consumo_periodo CHECK (periodo ~ '^[0-9]{4}-[0-9]{2}$')
);

COMMENT ON TABLE consumo_api_externa IS
    'Consultas hechas a APIs externas con cupo (p. ej. FACTILIZA_DNI) por mes. El límite está en la configuración del backend.';

ALTER TABLE consumo_api_externa ENABLE ROW LEVEL SECURITY;
