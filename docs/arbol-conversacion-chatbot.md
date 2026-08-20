# Árbol de conversación — Chatbot OdontoSystem

## Intent 1: BUSCAR_ODONTOLOGO

```
Usuario: "Busco un odontólogo" / "Quiero una cita" / "Odontólogos cerca"
Bot: "¿En qué distrito te gustaría atenderte?"
      [muestra lista de distritos disponibles como botones]

Usuario selecciona distrito (ej: "San Isidro")
Bot: consulta OdontologoService.buscarPorDistrito("San Isidro")

  SI hay resultados:
  Bot: "Encontré 3 odontólogos en San Isidro:"
        [Tarjeta 1: Dr. Pérez - Ortodoncia - ⭐4.8]
        [Tarjeta 2: Dra. Gómez - General - ⭐4.5]
        [Tarjeta 3: Dr. Ruiz - Endodoncia - ⭐4.9]
        Cada tarjeta con botón "Ver disponibilidad"

  SI NO hay resultados:
  Bot: "No encontré odontólogos registrados en ese distrito todavía.
        ¿Quieres ver distritos cercanos?"
```

## Intent 2: AGENDAR_CITA

```
Usuario: pulsa "Ver disponibilidad" en una tarjeta (o escribe "agendar con Dr. Pérez")
Bot: consulta DisponibilidadService.horariosDisponibles(idOdontologo)
Bot: "Estos son los horarios disponibles del Dr. Pérez esta semana:"
      [Lun 10am] [Lun 3pm] [Mar 9am] [Mié 11am]

Usuario selecciona horario
Bot: "Vas a agendar cita con Dr. Pérez el lunes 10am. ¿Confirmas?"
      [Confirmar] [Cancelar]

SI confirma:
Bot: llama a CitaService.crearCita(idPaciente, idOdontologo, fechaHora)
Bot: "✅ Cita confirmada. Te esperamos el lunes 10am con el Dr. Pérez."

SI el horario ya no está libre (carrera de concurrencia):
Bot: "Ese horario acaba de ocuparse. Aquí tienes otras opciones:" [refresca lista]
```

## Intent 3: CONSULTAR_HISTORIAL

```
Usuario inicia sesión / escribe "hola" / "mis citas"
Bot: consulta CitaService.ultimaCita(idPaciente) usando el JWT

  SI última cita fue hace más de 6 meses:
  Bot: "¡Hola! Veo que tu última visita fue hace 7 meses.
        ¿Quieres agendar tu control?"
        [Sí, agendar] [No, gracias]

  SI tiene cita próxima agendada:
  Bot: "Recuerda que tienes una cita el viernes 15
        con el Dr. Pérez a las 10am."

  SI es paciente nuevo (sin historial):
  Bot: "¡Hola! Soy el asistente de OdontoSystem.
        ¿Buscas agendar tu primera cita?"
```

## Manejo de mensajes no reconocidos

```
Bot: "No entendí bien eso. Puedo ayudarte a:
      🔍 Buscar un odontólogo
      📅 Agendar una cita
      🕐 Ver tu historial de citas"
```

## Formato JSON de respuesta

```json
{
  "tipo": "lista_odontologos",
  "mensaje": "Encontré 3 odontólogos en San Isidro:",
  "datos": [
    { "id": 12, "nombre": "Dr. Pérez", "especialidad": "Ortodoncia", "rating": 4.8 }
  ],
  "acciones": ["ver_disponibilidad"]
}
```

`tipo` define cómo el frontend renderiza: `texto_simple`, `lista_odontologos`, `lista_horarios`, `confirmacion`.
