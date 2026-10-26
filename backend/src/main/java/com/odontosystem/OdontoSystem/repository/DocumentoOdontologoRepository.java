package com.odontosystem.OdontoSystem.repository;

import com.odontosystem.OdontoSystem.entity.DocumentoOdontologo;
import com.odontosystem.OdontoSystem.entity.TipoDocumentoOdontologo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentoOdontologoRepository extends JpaRepository<DocumentoOdontologo, UUID> {

    /** Documentos vigentes (no reemplazados) del odontólogo. */
    List<DocumentoOdontologo> findByOdontologoIdAndReemplazadoEnIsNullOrderByTipoDocumentoAsc(UUID odontologoId);

    /** Documento vigente de un tipo; la BD garantiza que hay como máximo uno. */
    Optional<DocumentoOdontologo> findByOdontologoIdAndTipoDocumentoAndReemplazadoEnIsNull(
            UUID odontologoId, TipoDocumentoOdontologo tipoDocumento);
}
