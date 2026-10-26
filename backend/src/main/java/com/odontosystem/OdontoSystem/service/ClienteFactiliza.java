package com.odontosystem.OdontoSystem.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;

/**
 * Consulta de DNI en RENIEC a través de Factiliza ({@code GET /v1/dni/info/{dni}} con {@code Authorization: Bearer}).
 * <ul>
 *   <li>Token: FACTILIZA_TOKEN (Railway / .env). Nunca sale del backend ni se escribe en los logs.</li>
 *   <li>Cupo: FACTILIZA_LIMITE_MENSUAL consultas por mes, llevado en {@link ControlConsumoApi}. Sin cupo no se llama.</li>
 *   <li>Solo se usa para confirmar la identidad de odontólogos OBSERVADO (el nombre del COP contra el de RENIEC).</li>
 * </ul>
 */
@Component
public class ClienteFactiliza {

    private static final Logger log = LoggerFactory.getLogger(ClienteFactiliza.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String SERVICIO = "FACTILIZA_DNI";

    public enum Estado { ENCONTRADO, NO_ENCONTRADO, SIN_CUPO, NO_DISPONIBLE, NO_CONFIGURADO }

    /** Datos de RENIEC que usamos (no se guarda la dirección ni otros datos personales). */
    public record PersonaReniec(String dni, String nombres, String apellidoPaterno, String apellidoMaterno) {
        /** "NOMBRES APELLIDO_PATERNO APELLIDO_MATERNO" para comparar con el COP. */
        public String nombreCompleto() {
            return String.join(" ", nombres, apellidoPaterno, apellidoMaterno).replaceAll("\\s+", " ").strip();
        }
    }

    public record ConsultaDni(Estado estado, PersonaReniec persona, String detalle) {}

    private final RestClient api;
    private final ControlConsumoApi consumo;
    private final boolean configurado;
    private final int limiteMensual;

    @Autowired
    public ClienteFactiliza(@Value("${app.factiliza.token:}") String token,
                            @Value("${app.factiliza.url:https://api.factiliza.com}") String url,
                            @Value("${app.factiliza.limite-mensual:100}") int limiteMensual,
                            ControlConsumoApi consumo) {
        this(token, RestClient.builder().baseUrl(url).requestFactory(conTiempos()), limiteMensual, consumo);
    }

    ClienteFactiliza(String token, RestClient.Builder builder, int limiteMensual, ControlConsumoApi consumo) {
        String t = token == null ? "" : token.strip();
        this.configurado = !t.isEmpty();
        this.limiteMensual = limiteMensual;
        this.consumo = consumo;
        this.api = builder.defaultHeader("Authorization", "Bearer " + t).build();
        if (!configurado) {
            log.info("Factiliza no está configurado (falta FACTILIZA_TOKEN): los OBSERVADO se revisan a mano");
        }
    }

    private static SimpleClientHttpRequestFactory conTiempos() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(5));
        f.setReadTimeout(Duration.ofSeconds(15));
        return f;
    }

    public boolean configurado() {
        return configurado;
    }

    /** Consultas usadas este mes y el límite (para logs y la colección de Postman). */
    public String resumenConsumo() {
        return consumo.usadas(SERVICIO) + "/" + limiteMensual;
    }

    /** Consulta un DNI. Gasta 1 consulta del cupo solo si Factiliza respondió. */
    public ConsultaDni consultarDni(String dni) {
        if (!configurado) {
            return new ConsultaDni(Estado.NO_CONFIGURADO, null, "Factiliza no está configurado");
        }
        if (dni == null || !dni.strip().matches("\\d{8}")) {
            return new ConsultaDni(Estado.NO_ENCONTRADO, null, "El DNI debe tener 8 dígitos");
        }
        if (!consumo.reservar(SERVICIO, limiteMensual)) {
            log.warn("Cupo mensual de Factiliza agotado ({} consultas)", limiteMensual);
            return new ConsultaDni(Estado.SIN_CUPO, null, "Se alcanzó el límite de consultas a RENIEC de este mes");
        }
        try {
            String respuesta = api.get().uri("/v1/dni/info/{dni}", dni.strip()).retrieve().body(String.class);
            return leer(respuesta);
        } catch (RestClientResponseException e) {
            int codigo = e.getStatusCode().value();
            if (codigo == 404 || codigo == 400 || codigo == 422) {
                return new ConsultaDni(Estado.NO_ENCONTRADO, null, "RENIEC no tiene ese DNI");
            }
            consumo.liberar(SERVICIO);
            String motivo = codigo == 401 || codigo == 403 ? "token de Factiliza inválido o vencido"
                    : codigo == 402 || codigo == 429 ? "Factiliza sin créditos o con demasiadas consultas"
                    : "Factiliza respondió " + codigo;
            log.warn("No se pudo consultar RENIEC: {}", motivo);
            return new ConsultaDni(Estado.NO_DISPONIBLE, null, motivo);
        } catch (ResourceAccessException e) {
            consumo.liberar(SERVICIO);
            log.warn("No se pudo conectar con Factiliza: {}", e.getMessage());
            return new ConsultaDni(Estado.NO_DISPONIBLE, null, "No se pudo conectar con Factiliza");
        }
    }

    static ConsultaDni leer(String json) {
        try {
            JsonNode n = JSON.readTree(json);
            JsonNode d = n.path("data");
            if (!n.path("success").asBoolean(false) || d.isMissingNode() || d.path("nombres").asText("").isBlank()) {
                return new ConsultaDni(Estado.NO_ENCONTRADO, null, texto(n.path("message"), "RENIEC no tiene ese DNI"));
            }
            return new ConsultaDni(Estado.ENCONTRADO, new PersonaReniec(d.path("numero").asText(""),
                    d.path("nombres").asText(""), d.path("apellido_paterno").asText(""),
                    d.path("apellido_materno").asText("")), "Encontrado en RENIEC");
        } catch (Exception e) {
            return new ConsultaDni(Estado.NO_DISPONIBLE, null, "Respuesta de Factiliza ilegible");
        }
    }

    private static String texto(JsonNode n, String porDefecto) {
        return n == null || n.isMissingNode() || n.isNull() || n.asText().isBlank() ? porDefecto : n.asText();
    }
}
