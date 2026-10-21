package com.odontosystem.OdontoSystem.repository;

import com.odontosystem.OdontoSystem.entity.EstadoCita;
import com.odontosystem.OdontoSystem.entity.EstadoLiquidacion;
import com.odontosystem.OdontoSystem.entity.EstadoPago;
import com.odontosystem.OdontoSystem.entity.Liquidacion;
import com.odontosystem.OdontoSystem.entity.Pago;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LiquidacionRepository extends JpaRepository<Liquidacion, UUID> {

    /** Depósitos cobrados (pago VERIFICADO) de citas ya terminadas que todavía no tienen liquidación. */
    @Query("select p from Pago p join fetch p.cita c join fetch c.odontologo "
            + "where p.estado = :estadoPago "
            + "and c.estado in :estados and not exists (select l.id from Liquidacion l where l.cita = c)")
    List<Pago> depositosSinLiquidar(@Param("estadoPago") EstadoPago estadoPago,
                                    @Param("estados") Collection<EstadoCita> estados);

    /**
     * El estado del pago va como parámetro y no escrito en la consulta: si se escribe fijo, Hibernate
     * lo convierte a un tipo de PostgreSQL ("estadopago") que no existe en la base.
     */
    default List<Pago> depositosSinLiquidar(Collection<EstadoCita> estados) {
        return depositosSinLiquidar(EstadoPago.VERIFICADO, estados);
    }

    @Query("select l from Liquidacion l join fetch l.cita c where l.estado = :estado")
    List<Liquidacion> enEstado(@Param("estado") EstadoLiquidacion estado);

    Optional<Liquidacion> findFirstByCita_Id(UUID citaId);

    @Query("select l from Liquidacion l left join fetch l.cita c left join fetch c.paciente left join fetch c.servicio "
            + "where l.odontologo.usuarioId = :odontologoId order by l.creadoEn desc")
    List<Liquidacion> delOdontologo(@Param("odontologoId") UUID odontologoId);
}
