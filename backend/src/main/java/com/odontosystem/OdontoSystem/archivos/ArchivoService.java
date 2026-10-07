package com.odontosystem.OdontoSystem.archivos;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Único punto de contacto con Cloudinary.
 * <ul>
 *   <li>Imágenes públicas (foto de perfil, fotos del consultorio): tipo "upload", URL directa.</li>
 *   <li>Documentos privados (DNI, título, colegiatura, CV): tipo "authenticated"; solo se ven con
 *       un enlace firmado que vence a los 10 minutos.</li>
 * </ul>
 * El tipo de archivo se valida por su contenido real (magic bytes), no por la extensión ni el
 * Content-Type que envía el cliente, que se pueden falsificar.
 */
@Service
public class ArchivoService {

    private static final Logger log = LoggerFactory.getLogger(ArchivoService.class);

    static final String CARPETA_RAIZ = "odontosystem";
    static final long TAMANO_MAXIMO = 5L * 1024 * 1024;
    static final Duration VIGENCIA_ENLACE = Duration.ofMinutes(10);

    /** res.cloudinary.com/{cloud}/image/{upload|authenticated}/v123/{public_id}.{formato} */
    private static final Pattern URL_CLOUDINARY = Pattern.compile(
            "^https?://res\\.cloudinary\\.com/[^/]+/image/(upload|authenticated)/(?:v\\d+/)?(.+)\\.([A-Za-z0-9]+)$");

    public enum Formato {
        JPG("jpg"), PNG("png"), WEBP("webp"), PDF("pdf");

        private final String extension;

        Formato(String extension) {
            this.extension = extension;
        }

        public String extension() {
            return extension;
        }
    }

    public enum TipoArchivo {
        IMAGEN(EnumSet.of(Formato.JPG, Formato.PNG, Formato.WEBP), "JPG, PNG o WEBP"),
        DOCUMENTO(EnumSet.of(Formato.PDF, Formato.JPG, Formato.PNG), "PDF, JPG o PNG");

        private final Set<Formato> permitidos;
        private final String descripcion;

        TipoArchivo(Set<Formato> permitidos, String descripcion) {
            this.permitidos = permitidos;
            this.descripcion = descripcion;
        }
    }

    public record ArchivoSubido(String url, String publicId) {}

    public record EnlaceTemporal(String url, OffsetDateTime expiraEn) {}

    record ArchivoValidado(byte[] contenido, Formato formato) {}

    record Referencia(String tipo, String publicId, String formato) {}

    /** null cuando faltan las credenciales: la app arranca igual y la subida responde 503. */
    private final Cloudinary cloudinary;

    @Autowired
    public ArchivoService(@Value("${app.cloudinary.cloud-name:}") String cloudName,
                          @Value("${app.cloudinary.api-key:}") String apiKey,
                          @Value("${app.cloudinary.api-secret:}") String apiSecret) {
        this(crearCliente(cloudName, apiKey, apiSecret));
    }

    ArchivoService(Cloudinary cloudinary) {
        this.cloudinary = cloudinary;
    }

    private static Cloudinary crearCliente(String cloudName, String apiKey, String apiSecret) {
        if (cloudName.isBlank() || apiKey.isBlank() || apiSecret.isBlank()) {
            log.warn("Cloudinary no está configurado (CLOUDINARY_*): la subida de archivos responderá 503");
            return null;
        }
        return new Cloudinary(ObjectUtils.asMap(
                "cloud_name", cloudName,
                "api_key", apiKey,
                "api_secret", apiSecret,
                "secure", true));
    }

    /**
     * Sube una imagen pública. Con publicId fijo (ej. foto de perfil) la nueva reemplaza a la anterior;
     * sin publicId, Cloudinary genera un nombre único dentro de la carpeta.
     */
    public ArchivoSubido subirImagenPublica(MultipartFile archivo, String carpeta, String publicId) {
        ArchivoValidado validado = validar(archivo, TipoArchivo.IMAGEN);
        Map<String, Object> opciones = new HashMap<>();
        opciones.put("resource_type", "image");
        opciones.put("type", "upload");
        if (publicId != null) {
            opciones.put("public_id", CARPETA_RAIZ + "/" + carpeta + "/" + publicId);
            opciones.put("overwrite", true);
            opciones.put("invalidate", true);
        } else {
            opciones.put("folder", CARPETA_RAIZ + "/" + carpeta);
        }
        return subir(validado, opciones);
    }

