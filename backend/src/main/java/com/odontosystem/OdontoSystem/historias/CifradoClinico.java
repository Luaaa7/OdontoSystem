package com.odontosystem.OdontoSystem.historias;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Cifrado de la información clínica (Ley N.° 29733, RNF07) con AES-256-GCM.
 *
 * <ul>
 *   <li>La clave (32 bytes en Base64) viene de HISTORIA_CLINICA_CLAVE y nunca sale del backend:
 *       la BD solo recibe bytes cifrados, así que ni una copia de la BD ni sus logs exponen los datos.</li>
 *   <li>Cada valor lleva su propio vector aleatorio (IV): el mismo texto cifrado dos veces da bytes distintos.</li>
 *   <li>GCM además autentica: si alguien altera los bytes o los copia a otra fila/campo
 *       (el "contexto" = cita + campo), descifrar falla en lugar de devolver datos falsos.</li>
 * </ul>
 * Formato guardado: [versión 1 byte][IV 12 bytes][texto cifrado + etiqueta de 16 bytes].
 * ¡Si la clave se pierde, las historias no se pueden recuperar! Guárdala también fuera de Railway.
 */
@Component
public class CifradoClinico {

    private static final Logger log = LoggerFactory.getLogger(CifradoClinico.class);
    private static final byte VERSION = 1;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey clave;
    private final SecureRandom azar = new SecureRandom();

    public CifradoClinico(@Value("${app.historias.clave:}") String claveBase64) {
        if (claveBase64 == null || claveBase64.isBlank()) {
            this.clave = null;
            log.warn("HISTORIA_CLINICA_CLAVE no está configurada: las historias clínicas responderán 503");
            return;
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(claveBase64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("HISTORIA_CLINICA_CLAVE debe estar en Base64");
        }
        if (bytes.length != 32) {
            throw new IllegalStateException("HISTORIA_CLINICA_CLAVE debe tener 32 bytes (AES-256); tiene " + bytes.length);
        }
        this.clave = new SecretKeySpec(bytes, "AES");
    }

    public boolean disponible() {
        return clave != null;
    }

    public byte[] cifrar(String texto, String contexto) {
        exigirClave();
        try {
            byte[] iv = new byte[IV_BYTES];
            azar.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, clave, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(contexto.getBytes(StandardCharsets.UTF_8));
            byte[] cifrado = cipher.doFinal(texto.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(1 + IV_BYTES + cifrado.length).put(VERSION).put(iv).put(cifrado).array();
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo cifrar la información clínica", e);
        }
    }

    public String descifrar(byte[] datos, String contexto) {
        exigirClave();
        if (datos == null || datos.length < 1 + IV_BYTES + TAG_BITS / 8 || datos[0] != VERSION) {
            throw new IllegalStateException("Dato clínico con formato desconocido");
        }
        try {
            ByteBuffer buffer = ByteBuffer.wrap(datos);
            buffer.get(); // versión
            byte[] iv = new byte[IV_BYTES];
            buffer.get(iv);
            byte[] cifrado = new byte[buffer.remaining()];
            buffer.get(cifrado);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, clave, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(contexto.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(cifrado), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // clave distinta o datos alterados: nunca se devuelve texto sin verificar
            throw new IllegalStateException("No se pudo descifrar la información clínica (¿cambió la clave?)", e);
        }
    }

    private void exigirClave() {
        if (clave == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Las historias clínicas no están disponibles: falta configurar el cifrado");
        }
    }
}
