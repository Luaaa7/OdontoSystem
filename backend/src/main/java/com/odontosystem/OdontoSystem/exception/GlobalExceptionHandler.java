package com.odontosystem.OdontoSystem.exception;

import com.odontosystem.OdontoSystem.dto.ErrorResponse;
import com.odontosystem.OdontoSystem.security.TokenInvalidoException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
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