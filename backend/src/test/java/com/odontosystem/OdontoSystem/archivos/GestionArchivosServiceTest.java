package com.odontosystem.OdontoSystem.archivos;

import com.odontosystem.OdontoSystem.archivos.ArchivoService.ArchivoSubido;
import com.odontosystem.OdontoSystem.archivos.ArchivoService.EnlaceTemporal;
import com.odontosystem.OdontoSystem.archivos.ArchivosResponses.DocumentoResponse;
import com.odontosystem.OdontoSystem.archivos.ArchivosResponses.FotoConsultorioResponse;
import com.odontosystem.OdontoSystem.entity.DocumentoOdontologo;
import com.odontosystem.OdontoSystem.entity.FotoConsultorio;
import com.odontosystem.OdontoSystem.entity.TipoDocumentoOdontologo;
import com.odontosystem.OdontoSystem.entity.Usuario;
import com.odontosystem.OdontoSystem.repository.DocumentoOdontologoRepository;
import com.odontosystem.OdontoSystem.repository.FotoConsultorioRepository;
import com.odontosystem.OdontoSystem.repository.PerfilOdontologoRepository;
import com.odontosystem.OdontoSystem.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reglas de negocio de los archivos. Cloudinary (ArchivoService) y la BD se simulan con Mockito.
 */
@ExtendWith(MockitoExtension.class)
class GestionArchivosServiceTest {

    @Mock private ArchivoService archivoService;
    @Mock private UsuarioRepository usuarioRepository;
    @Mock private PerfilOdontologoRepository perfilOdontologoRepository;
    @Mock private DocumentoOdontologoRepository documentoRepository;
    @Mock private FotoConsultorioRepository fotoRepository;

    private GestionArchivosService servicio;
    private final UUID odontologoId = UUID.randomUUID();
    private final MockMultipartFile archivo =
            new MockMultipartFile("archivo", "a.jpg", "image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});

    @BeforeEach
    void setUp() {
        servicio = new GestionArchivosService(archivoService, usuarioRepository, perfilOdontologoRepository,
                documentoRepository, fotoRepository);
    }

    private static HttpStatus estado(Throwable e) {
        return HttpStatus.valueOf(((ResponseStatusException) e).getStatusCode().value());
    }

    @Test
    @DisplayName("Foto de perfil: guarda la URL en el usuario")
    void actualizaFotoPerfil() {
        Usuario usuario = Usuario.builder().id(odontologoId).build();
        when(usuarioRepository.findById(odontologoId)).thenReturn(Optional.of(usuario));
        when(archivoService.subirImagenPublica(archivo, "perfiles", odontologoId.toString()))
                .thenReturn(new ArchivoSubido("https://res.cloudinary.com/x/foto.jpg", "p"));

        var respuesta = servicio.actualizarFotoPerfil(odontologoId, archivo);

        assertThat(respuesta.fotoPerfilUrl()).isEqualTo("https://res.cloudinary.com/x/foto.jpg");
        assertThat(usuario.getFotoPerfilUrl()).isEqualTo("https://res.cloudinary.com/x/foto.jpg");
        verify(usuarioRepository).save(usuario);
    }

    @Test
    @DisplayName("Documento sin perfil de odontólogo → 404 y no se sube nada")
    void documentoSinPerfil() {
        when(perfilOdontologoRepository.existsById(odontologoId)).thenReturn(false);

        assertThatThrownBy(() -> servicio.subirDocumento(odontologoId, TipoDocumentoOdontologo.DNI, archivo))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(HttpStatus.NOT_FOUND));
        verify(archivoService, never()).subirDocumentoPrivado(any(), anyString());
    }

