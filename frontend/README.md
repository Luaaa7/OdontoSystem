# Frontend — OdontoSystem

Pendiente: inicializar proyecto React aquí (Nicole).

```bash
npm create vite@latest . -- --template react
npm install
npm install axios
```

## Estructura sugerida

```
frontend/
├── src/
│   ├── components/
│   │   ├── ChatWidget/          # widget del chatbot
│   │   ├── BuscadorOdontologos/
│   │   └── PerfilOdontologo/
│   ├── pages/
│   └── services/                # llamadas a la API (axios)
└── public/
```

El backend corre en `http://localhost:8080`. Los endpoints principales:

- `GET /api/odontologos?distrito=Ica`
- `GET /api/citas/disponibilidad?odontologoId=1&dia=2026-09-01`
- `POST /api/chatbot/mensaje` (pendiente, en desarrollo)