    /** Sube un documento privado: no se puede abrir con la URL guardada, solo con {@link #enlaceTemporal}. */
    public ArchivoSubido subirDocumentoPrivado(MultipartFile archivo, String carpeta) {
        ArchivoValidado validado = validar(archivo, TipoArchivo.DOCUMENTO);
        Map<String, Object> opciones = new HashMap<>();
        opciones.put("resource_type", "image");
        opciones.put("type", "authenticated");
        opciones.put("folder", CARPETA_RAIZ + "/" + carpeta);
        return subir(validado, opciones);
    }

    /** Enlace firmado para ver un documento privado; vence a los 10 minutos. */
    public EnlaceTemporal enlaceTemporal(String url) {
        Referencia ref = referencia(url);
        if (ref == null) {
            throw new IllegalStateException("URL de Cloudinary no reconocida: " + url);
        }
        OffsetDateTime expiraEn = OffsetDateTime.now().plus(VIGENCIA_ENLACE);
        try {
            String enlace = cliente().privateDownload(ref.publicId(), ref.formato(), ObjectUtils.asMap(
                    "type", ref.tipo(),
                    "resource_type", "image",
                    "expires_at", expiraEn.toEpochSecond()));
            return new EnlaceTemporal(enlace, expiraEn);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("No se pudo generar el enlace temporal de {}", ref.publicId(), e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "No se pudo generar el enlace del documento");
        }
    }

    /**
     * Borra un archivo de Cloudinary. Si falla solo se registra en el log: un archivo huérfano
     * no debe impedir que el usuario termine su operación.
     */
    public void eliminar(String url) {
        Referencia ref = referencia(url);
        if (cloudinary == null || ref == null) {
            return;
        }
        try {
            cloudinary.uploader().destroy(ref.publicId(), ObjectUtils.asMap(
                    "type", ref.tipo(),
                    "resource_type", "image",
                    "invalidate", true));
        } catch (Exception e) {
            log.warn("No se pudo borrar {} de Cloudinary", ref.publicId(), e);
        }
    }

    private ArchivoSubido subir(ArchivoValidado validado, Map<String, Object> opciones) {
        Cloudinary cliente = cliente();
        try {
            Map<?, ?> resultado = cliente.uploader().upload(validado.contenido(), opciones);
            return new ArchivoSubido((String) resultado.get("secure_url"), (String) resultado.get("public_id"));
        } catch (IOException | RuntimeException e) {
            log.error("Error al subir archivo a Cloudinary", e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "No se pudo subir el archivo");
        }
    }

    private Cloudinary cliente() {
        if (cloudinary == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "La subida de archivos no está configurada");
        }
        return cloudinary;
    }

    // ------------------------------------------------------------------
    // Ayudantes (visibles en el paquete para las pruebas)
    // ------------------------------------------------------------------

    static ArchivoValidado validar(MultipartFile archivo, TipoArchivo tipo) {
        if (archivo == null || archivo.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El archivo está vacío");
        }
        if (archivo.getSize() > TAMANO_MAXIMO) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "El archivo supera el tamaño máximo de 5 MB");
        }
        byte[] contenido;
        try {
            contenido = archivo.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No se pudo leer el archivo");
        }
        Formato formato = detectarFormato(contenido);
        if (formato == null || !tipo.permitidos.contains(formato)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Formato no permitido: se acepta " + tipo.descripcion);
        }
        return new ArchivoValidado(contenido, formato);
    }

    static Formato detectarFormato(byte[] b) {
        if (b == null) return null;
        if (empiezaCon(b, 0, 0xFF, 0xD8, 0xFF)) return Formato.JPG;
        if (empiezaCon(b, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) return Formato.PNG;
        if (empiezaCon(b, 0, 'R', 'I', 'F', 'F') && empiezaCon(b, 8, 'W', 'E', 'B', 'P')) return Formato.WEBP;
        if (empiezaCon(b, 0, '%', 'P', 'D', 'F', '-')) return Formato.PDF;
        return null;
    }

    private static boolean empiezaCon(byte[] b, int desde, int... firma) {
        if (b.length < desde + firma.length) return false;
        for (int i = 0; i < firma.length; i++) {
            if ((b[desde + i] & 0xFF) != firma[i]) return false;
        }
        return true;
    }

    static Referencia referencia(String url) {
        if (url == null) return null;
        Matcher m = URL_CLOUDINARY.matcher(url);
        if (!m.matches()) return null;
        return new Referencia(m.group(1), m.group(2), m.group(3));
    }
}
