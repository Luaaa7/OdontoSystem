package com.odontosystem.OdontoSystem.chatbot;

import com.odontosystem.OdontoSystem.entity.CanalChat;
import com.odontosystem.OdontoSystem.entity.EstadoChat;
import com.odontosystem.OdontoSystem.entity.EstadoVerificacionOdontologo;
import com.odontosystem.OdontoSystem.entity.MensajeChat;
import com.odontosystem.OdontoSystem.entity.PerfilOdontologo;
import com.odontosystem.OdontoSystem.entity.SesionChat;
import com.odontosystem.OdontoSystem.entity.TipoEmisorMensaje;
import com.odontosystem.OdontoSystem.entity.Usuario;
import com.odontosystem.OdontoSystem.repository.MensajeChatRepository;
import com.odontosystem.OdontoSystem.repository.PerfilOdontologoRepository;
import com.odontosystem.OdontoSystem.repository.SesionChatRepository;
import com.odontosystem.OdontoSystem.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias del servicio del chatbot: la base de datos se simula con Mockito,
 * así que corren en milisegundos y sin conexión a Supabase.
 */
@ExtendWith(MockitoExtension.class)
class ChatbotServiceTest {

    @Mock private SesionChatRepository sesionChatRepository;
    @Mock private MensajeChatRepository mensajeChatRepository;
    @Mock private UsuarioRepository usuarioRepository;
    @Mock private PerfilOdontologoRepository perfilOdontologoRepository;

    private ChatbotService servicio;
    private Usuario paciente;

    @BeforeEach
    void setUp() {
        // El detector es real: así también probamos que la intención llega bien al servicio.
        servicio = new ChatbotService(new IntentDetector(), new DistritoExtractor(),
                sesionChatRepository, mensajeChatRepository, usuarioRepository, perfilOdontologoRepository);
        paciente = Usuario.builder().id(UUID.randomUUID()).nombreCompleto("Paciente Prueba").build();
    }

    private SesionChat sesionDe(Usuario usuario) {
        return SesionChat.builder()
                .id(UUID.randomUUID())
                .usuario(usuario)
                .canal(CanalChat.WEB)
                .estado(EstadoChat.ACTIVA)
                .build();
    }

    private static MensajeChatRequest mensaje(String texto) {
        return new MensajeChatRequest(texto, null, null);
    }

    @Test
    @DisplayName("Sin conversación activa: crea una nueva y guarda los dos mensajes")
    void creaSesionNuevaYGuardaMensajes() {
        when(sesionChatRepository.findFirstByUsuarioIdAndEstadoOrderByActualizadoEnDesc(paciente.getId(), EstadoChat.ACTIVA))
                .thenReturn(Optional.empty());
        when(usuarioRepository.getReferenceById(paciente.getId())).thenReturn(paciente);
        when(sesionChatRepository.save(any(SesionChat.class))).thenAnswer(invocacion -> {
            SesionChat sesion = invocacion.getArgument(0);
            sesion.setId(UUID.randomUUID()); // simula el id que asigna Hibernate
            return sesion;
        });

        RespuestaChatbot respuesta = servicio.procesarMensaje(paciente.getId(), mensaje("hola"));

        assertThat(respuesta.sesionId()).isNotNull();
        assertThat(respuesta.intencion()).isEqualTo("SALUDO");
        assertThat(respuesta.tipo()).isEqualTo(RespuestaChatbot.TEXTO_SIMPLE);

        ArgumentCaptor<MensajeChat> captor = ArgumentCaptor.forClass(MensajeChat.class);
        verify(mensajeChatRepository, times(2)).save(captor.capture());
        List<MensajeChat> guardados = captor.getAllValues();

        assertThat(guardados.get(0).getTipoEmisor()).isEqualTo(TipoEmisorMensaje.USUARIO);
        assertThat(guardados.get(0).getTextoMensaje()).isEqualTo("hola");
        assertThat(guardados.get(0).getIntencionDetectada()).isEqualTo("SALUDO");
        assertThat(guardados.get(1).getTipoEmisor()).isEqualTo(TipoEmisorMensaje.BOT);
        assertThat(guardados.get(1).getTextoMensaje()).isEqualTo(respuesta.mensaje());
    }

    @Test
    @DisplayName("Si ya hay una conversación activa, la reutiliza")
    void reutilizaSesionActiva() {
        SesionChat activa = sesionDe(paciente);
        when(sesionChatRepository.findFirstByUsuarioIdAndEstadoOrderByActualizadoEnDesc(paciente.getId(), EstadoChat.ACTIVA))
                .thenReturn(Optional.of(activa));

        RespuestaChatbot respuesta = servicio.procesarMensaje(paciente.getId(), mensaje("busco un dentista"));

        assertThat(respuesta.sesionId()).isEqualTo(activa.getId());
        assertThat(respuesta.intencion()).isEqualTo("BUSCAR_ODONTOLOGO");
        verify(sesionChatRepository, never()).save(any());
        verify(usuarioRepository, never()).getReferenceById(any());
    }

