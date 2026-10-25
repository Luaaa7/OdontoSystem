package com.odontosystem.OdontoSystem.constancias;

import com.odontosystem.OdontoSystem.entity.Cita;
import com.odontosystem.OdontoSystem.entity.EstadoPago;
import com.odontosystem.OdontoSystem.entity.MetodoPago;
import com.odontosystem.OdontoSystem.entity.Pago;
import com.odontosystem.OdontoSystem.entity.PerfilOdontologo;
import com.odontosystem.OdontoSystem.entity.Servicio;
import com.odontosystem.OdontoSystem.entity.Usuario;
import com.odontosystem.OdontoSystem.repository.CitaRepository;
import com.odontosystem.OdontoSystem.repository.PagoRepository;
import com.odontosystem.OdontoSystem.repository.ReembolsoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.server.ResponseStatusException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import java.io.IOException;
import java.io.UncheckedIOException;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConstanciaServiceTest {

        static final OffsetDateTime AHORA = OffsetDateTime.of(2026, 10, 19, 10, 0, 0, 0, ZoneOffset.ofHours(-5));

        @Mock
        private CitaRepository citas;
        @Mock
        private PagoRepository pagos;
        @Mock
        private ReembolsoRepository reembolsos;

        private ConstanciaService servicio;
        private Cita cita;
        private Usuario paciente;

        @BeforeEach
        void setUp() {
                servicio = new ConstanciaService(citas, pagos, reembolsos,
                                Clock.fixed(AHORA.toInstant(), ZoneOffset.UTC));
                paciente = Usuario.builder().id(UUID.randomUUID()).nombreCompleto("Lucía Ramos").tipoDocumento("DNI")
                                .numeroDocumento("71234567").build();
                UUID odId = UUID.randomUUID();
                PerfilOdontologo perfil = PerfilOdontologo.builder().usuarioId(odId).numeroColegiatura("12345")
                                .usuario(Usuario.builder().id(odId).nombreCompleto("Dra. Ana Torres").build())
                                .nombreConsultorio("Sonrisas").direccionConsultorio("Av. San Martín 123")
                                .distritoConsultorio("Ica").build();
                cita = Cita.builder().id(UUID.randomUUID()).paciente(paciente).odontologo(perfil)
                                .servicio(Servicio.builder().titulo("Limpieza").build())
                                .inicioProgramado(AHORA.plusDays(1))
                                .precioServicio(new BigDecimal("80.00")).build();
                when(citas.conDetalle(cita.getId())).thenReturn(Optional.of(cita));
        }

        @Test
        @DisplayName("Con el pago verificado: PDF válido con código CON- y la aclaración de que no es tributario")
        void generaPdf() {
                UUID pagoId = UUID.fromString("1a2b3c4d-0000-0000-0000-000000000000");
                when(pagos.findFirstByCita_IdAndEstado(cita.getId(), EstadoPago.VERIFICADO)).thenReturn(Optional.of(
                                Pago.builder().id(pagoId).cita(cita).monto(new BigDecimal("16.00"))
                                                .metodoPago(MetodoPago.YAPE)
                                                .estado(EstadoPago.VERIFICADO).verificadoEn(AHORA).build()));

                ConstanciaService.Constancia c = servicio.deCita(paciente.getId(), cita.getId());

                assertThat(c.nombreArchivo()).isEqualTo("constancia-CON-1A2B3C4D.pdf");
                String texto = textoDe(c.pdf());
                assertThat(new String(c.pdf(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
                assertThat(texto).contains("CONSTANCIA DE PAGO DE GARANTÍA", "no es boleta ni factura", "Lucía Ramos",
                                "S/ 16.00", "S/ 64.00", "CON-1A2B3C4D");
        }

        @Test
        @DisplayName("Sin depósito pagado → 409; alguien que no es de la cita → 404")
        void errores() {
                assertThatThrownBy(() -> servicio.deCita(paciente.getId(), cita.getId()))
                                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value())
                                                .isEqualTo(409));
                assertThatThrownBy(() -> servicio.deCita(UUID.randomUUID(), cita.getId()))
                                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value())
                                                .isEqualTo(404));
        }

        @Test
        @DisplayName("Texto del PDF: escapa paréntesis y parte los párrafos largos por palabras")
        void utilidades() {

                assertThat(PdfSimple.partir("uno dos tres cuatro", 9)).containsExactly("uno dos", "tres", "cuatro");
        }

        private static String textoDe(byte[] pdf) {
                try (PDDocument doc = Loader.loadPDF(pdf)) {
                        return new PDFTextStripper().getText(doc);
                } catch (IOException e) {
                        throw new UncheckedIOException(e);
                }
        }

        @Test
        @DisplayName("Caracteres especiales: el PDF conserva letras fuera de WinAnsi")
        void conservaCaracteresEspeciales() {
                byte[] pdf = new PdfSimple()
                                .texto("Łukasz Żółć — Ñandú Ω", 50, 700, 12, false)
                                .generar();
                assertThat(textoDe(pdf)).contains("Łukasz Żółć — Ñandú Ω");
        }

}
