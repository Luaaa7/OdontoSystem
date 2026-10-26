package com.odontosystem.OdontoSystem.archivos;

import com.odontosystem.OdontoSystem.archivos.ArchivoService.ArchivoSubido;
import com.odontosystem.OdontoSystem.archivos.ArchivoService.EnlaceTemporal;
import com.odontosystem.OdontoSystem.archivos.ArchivosResponses.DocumentoResponse;
import com.odontosystem.OdontoSystem.archivos.ArchivosResponses.FotoConsultorioResponse;
import com.odontosystem.OdontoSystem.archivos.ArchivosResponses.FotoPerfilResponse;
import com.odontosystem.OdontoSystem.entity.DocumentoOdontologo;
import com.odontosystem.OdontoSystem.entity.FotoConsultorio;
import com.odontosystem.OdontoSystem.entity.TipoDocumentoOdontologo;
import com.odontosystem.OdontoSystem.entity.Usuario;
import com.odontosystem.OdontoSystem.repository.DocumentoOdontologoRepository;
import com.odontosystem.OdontoSystem.repository.FotoConsultorioRepository;
import com.odontosystem.OdontoSystem.repository.PerfilOdontologoRepository;
import com.odontosystem.OdontoSystem.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Reglas de negocio de los archivos: qué se guarda en la BD y qué se sube a Cloudinary.
 * Si la BD falla después de subir, se borra el archivo recién subido para no dejar huérfanos.
 */
@Service
@RequiredArgsConstructor
public class GestionArchivosService {

    static final int MAXIMO_FOTOS_CONSULTORIO = 10;

    private final ArchivoService archivoService;
    private final UsuarioRepository usuarioRepository;
    private final PerfilOdontologoRepository perfilOdontologoRepository;
    private final DocumentoOdontologoRepository documentoRepository;
    private final FotoConsultorioRepository fotoRepository;

    // ------------------------------------------------------------------
    // Foto de perfil (paciente u odontólogo)
    // ------------------------------------------------------------------

    @Transactional
    public FotoPerfilResponse actualizarFotoPerfil(UUID usuarioId, MultipartFile archivo) {
        Usuario usuario = usuarioRepository.findById(usuarioId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario no encontrado"));
        // public_id fijo por usuario: la foto nueva reemplaza a la anterior en Cloudinary
        ArchivoSubido subido = archivoService.subirImagenPublica(archivo, "perfiles", usuarioId.toString());
        usuario.setFotoPerfilUrl(subido.url());
        usuarioRepository.save(usuario);
        return new FotoPerfilResponse(subido.url());
    }

    // ------------------------------------------------------------------
    // Documentos del odontólogo (privados)
    // ------------------------------------------------------------------

    @Transactional
    public DocumentoResponse subirDocumento(UUID odontologoId, TipoDocumentoOdontologo tipo, MultipartFile archivo) {
        exigirPerfilOdontologo(odontologoId);
        ArchivoSubido subido = archivoService.subirDocumentoPrivado(archivo, "documentos/" + odontologoId);
        try {
            // El anterior se marca como reemplazado ANTES de insertar el nuevo: la BD solo permite
            // un documento vigente por tipo (índice único parcial).
            documentoRepository.findByOdontologoIdAndTipoDocumentoAndReemplazadoEnIsNull(odontologoId, tipo)
                    .ifPresent(anterior -> {
                        anterior.setReemplazadoEn(OffsetDateTime.now());
                        documentoRepository.saveAndFlush(anterior);
                    });
            DocumentoOdontologo nuevo = documentoRepository.saveAndFlush(DocumentoOdontologo.builder()
                    .odontologoId(odontologoId)
                    .tipoDocumento(tipo)
                    .urlArchivo(subido.url())
                    .build());
            return aRespuesta(nuevo);
        } catch (RuntimeException e) {
            archivoService.eliminar(subido.url());
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public List<DocumentoResponse> listarDocumentos(UUID odontologoId) {
        exigirPerfilOdontologo(odontologoId);
        return documentoRepository.findByOdontologoIdAndReemplazadoEnIsNullOrderByTipoDocumentoAsc(odontologoId)
                .stream()
                .map(this::aRespuesta)
                .toList();
    }

    // ------------------------------------------------------------------
    // Fotos del consultorio (públicas, máximo 10)
    // ------------------------------------------------------------------

    @Transactional
    public FotoConsultorioResponse agregarFotoConsultorio(UUID odontologoId, MultipartFile archivo, String descripcion) {
        exigirPerfilOdontologo(odontologoId);
        if (descripcion != null && descripcion.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La descripción admite como máximo 255 caracteres");
        }
        List<FotoConsultorio> actuales = fotoRepository.findByOdontologoIdOrderByOrdenAsc(odontologoId);
        if (actuales.size() >= MAXIMO_FOTOS_CONSULTORIO) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ya tienes " + MAXIMO_FOTOS_CONSULTORIO + " fotos; elimina una para subir otra");
        }
        short orden = actuales.isEmpty() ? 0 : (short) (actuales.get(actuales.size() - 1).getOrden() + 1);

        ArchivoSubido subido = archivoService.subirImagenPublica(archivo, "consultorios/" + odontologoId, null);
        try {
            FotoConsultorio foto = fotoRepository.saveAndFlush(FotoConsultorio.builder()
                    .odontologoId(odontologoId)
                    .urlFoto(subido.url())
                    .descripcion(descripcion == null || descripcion.isBlank() ? null : descripcion.trim())
                    .orden(orden)
                    .build());
            return aRespuesta(foto);
        } catch (RuntimeException e) {
            archivoService.eliminar(subido.url());
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public List<FotoConsultorioResponse> listarFotosConsultorio(UUID odontologoId) {
        exigirPerfilOdontologo(odontologoId);
        return fotoRepository.findByOdontologoIdOrderByOrdenAsc(odontologoId).stream()
                .map(this::aRespuesta)
                .toList();
    }

    @Transactional
    public void eliminarFotoConsultorio(UUID odontologoId, UUID fotoId) {
        FotoConsultorio foto = fotoRepository.findByIdAndOdontologoId(fotoId, odontologoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Foto no encontrada"));
        fotoRepository.delete(foto);
        fotoRepository.flush();
        archivoService.eliminar(foto.getUrlFoto());
    }

    // ------------------------------------------------------------------

    private void exigirPerfilOdontologo(UUID odontologoId) {
        if (!perfilOdontologoRepository.existsById(odontologoId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Perfil de odontólogo no encontrado");
        }
    }

    private DocumentoResponse aRespuesta(DocumentoOdontologo documento) {
        EnlaceTemporal enlace = archivoService.enlaceTemporal(documento.getUrlArchivo());
        return new DocumentoResponse(documento.getId(), documento.getTipoDocumento(), documento.getSubidoEn(),
                enlace.url(), enlace.expiraEn());
    }

    private FotoConsultorioResponse aRespuesta(FotoConsultorio foto) {
        return new FotoConsultorioResponse(foto.getId(), foto.getUrlFoto(), foto.getDescripcion(), foto.getOrden());
    }
}