    @Test
    @DisplayName("No permite escribir en la conversación de otro usuario (403)")
    void rechazaSesionAjena() {
        Usuario otro = Usuario.builder().id(UUID.randomUUID()).nombreCompleto("Otra Persona").build();
        SesionChat ajena = sesionDe(otro);
        when(sesionChatRepository.findById(ajena.getId())).thenReturn(Optional.of(ajena));

        MensajeChatRequest request = new MensajeChatRequest("hola", ajena.getId(), null);

        assertThatThrownBy(() -> servicio.procesarMensaje(paciente.getId(), request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(403));
        verify(mensajeChatRepository, never()).save(any());
    }

    @Test
    @DisplayName("Una conversación que no existe responde 404")
    void sesionInexistente() {
        UUID idInventado = UUID.randomUUID();
        when(sesionChatRepository.findById(idInventado)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.procesarMensaje(paciente.getId(), new MensajeChatRequest("hola", idInventado, null)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(404));
    }

    @Test
    @DisplayName("El saludo usa el primer nombre del paciente")
    void saludoConPrimerNombre() {
        when(sesionChatRepository.findFirstByUsuarioIdAndEstadoOrderByActualizadoEnDesc(paciente.getId(), EstadoChat.ACTIVA))
                .thenReturn(Optional.of(sesionDe(paciente)));

        RespuestaChatbot respuesta = servicio.procesarMensaje(paciente.getId(), mensaje("Buenos días"));

        assertThat(respuesta.mensaje()).startsWith("¡Hola, Paciente!");
    }

    @Test
    @DisplayName("Un mensaje no reconocido muestra el menú de ayuda")
    void desconocidaMuestraMenu() {
        when(sesionChatRepository.findFirstByUsuarioIdAndEstadoOrderByActualizadoEnDesc(paciente.getId(), EstadoChat.ACTIVA))
                .thenReturn(Optional.of(sesionDe(paciente)));

        RespuestaChatbot respuesta = servicio.procesarMensaje(paciente.getId(), mensaje("asdfgh"));

        assertThat(respuesta.intencion()).isEqualTo("DESCONOCIDA");
        assertThat(respuesta.acciones()).containsExactlyElementsOf(ChatbotService.ACCIONES_MENU);
    }

    @Test
    @DisplayName("El historial devuelve los mensajes de la conversación en orden")
    void historialEnOrden() {
        SesionChat sesion = sesionDe(paciente);
        when(sesionChatRepository.findById(sesion.getId())).thenReturn(Optional.of(sesion));
        OffsetDateTime t0 = OffsetDateTime.now();
        when(mensajeChatRepository.findBySesionIdOrderByCreadoEnAsc(sesion.getId())).thenReturn(List.of(
                MensajeChat.builder().sesion(sesion).tipoEmisor(TipoEmisorMensaje.USUARIO)
                        .textoMensaje("hola").intencionDetectada("SALUDO").creadoEn(t0).build(),
                MensajeChat.builder().sesion(sesion).tipoEmisor(TipoEmisorMensaje.BOT)
                        .textoMensaje("¡Hola, Paciente!").intencionDetectada("SALUDO").creadoEn(t0.plusSeconds(1)).build()
        ));

        List<MensajeChatResponse> historial = servicio.obtenerHistorial(paciente.getId(), sesion.getId());

        assertThat(historial).extracting(MensajeChatResponse::emisor)
                .containsExactly(TipoEmisorMensaje.USUARIO, TipoEmisorMensaje.BOT);
        assertThat(historial.get(0).texto()).isEqualTo("hola");
    }

    // ------------------------------------------------------------------ Fase 3a: distrito y contexto

    private PerfilOdontologo odontologo(String nombre, String distrito, String calificacion) {
        Usuario usuario = Usuario.builder().id(UUID.randomUUID()).nombreCompleto(nombre).build();
        return PerfilOdontologo.builder()
                .usuarioId(usuario.getId())
                .usuario(usuario)
                .numeroColegiatura("COP-" + nombre.hashCode())
                .nombreConsultorio("Consultorio " + nombre)
                .distritoConsultorio(distrito)
                .calificacionPromedio(new BigDecimal(calificacion))
                .estadoVerificacion(EstadoVerificacionOdontologo.VERIFICADO)
                .build();
    }

    private void conSesionActiva() {
        when(sesionChatRepository.findFirstByUsuarioIdAndEstadoOrderByActualizadoEnDesc(paciente.getId(), EstadoChat.ACTIVA))
                .thenReturn(Optional.of(sesionDe(paciente)));
    }

    private void conOdontologosVerificados(PerfilOdontologo... perfiles) {
        when(perfilOdontologoRepository.findByDesactivadoEnIsNullAndEstadoVerificacionAndDistritoConsultorioIsNotNull(
                EstadoVerificacionOdontologo.VERIFICADO)).thenReturn(List.of(perfiles));
    }

    @Test
    @DisplayName("Si el mensaje trae el distrito, busca directo y devuelve solo los de ese distrito")
    void buscaConDistritoEnElMensaje() {
        conSesionActiva();
        conOdontologosVerificados(
                odontologo("Ana Perez", "Parcona", "4.50"),
                odontologo("Luis Gomez", "La Tinguiña", "4.90"));

        RespuestaChatbot respuesta = servicio.procesarMensaje(paciente.getId(), mensaje("busco un dentista en Parcona"));

        assertThat(respuesta.tipo()).isEqualTo(RespuestaChatbot.LISTA_ODONTOLOGOS);
        assertThat(respuesta.mensaje()).isEqualTo("Encontré 1 odontólogo en Parcona:");
        assertThat(respuesta.acciones()).containsExactly("ver_disponibilidad");
        @SuppressWarnings("unchecked")
        List<OdontologoResumen> datos = (List<OdontologoResumen>) respuesta.datos();
        assertThat(datos).extracting(OdontologoResumen::nombre).containsExactly("Ana Perez");
    }

    @Test
    @DisplayName("Los resultados salen ordenados por calificación, la mejor primero")
    void ordenaPorCalificacion() {
        conSesionActiva();
        conOdontologosVerificados(
                odontologo("Regular", "parcona", "3.20"),
                odontologo("Excelente", "PARCONA", "4.80"));

        RespuestaChatbot respuesta = servicio.procesarMensaje(paciente.getId(), mensaje("dentistas en parcona"));

        @SuppressWarnings("unchecked")
        List<OdontologoResumen> datos = (List<OdontologoResumen>) respuesta.datos();
        assertThat(datos).extracting(OdontologoResumen::nombre).containsExactly("Excelente", "Regular");
    }

    @Test
    @DisplayName("Sin odontólogos en el distrito, lo dice y ofrece buscar en otro")
    void sinResultadosEnElDistrito() {
        conSesionActiva();
        conOdontologosVerificados(odontologo("Luis Gomez", "La Tinguiña", "4.90"));

        RespuestaChatbot respuesta = servicio.procesarMensaje(paciente.getId(), mensaje("busco odontólogo en Subtanjalla"));

        assertThat(respuesta.tipo()).isEqualTo(RespuestaChatbot.TEXTO_SIMPLE);
        assertThat(respuesta.mensaje()).contains("Subtanjalla");
        assertThat(respuesta.acciones()).containsExactly("elegir_distrito");
    }

    @Test
    @DisplayName("Memoria de contexto: si el bot preguntó el distrito, \"Parcona\" se entiende como respuesta")
    void recuerdaQueElBotPreguntoElDistrito() {
        SesionChat sesion = sesionDe(paciente);
        when(sesionChatRepository.findFirstByUsuarioIdAndEstadoOrderByActualizadoEnDesc(paciente.getId(), EstadoChat.ACTIVA))
                .thenReturn(Optional.of(sesion));
        when(mensajeChatRepository.findFirstBySesionIdAndTipoEmisorOrderByCreadoEnDesc(sesion.getId(), TipoEmisorMensaje.BOT))
                .thenReturn(Optional.of(MensajeChat.builder()
                        .tipoEmisor(TipoEmisorMensaje.BOT)
                        .textoMensaje("¿En qué distrito de Ica te gustaría atenderte?")
                        .intencionDetectada("BUSCAR_ODONTOLOGO")
                        .build()));
        conOdontologosVerificados(odontologo("Ana Perez", "Parcona", "4.50"));

        RespuestaChatbot respuesta = servicio.procesarMensaje(paciente.getId(), mensaje("Parcona"));

        assertThat(respuesta.intencion()).isEqualTo("BUSCAR_ODONTOLOGO");
        assertThat(respuesta.tipo()).isEqualTo(RespuestaChatbot.LISTA_ODONTOLOGOS);
    }

    @Test
    @DisplayName("Sin contexto previo, un distrito suelto no se adivina: muestra el menú")
    void distritoSueltoSinContexto() {
        conSesionActiva();

        RespuestaChatbot respuesta = servicio.procesarMensaje(paciente.getId(), mensaje("Parcona"));

        assertThat(respuesta.intencion()).isEqualTo("DESCONOCIDA");
        verify(perfilOdontologoRepository, never())
                .findByDesactivadoEnIsNullAndEstadoVerificacionAndDistritoConsultorioIsNotNull(any());
    }
}
