# Árbol de conversación — Chatbot OdontoSystem

Motor de reglas en Spring Boot (sin IA). Endpoint: `POST /api/chatbot/mensaje` (requiere JWT).
Provincia de Ica. JSON en snake_case.

## Petición

```json
{ "mensaje": "busco un dentista en Parcona", "sesion_id": "opcional", "canal": "WEB" }
```

Cuando el usuario pulsa un botón (una `opcion` de la respuesta anterior), se manda el texto del
botón como `mensaje` y además su `accion` y `valor`:

```json
{ "mensaje": "Dra. Ana Torres", "accion": "elegir_odontologo", "valor": "uuid-del-odontologo" }
```

## Respuesta

```json
{
  "sesion_id": "…",
  "tipo": "lista_horarios",
  "mensaje": "Turnos libres con Dra. Ana Torres para Resina estética el lunes 12/10. ¿Cuál prefieres?",
  "intencion": "AGENDAR_CITA",
  "datos": { … },
  "acciones": ["elegir_turno", "elegir_fecha"],
  "opciones": [
    { "etiqueta": "09:00", "accion": "elegir_turno", "valor": "2026-10-12T09:00-05:00" },
    { "etiqueta": "Otro día: mar. 13/10", "accion": "elegir_fecha", "valor": "2026-10-13" }
  ]
}
```

`tipo` define cómo dibujarla:

| tipo | datos |
|---|---|
| `texto_simple` | — |
| `lista_odontologos` | tarjetas: id, nombre, foto_url, consultorio, distrito, calificacion, total_resenas, especialidades, precio_desde |
| `lista_servicios` | servicios del odontólogo (id, titulo, precio_total, monto_deposito, duracion_minutos) |
| `lista_horarios` | disponibilidad del día (fecha, zona_horaria, turnos con inicio y fin) |
| `confirmacion` | resumen de lo que se va a reservar (odontólogo, servicio, inicio, precio, depósito) |
| `cita_reservada` | la cita creada (PENDIENTE_PAGO, pagar_antes_de…) |
| `lista_citas` | próximas citas del paciente (o agenda del odontólogo) |

`opciones` trae los botones ya armados. La acción `pagar_deposito` (valor = id de la cita) la
resuelve el frontend llevando al pago (`POST /api/citas/{id}/pagar`); el chat no cobra.

## Memoria de contexto

`sesiones_chat.contexto` (JSONB) guarda el paso en curso y lo que eligió el usuario. Por eso
estas respuestas cortas se entienden según lo que el bot acaba de preguntar:

| Paso | Ejemplos que entiende |
|---|---|
| ELEGIR_DISTRITO | "Parcona", "en la Tinguiña" |
| ELEGIR_ODONTOLOGO | "1", "el segundo", "la última" |
| ELEGIR_SERVICIO | "2", "la limpieza", "resina" |
| ELEGIR_TURNO | "a las 10", "10:30", "3 pm", "el 2", "¿y el lunes?", "mañana a las 9", "12/10" |
| CONFIRMAR | "sí", "confirmo", "no" (vuelve a los turnos) |
| cualquiera | "cancelar", "menú" (borra la memoria) · "mis citas" · "hola" · "mejor en Parcona" |

## Flujo 1: BUSCAR_ODONTOLOGO / AGENDAR_CITA

```
Usuario: "Busco un odontólogo" / "Quiero una cita"
Bot: "¿En qué distrito de Ica te gustaría atenderte? Hay odontólogos en: Ica Centro, Parcona…"
      [botón por cada distrito que tiene odontólogos]

Usuario: "Parcona"   (o "busco dentista en Parcona" desde el inicio)
Bot: "Encontré 3 odontólogos en Parcona: Elige uno para ver sus horarios."   (lista_odontologos)

Usuario: "1"   (o "agendar con la Dra. Torres" desde el inicio: busca por nombre)
Bot: "Dra. Ana Torres ofrece estos servicios. ¿Cuál necesitas?"   (lista_servicios; si tiene uno solo, se salta)

Usuario: "resina"
Bot: turnos del primer día con horario libre en las próximas 2 semanas   (lista_horarios)
     + botones "Otro día: …"

Usuario: "¿y el lunes?"  → turnos del lunes con el mismo odontólogo y servicio
Usuario: "a las 10"
Bot: "Vas a reservar Resina estética con Dra. Ana Torres el lunes 12/10 a las 10:00.
      Para asegurarla pagas un depósito de S/ 30.00 (precio total S/ 150.00). ¿Confirmas?"   (confirmacion)

Usuario: "sí"
Bot: CitaService.reservar → cita PENDIENTE_PAGO
     "¡Listo! Reservé tu cita… Para confirmarla paga el depósito de S/ 30.00 antes de las 10:15."   (cita_reservada)
     [Pagar depósito] [Ver mis citas]

SI el turno se ocupó justo antes (otro paciente):
Bot: "Ese horario se acaba de ocupar 😕. Te muestro los que siguen libres."   (lista_horarios)
```

Un odontólogo no agenda por chat: el bot le indica que use "Mi consultorio".

## Flujo 2: CONSULTAR_HISTORIAL ("mis citas")

```
Paciente con citas próximas:
Bot: "Tus próximas citas:
      • el lunes 12/10 a las 10:00 con Dra. Ana Torres (Resina estética) — falta pagar el depósito
      Tu última visita fue el lunes 3/08 con Dr. Luis Pérez."
Sin citas próximas y última visita hace 6+ meses: "…¿agendamos tu control?"
Sin citas: "Todavía no tienes citas. ¿Agendamos la primera?"
Odontólogo: su agenda de los próximos 7 días.
```

## Saludo ("hola")

```
Con cita próxima:        "¡Hola, Ana! … Recuerda que tienes una cita el viernes 15/10 a las 10:00 con Dr. Pérez."
Última visita 6+ meses:  "¡Hola, Ana! … Tu última visita fue hace 7 meses. ¿Agendamos tu control?"
Paciente nuevo:          "¡Hola, Ana! … ¿Buscas agendar tu primera cita?"
```

## Mensajes no reconocidos

```
Bot: "No entendí bien eso. Puedo ayudarte a:
      🔍 Buscar un odontólogo
      📅 Agendar una cita
      🕐 Ver tus citas"
```
Dentro de un flujo, en vez del menú vuelve a preguntar lo mismo sin perder lo elegido.
