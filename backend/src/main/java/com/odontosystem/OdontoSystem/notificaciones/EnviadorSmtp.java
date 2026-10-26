package com.odontosystem.OdontoSystem.notificaciones;

import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/** Envío por SMTP (spring.mail.*: MAIL_HOST, MAIL_USERNAME, MAIL_PASSWORD). Para probar en la computadora. */
@Component
@ConditionalOnProperty(name = "app.correo.proveedor", havingValue = "smtp")
public class EnviadorSmtp implements EnviadorCorreo {

    private final JavaMailSender mailSender;
    private final String remitente;
    private final String nombreRemitente;

    public EnviadorSmtp(JavaMailSender mailSender,
                        @Value("${app.correo.remitente}") String remitente,
                        @Value("${app.correo.remitente-nombre}") String nombreRemitente) {
        this.mailSender = mailSender;
        this.remitente = remitente;
        this.nombreRemitente = nombreRemitente;
    }

    @Override
    public boolean activo() {
        return true;
    }

    @Override
    public void enviar(String para, String nombre, String asunto, String html) {
        try {
            MimeMessage mensaje = mailSender.createMimeMessage();
            MimeMessageHelper ayudante = new MimeMessageHelper(mensaje, false, "UTF-8");
            ayudante.setFrom(remitente, nombreRemitente);
            ayudante.setTo(para);
            ayudante.setSubject(asunto);
            ayudante.setText(html, true);
            mailSender.send(mensaje);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo enviar el correo por SMTP: " + e.getMessage(), e);
        }
    }
}