    @Test
    @DisplayName("Documento nuevo reemplaza al anterior del mismo tipo")
    void documentoReemplazaAnterior() {
        DocumentoOdontologo anterior = DocumentoOdontologo.builder()
                .id(UUID.randomUUID()).odontologoId(odontologoId).tipoDocumento(TipoDocumentoOdontologo.DNI)
                .urlArchivo("https://res.cloudinary.com/x/viejo.pdf").build();
        when(perfilOdontologoRepository.existsById(odontologoId)).thenReturn(true);
        when(archivoService.subirDocumentoPrivado(archivo, "documentos/" + odontologoId))
                .thenReturn(new ArchivoSubido("https://res.cloudinary.com/x/nuevo.pdf", "n"));
        when(documentoRepository.findByOdontologoIdAndTipoDocumentoAndReemplazadoEnIsNull(odontologoId, TipoDocumentoOdontologo.DNI))
                .thenReturn(Optional.of(anterior));
        when(documentoRepository.saveAndFlush(any(DocumentoOdontologo.class))).thenAnswer(inv -> inv.getArgument(0));
        when(archivoService.enlaceTemporal("https://res.cloudinary.com/x/nuevo.pdf"))
                .thenReturn(new EnlaceTemporal("https://firmado", OffsetDateTime.now().plusMinutes(10)));

        DocumentoResponse respuesta = servicio.subirDocumento(odontologoId, TipoDocumentoOdontologo.DNI, archivo);

        assertThat(anterior.getReemplazadoEn()).isNotNull();
        assertThat(respuesta.tipo()).isEqualTo(TipoDocumentoOdontologo.DNI);
        assertThat(respuesta.urlTemporal()).isEqualTo("https://firmado");
    }

    @Test
    @DisplayName("Si la BD falla al guardar el documento, se borra el archivo recién subido")
    void documentoBorraArchivoSiFallaBd() {
        when(perfilOdontologoRepository.existsById(odontologoId)).thenReturn(true);
        when(archivoService.subirDocumentoPrivado(archivo, "documentos/" + odontologoId))
                .thenReturn(new ArchivoSubido("https://res.cloudinary.com/x/nuevo.pdf", "n"));
        when(documentoRepository.findByOdontologoIdAndTipoDocumentoAndReemplazadoEnIsNull(odontologoId, TipoDocumentoOdontologo.CV))
                .thenReturn(Optional.empty());
        when(documentoRepository.saveAndFlush(any(DocumentoOdontologo.class)))
                .thenThrow(new DataIntegrityViolationException("duplicado"));

        assertThatThrownBy(() -> servicio.subirDocumento(odontologoId, TipoDocumentoOdontologo.CV, archivo))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(archivoService).eliminar("https://res.cloudinary.com/x/nuevo.pdf");
    }

    @Test
    @DisplayName("Con 10 fotos ya no se puede agregar otra → 409 y no se sube nada")
    void fotoLimite() {
        List<FotoConsultorio> diez = new ArrayList<>();
        for (short i = 0; i < 10; i++) {
            diez.add(FotoConsultorio.builder().orden(i).build());
        }
        when(perfilOdontologoRepository.existsById(odontologoId)).thenReturn(true);
        when(fotoRepository.findByOdontologoIdOrderByOrdenAsc(odontologoId)).thenReturn(diez);

        assertThatThrownBy(() -> servicio.agregarFotoConsultorio(odontologoId, archivo, null))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(HttpStatus.CONFLICT));
        verify(archivoService, never()).subirImagenPublica(any(), anyString(), any());
    }

    @Test
    @DisplayName("La foto nueva va al final de la galería")
    void fotoVaAlFinal() {
        when(perfilOdontologoRepository.existsById(odontologoId)).thenReturn(true);
        when(fotoRepository.findByOdontologoIdOrderByOrdenAsc(odontologoId)).thenReturn(List.of(
                FotoConsultorio.builder().orden((short) 0).build(),
                FotoConsultorio.builder().orden((short) 3).build()));
        when(archivoService.subirImagenPublica(eq(archivo), eq("consultorios/" + odontologoId), isNull()))
                .thenReturn(new ArchivoSubido("https://res.cloudinary.com/x/f.jpg", "f"));
        when(fotoRepository.saveAndFlush(any(FotoConsultorio.class))).thenAnswer(inv -> inv.getArgument(0));

        FotoConsultorioResponse respuesta = servicio.agregarFotoConsultorio(odontologoId, archivo, "  Sala de espera ");

        assertThat(respuesta.orden()).isEqualTo((short) 4);
        assertThat(respuesta.descripcion()).isEqualTo("Sala de espera");
        assertThat(respuesta.url()).isEqualTo("https://res.cloudinary.com/x/f.jpg");
    }

    @Test
    @DisplayName("No se puede borrar una foto de otro odontólogo → 404")
    void noBorraFotoAjena() {
        UUID fotoId = UUID.randomUUID();
        when(fotoRepository.findByIdAndOdontologoId(fotoId, odontologoId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.eliminarFotoConsultorio(odontologoId, fotoId))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(HttpStatus.NOT_FOUND));
        verify(archivoService, never()).eliminar(anyString());
    }
}
