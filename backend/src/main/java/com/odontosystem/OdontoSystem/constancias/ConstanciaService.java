package com.odontosystem.OdontoSystem.constancias;

import com.odontosystem.OdontoSystem.entity.Cita;
import com.odontosystem.OdontoSystem.entity.EstadoPago;
import com.odontosystem.OdontoSystem.entity.Pago;
import com.odontosystem.OdontoSystem.entity.PerfilOdontologo;
import com.odontosystem.OdontoSystem.entity.Reembolso;
import com.odontosystem.OdontoSystem.entity.Usuario;
import com.odontosystem.OdontoSystem.repository.CitaRepository;
import com.odontosystem.OdontoSystem.repository.PagoRepository;
import com.odontosystem.OdontoSystem.repository.ReembolsoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;

/**
 * RF14: constancia de pago del depósito de garantía, en PDF. <b>No es un comprobante tributario</b>: OdontoSystem
 * solo recibe el depósito en custodia; la boleta o factura del servicio la emite el odontólogo con su RUC al
 * atender. La pueden descargar el paciente y el odontólogo de la cita, una vez que el pago se verificó.
 */
@Service
@RequiredArgsConstructor
public class ConstanciaService {

    public record Constancia(String nombreArchivo, byte[] pdf) {}

    private final CitaRepository citaRepository;
    private final PagoRepository pagoRepository;
    private final ReembolsoRepository reembolsoRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public Constancia deCita(UUID usuarioId, UUID citaId) {
        Cita cita = citaRepository.conDetalle(citaId)
                .filter(c -> c.getPaciente().getId().equals(usuarioId) || c.getOdontologo().getUsuarioId().equals(usuarioId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cita no encontrada"));
        Pago pago = pagoRepository.findFirstByCita_IdAndEstado(citaId, EstadoPago.VERIFICADO)
                .or(() -> pagoRepository.findFirstByCita_IdAndEstado(citaId, EstadoPago.REEMBOLSADO))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "Esta cita no tiene un depósito pagado; la constancia sale cuando el pago se verifica"));
        OffsetDateTime devuelto = pago.getEstado() == EstadoPago.REEMBOLSADO
                ? reembolsoRepository.findByPago_Cita_IdOrderBySolicitadoEnDesc(citaId).stream()
                        .map(Reembolso::getProcesadoEn).filter(java.util.Objects::nonNull).findFirst()
                        .orElse(OffsetDateTime.now(clock))
                : null;
        Usuario p = cita.getPaciente();
        PerfilOdontologo o = cita.getOdontologo();
        String codigo = codigo(pago.getId());
        ConstanciaPdf.Datos d = new ConstanciaPdf.Datos(codigo, p.getNombreCompleto(), p.getTipoDocumento() + " " + p.getNumeroDocumento(),
                o.getUsuario().getNombreCompleto(), o.getNumeroColegiatura(), o.getNombreConsultorio(),
                unir(o.getDireccionConsultorio(), o.getDistritoConsultorio()), cita.getServicio().getTitulo(),
                cita.getInicioProgramado(), cita.getPrecioServicio(), pago.getMonto(),
                pago.getMetodoPago() != null ? pago.getMetodoPago().name() : "-", pago.getNumeroOperacion(),
                pago.getVerificadoEn() != null ? pago.getVerificadoEn() : pago.getCreadoEn(), devuelto,
                OffsetDateTime.now(clock));
        return new Constancia("constancia-" + codigo + ".pdf", ConstanciaPdf.armar(d));
    }

    /** CON- y los primeros 8 caracteres del id del pago. */
    static String codigo(UUID pagoId) {
        return "CON-" + pagoId.toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private static String unir(String direccion, String distrito) {
        if (direccion == null || direccion.isBlank()) return distrito;
        return distrito == null || distrito.isBlank() ? direccion : direccion + ", " + distrito;
    }
}
