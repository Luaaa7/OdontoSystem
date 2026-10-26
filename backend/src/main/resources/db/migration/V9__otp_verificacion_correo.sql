-- OTP por correo: verificación del correo de la cuenta con un código de 6 dígitos (tabla otp_verificaciones).
-- El hash es SHA-256 en hexadecimal (64 caracteres): VARCHAR en vez de CHAR para que coincida con la entidad.
-- destino crece a 150, el mismo tamaño que usuarios.correo.
ALTER TABLE otp_verificaciones ALTER COLUMN codigo_hash TYPE VARCHAR(64);
ALTER TABLE otp_verificaciones ALTER COLUMN destino TYPE VARCHAR(150);

-- Las cuentas que ya existían antes de esta versión (datos de prueba y de la demo) quedan como verificadas:
-- la verificación se exige solo a las cuentas nuevas. Google ya marca el correo como verificado al registrarse.
UPDATE usuarios SET email_verificado_en = creado_en WHERE email_verificado_en IS NULL;

COMMENT ON TABLE otp_verificaciones IS
    'Códigos OTP (6 dígitos) para verificar el correo de la cuenta. Solo se guarda el SHA-256 del código; vence en 15 min, máximo 5 intentos, un envío por minuto.';
