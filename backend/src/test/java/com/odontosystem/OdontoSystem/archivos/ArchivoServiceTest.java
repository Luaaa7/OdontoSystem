package com.odontosystem.OdontoSystem.archivos;

import com.cloudinary.Cloudinary;
import com.cloudinary.Uploader;
import com.odontosystem.OdontoSystem.archivos.ArchivoService.EnlaceTemporal;
import com.odontosystem.OdontoSystem.archivos.ArchivoService.Formato;
import com.odontosystem.OdontoSystem.archivos.ArchivoService.Referencia;
import com.odontosystem.OdontoSystem.archivos.ArchivoService.TipoArchivo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pruebas de ArchivoService sin conexión a Cloudinary: el cliente se simula con Mockito.
 */
class ArchivoServiceTest {

    private static final byte[] JPG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};
    private static final byte[] WEBP = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};
    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-', '1', '.', '7'};

    private static MockMultipartFile archivo(String nombre, byte[] contenido) {
        return new MockMultipartFile("archivo", nombre, "application/octet-stream", contenido);
    }

    private static HttpStatus estado(Throwable e) {
        return HttpStatus.valueOf(((ResponseStatusException) e).getStatusCode().value());
    }

    @Test
    @DisplayName("Reconoce JPG, PNG, WEBP y PDF por su contenido")
    void detectaFormatos() {
        assertThat(ArchivoService.detectarFormato(JPG)).isEqualTo(Formato.JPG);
        assertThat(ArchivoService.detectarFormato(PNG)).isEqualTo(Formato.PNG);
        assertThat(ArchivoService.detectarFormato(WEBP)).isEqualTo(Formato.WEBP);
        assertThat(ArchivoService.detectarFormato(PDF)).isEqualTo(Formato.PDF);
        assertThat(ArchivoService.detectarFormato("hola mundo".getBytes())).isNull();
        assertThat(ArchivoService.detectarFormato(new byte[]{(byte) 0xFF})).isNull();
    }

    @Test
    @DisplayName("Archivo vacío → 400")
    void rechazaVacio() {
        assertThatThrownBy(() -> ArchivoService.validar(archivo("a.jpg", new byte[0]), TipoArchivo.IMAGEN))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("Más de 5 MB → 413")
    void rechazaGrande() {
        byte[] grande = new byte[(int) ArchivoService.TAMANO_MAXIMO + 1];
        System.arraycopy(JPG, 0, grande, 0, JPG.length);
        assertThatThrownBy(() -> ArchivoService.validar(archivo("a.jpg", grande), TipoArchivo.IMAGEN))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(HttpStatus.CONTENT_TOO_LARGE));
    }

    @Test
    @DisplayName("Un PDF no se acepta como foto")
    void rechazaPdfComoImagen() {
        assertThatThrownBy(() -> ArchivoService.validar(archivo("foto.pdf", PDF), TipoArchivo.IMAGEN))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("Un texto renombrado a .jpg se rechaza (se valida el contenido, no la extensión)")
    void rechazaExtensionFalsa() {
        assertThatThrownBy(() -> ArchivoService.validar(archivo("virus.jpg", "<script>".getBytes()), TipoArchivo.IMAGEN))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("PDF y JPG se aceptan como documento")
    void aceptaDocumentos() {
        assertThat(ArchivoService.validar(archivo("dni.pdf", PDF), TipoArchivo.DOCUMENTO).formato()).isEqualTo(Formato.PDF);
        assertThat(ArchivoService.validar(archivo("dni.jpg", JPG), TipoArchivo.DOCUMENTO).formato()).isEqualTo(Formato.JPG);
    }

    @Test
    @DisplayName("Extrae tipo, public_id y formato de una URL de Cloudinary")
    void leeReferencia() {
        Referencia publica = ArchivoService.referencia(
                "https://res.cloudinary.com/demo/image/upload/v1712345678/odontosystem/perfiles/abc.jpg");
        assertThat(publica).isEqualTo(new Referencia("upload", "odontosystem/perfiles/abc", "jpg"));

        Referencia privada = ArchivoService.referencia(
                "https://res.cloudinary.com/demo/image/authenticated/v1/odontosystem/documentos/x/y1.pdf");
        assertThat(privada).isEqualTo(new Referencia("authenticated", "odontosystem/documentos/x/y1", "pdf"));

        assertThat(ArchivoService.referencia("https://otro-sitio.com/foto.jpg")).isNull();
        assertThat(ArchivoService.referencia(null)).isNull();
    }

    @Test
    @DisplayName("Sin credenciales de Cloudinary la subida responde 503")
    void sinConfiguracion() {
        ArchivoService servicio = new ArchivoService("", "", "");
        assertThatThrownBy(() -> servicio.subirImagenPublica(archivo("a.jpg", JPG), "perfiles", "u1"))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    @DisplayName("Foto de perfil: public_id fijo y overwrite para reemplazar la anterior")
    @SuppressWarnings("unchecked")
    void subeImagenConPublicIdFijo() throws IOException {
        Cloudinary cloudinary = mock(Cloudinary.class);
        Uploader uploader = mock(Uploader.class);
        when(cloudinary.uploader()).thenReturn(uploader);
        when(uploader.upload(any(), anyMap())).thenReturn(Map.of(
                "secure_url", "https://res.cloudinary.com/demo/image/upload/v1/odontosystem/perfiles/u1.jpg",
                "public_id", "odontosystem/perfiles/u1"));

        var subido = new ArchivoService(cloudinary).subirImagenPublica(archivo("a.jpg", JPG), "perfiles", "u1");

        ArgumentCaptor<Map<String, Object>> opciones = ArgumentCaptor.forClass(Map.class);
        verify(uploader).upload(eq(JPG), opciones.capture());
        assertThat(opciones.getValue())
                .containsEntry("public_id", "odontosystem/perfiles/u1")
                .containsEntry("overwrite", true)
                .containsEntry("type", "upload");
        assertThat(subido.url()).endsWith("/perfiles/u1.jpg");
    }

    @Test
    @DisplayName("Documento: se sube como privado (authenticated) dentro de su carpeta")
    @SuppressWarnings("unchecked")
    void subeDocumentoPrivado() throws IOException {
        Cloudinary cloudinary = mock(Cloudinary.class);
        Uploader uploader = mock(Uploader.class);
        when(cloudinary.uploader()).thenReturn(uploader);
        when(uploader.upload(any(), anyMap())).thenReturn(Map.of(
                "secure_url", "https://res.cloudinary.com/demo/image/authenticated/v1/odontosystem/documentos/o1/d.pdf",
                "public_id", "odontosystem/documentos/o1/d"));

        new ArchivoService(cloudinary).subirDocumentoPrivado(archivo("dni.pdf", PDF), "documentos/o1");

        ArgumentCaptor<Map<String, Object>> opciones = ArgumentCaptor.forClass(Map.class);
        verify(uploader).upload(eq(PDF), opciones.capture());
        assertThat(opciones.getValue())
                .containsEntry("type", "authenticated")
                .containsEntry("folder", "odontosystem/documentos/o1");
    }

    @Test
    @DisplayName("Enlace temporal: firmado, del tipo correcto y vence en unos 10 minutos")
    @SuppressWarnings("unchecked")
    void generaEnlaceTemporal() throws Exception {
        Cloudinary cloudinary = mock(Cloudinary.class);
        when(cloudinary.privateDownload(anyString(), anyString(), anyMap())).thenReturn("https://api.cloudinary.com/firmado");

        EnlaceTemporal enlace = new ArchivoService(cloudinary).enlaceTemporal(
                "https://res.cloudinary.com/demo/image/authenticated/v1/odontosystem/documentos/o1/d.pdf");

        ArgumentCaptor<Map<String, Object>> opciones = ArgumentCaptor.forClass(Map.class);
        verify(cloudinary).privateDownload(eq("odontosystem/documentos/o1/d"), eq("pdf"), opciones.capture());
        assertThat(opciones.getValue()).containsEntry("type", "authenticated");
        assertThat(enlace.url()).isEqualTo("https://api.cloudinary.com/firmado");
        assertThat(enlace.expiraEn()).isBetween(OffsetDateTime.now().plusMinutes(9), OffsetDateTime.now().plusMinutes(11));
    }

    @Test
    @DisplayName("Si Cloudinary falla al borrar, no se propaga el error")
    void eliminarNoFalla() throws IOException {
        Cloudinary cloudinary = mock(Cloudinary.class);
        Uploader uploader = mock(Uploader.class);
        when(cloudinary.uploader()).thenReturn(uploader);
        when(uploader.destroy(anyString(), anyMap())).thenThrow(new IOException("sin red"));

        assertThatCode(() -> new ArchivoService(cloudinary).eliminar(
                "https://res.cloudinary.com/demo/image/upload/v1/odontosystem/consultorios/o1/f.jpg"))
                .doesNotThrowAnyException();
    }
}
