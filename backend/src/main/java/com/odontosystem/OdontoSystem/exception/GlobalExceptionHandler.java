package com.odontosystem.OdontoSystem.exception;

import com.odontosystem.OdontoSystem.dto.ErrorResponse;
import com.odontosystem.OdontoSystem.security.TokenInvalidoException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.OffsetDateTime;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(TokenInvalidoException.class)
    public ResponseEntity<ErrorResponse> manejarTokenInvalido(TokenInvalidoException ex, HttpServletRequest request) {
        return construirRespuesta(HttpStatus.UNAUTHORIZED, ex.getMessage(), request);
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> manejarCredencialesInvalidas(BadCredentialsException ex, HttpServletRequest request) {
        return construirRespuesta(HttpStatus.UNAUTHORIZED, "Correo o contraseña incorrectos", request);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> manejarResponseStatusException(ResponseStatusException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        return construirRespuesta(status, ex.getReason(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> manejarValidacion(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String mensaje = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return construirRespuesta(HttpStatus.BAD_REQUEST, mensaje, request);
    }

    // Ruta que no existe: 404 (antes caia en el manejador general y respondia 500).
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> manejarRutaInexistente(NoResourceFoundException ex, HttpServletRequest request) {
        return construirRespuesta(HttpStatus.NOT_FOUND, "Recurso no encontrado", request);
    }

    // Restricción de la BD (dato duplicado, cita que se cruza, regla de un trigger): 409 en lugar de 500
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> manejarConflictoDatos(DataIntegrityViolationException ex, HttpServletRequest request) {
        log.warn("Conflicto de datos en {} {}: {}", request.getMethod(), request.getRequestURI(),
                ex.getMostSpecificCause().getMessage());
        return construirRespuesta(HttpStatus.CONFLICT, "La operación entra en conflicto con datos existentes", request);
    }

    // ---- Subida de archivos (multipart) ----

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> manejarArchivoGrande(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        return construirRespuesta(HttpStatus.CONTENT_TOO_LARGE, "El archivo supera el tamaño máximo de 5 MB", request);
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ErrorResponse> manejarFaltaArchivo(MissingServletRequestPartException ex, HttpServletRequest request) {
        return construirRespuesta(HttpStatus.BAD_REQUEST, "Falta el campo '" + ex.getRequestPartName() + "'", request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> manejarFaltaParametro(MissingServletRequestParameterException ex, HttpServletRequest request) {
        return construirRespuesta(HttpStatus.BAD_REQUEST, "Falta el parámetro '" + ex.getParameterName() + "'", request);
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ErrorResponse> manejarMultipart(MultipartException ex, HttpServletRequest request) {
        return construirRespuesta(HttpStatus.BAD_REQUEST, "La petición debe enviarse como multipart/form-data", request);
    }

    // Ej.: tipo=PASAPORTE en un enum, o un UUID mal escrito en la ruta
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> manejarTipoInvalido(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        return construirRespuesta(HttpStatus.BAD_REQUEST, "Valor inválido para '" + ex.getName() + "'", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> manejarExcepcionGeneral(Exception ex, HttpServletRequest request) {
        // Al cliente no se le muestran detalles internos, pero el error completo queda en los logs
        // (en Railway: Deploy Logs). Sin esta linea los errores 500 no dejaban ningun rastro.
        log.error("Error inesperado en {} {}", request.getMethod(), request.getRequestURI(), ex);
        return construirRespuesta(HttpStatus.INTERNAL_SERVER_ERROR, "Ocurrió un error inesperado", request);
    }

    private ResponseEntity<ErrorResponse> construirRespuesta(HttpStatus status, String mensaje, HttpServletRequest request) {
        ErrorResponse error = new ErrorResponse(
                OffsetDateTime.now(),
                status.value(),
                status.getReasonPhrase(),
                mensaje,
                request.getRequestURI()
        );
        return ResponseEntity.status(status).body(error);
    }
}