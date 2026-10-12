package com.odontosystem.OdontoSystem.notificaciones;

/**
 * Manda un correo HTML. Hay tres implementaciones y se elige una con app.correo.proveedor
 * (variable CORREO_PROVEEDOR):
 * <ul>
 *   <li>{@code brevo}: API HTTPS de Brevo. Es la que sirve en Railway, que bloquea el SMTP en su plan Hobby.</li>
 *   <li>{@code smtp}: servidor SMTP (por ejemplo Gmail). Sirve para probar en la computadora.</li>
 *   <li>{@code ninguno} (por defecto): no manda nada; los recordatorios quedan pendientes para la
 *       instancia que sí tenga un proveedor.</li>
 * </ul>
 */
public interface EnviadorCorreo {

    /** false = no hay proveedor configurado: no se envía ni se marca nada como enviado. */
    boolean activo();

    /** Lanza una excepción si el proveedor rechaza el correo o no responde. */
    void enviar(String para, String nombre, String asunto, String html);
}
