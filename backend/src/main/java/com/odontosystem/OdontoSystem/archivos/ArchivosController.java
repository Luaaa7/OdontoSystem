package com.odontosystem.OdontoSystem.archivos;

import com.odontosystem.OdontoSystem.archivos.ArchivosResponses.DocumentoResponse;
import com.odontosystem.OdontoSystem.archivos.ArchivosResponses.FotoConsultorioResponse;
import com.odontosystem.OdontoSystem.archivos.ArchivosResponses.FotoPerfilResponse;
import com.odontosystem.OdontoSystem.entity.TipoDocumentoOdontologo;
import com.odontosystem.OdontoSystem.security.UsuarioPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * Subida de archivos a Cloudinary. Todos requieren JWT; las rutas /api/odontologos/yo/**
 * además exigen rol ODONTOLOGO (SecurityConfig). Los archivos se envían como multipart/form-data
 * en el campo "archivo" (máximo 5 MB).
 */
@Tag(name = "Archivos", description = "Foto de perfil, documentos del odontólogo y fotos del consultorio (Cloudinary)")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ArchivosController {

    private final GestionArchivosService gestionArchivosService;

    @Operation(summary = "Subir o cambiar la foto de perfil",
            description = "JPG, PNG o WEBP de hasta 5 MB. Reemplaza la foto anterior. Paciente u odontólogo.")
    @PutMapping(value = "/usuarios/yo/foto", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public FotoPerfilResponse actualizarFotoPerfil(@AuthenticationPrincipal UsuarioPrincipal principal,
                                                   @RequestPart("archivo") MultipartFile archivo) {
        return gestionArchivosService.actualizarFotoPerfil(principal.getUsuario().getId(), archivo);
    }

    @Operation(summary = "Subir un documento de verificación",
            description = "PDF, JPG o PNG de hasta 5 MB. Si ya existía uno del mismo tipo, queda como reemplazado. "
                    + "El archivo es privado: se devuelve un enlace temporal que vence a los 10 minutos.")
    @PostMapping(value = "/odontologos/yo/documentos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentoResponse subirDocumento(@AuthenticationPrincipal UsuarioPrincipal principal,
                                            @RequestParam("tipo") TipoDocumentoOdontologo tipo,
                                            @RequestPart("archivo") MultipartFile archivo) {
        return gestionArchivosService.subirDocumento(principal.getUsuario().getId(), tipo, archivo);
    }

    @Operation(summary = "Mis documentos vigentes",
            description = "Un documento por tipo, cada uno con un enlace temporal nuevo (10 minutos).")
    @GetMapping("/odontologos/yo/documentos")
    public List<DocumentoResponse> listarDocumentos(@AuthenticationPrincipal UsuarioPrincipal principal) {
        return gestionArchivosService.listarDocumentos(principal.getUsuario().getId());
    }

    @Operation(summary = "Agregar una foto del consultorio",
            description = "JPG, PNG o WEBP de hasta 5 MB, máximo 10 fotos. La descripción es opcional (255 caracteres).")
    @PostMapping(value = "/odontologos/yo/fotos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public FotoConsultorioResponse agregarFoto(@AuthenticationPrincipal UsuarioPrincipal principal,
                                               @RequestPart("archivo") MultipartFile archivo,
                                               @RequestParam(value = "descripcion", required = false) String descripcion) {
        return gestionArchivosService.agregarFotoConsultorio(principal.getUsuario().getId(), archivo, descripcion);
    }

    @Operation(summary = "Fotos de mi consultorio", description = "En el orden en que se muestran.")
    @GetMapping("/odontologos/yo/fotos")
    public List<FotoConsultorioResponse> listarFotos(@AuthenticationPrincipal UsuarioPrincipal principal) {
        return gestionArchivosService.listarFotosConsultorio(principal.getUsuario().getId());
    }

    @Operation(summary = "Eliminar una foto del consultorio")
    @DeleteMapping("/odontologos/yo/fotos/{fotoId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void eliminarFoto(@AuthenticationPrincipal UsuarioPrincipal principal, @PathVariable UUID fotoId) {
        gestionArchivosService.eliminarFotoConsultorio(principal.getUsuario().getId(), fotoId);
    }
}
