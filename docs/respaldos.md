# Respaldos de la base de datos

El proyecto de Supabase está en el plan Free, que **no** incluye backups automáticos. Por eso el workflow
[`backup-db.yml`](../.github/workflows/backup-db.yml) saca uno por semana.

| | |
|---|---|
| Cuándo | Domingos 03:17 (hora de Lima), o a mano con **Actions → Respaldo de la base de datos → Run workflow** |
| Qué guarda | El esquema `public` completo: estructura, datos e historial de Flyway (una foto de ese momento) |
| Qué no guarda | Lo que cambie después del respaldo; archivos fuera de la base |
| Formato | `odontosystem_AAAA-MM-DD_HHMM.dump.gpg`: `pg_dump` formato custom, cifrado con AES-256 |
| Dónde | Artefacto de la ejecución en GitHub Actions; se borra a los 30 días (quedan ~4 respaldos) |
| Control | Cada ejecución verifica que la copia se puede leer y muestra las filas por tabla en su resumen |

## Configuración (una sola vez)

En GitHub: **Settings → Secrets and variables → Actions → New repository secret**

| Secreto | Valor |
|---|---|
| `SUPABASE_DB_URL` | La cadena de conexión de Supabase (**Connect → Session pooler**), con la contraseña real y `?sslmode=require` al final |
| `BACKUP_PASSPHRASE` | Una clave larga (20+ caracteres) solo para los respaldos |

**Guarda `BACKUP_PASSPHRASE` fuera de GitHub** (por ejemplo, en un gestor de contraseñas). GitHub no deja
volver a verla y sin ella ningún respaldo se puede abrir.

Si cambias la contraseña de la base en Supabase, actualiza `SUPABASE_DB_URL` o el próximo respaldo fallará.
GitHub avisa por correo cuando un workflow programado falla.

## Restaurar

Necesitas el cliente de PostgreSQL 17 (`pg_restore`) y `gpg` (viene con Git Bash).

1. **Descargar**: Actions → la ejecución del respaldo → *Artifacts* → descargar y descomprimir el `.zip`.
2. **Comprobar** que el archivo no se dañó (debe dar el mismo valor que el `.sha256`):
   ```bash
   sha256sum odontosystem_2026-11-08_0317.dump.gpg
   cat odontosystem_2026-11-08_0317.sha256
   ```
3. **Descifrar** (pide la `BACKUP_PASSPHRASE`):
   ```bash
   gpg --output odontosystem.dump --decrypt odontosystem_2026-11-08_0317.dump.gpg
   ```
4. **Ver qué contiene** sin tocar ninguna base:
   ```bash
   pg_restore --list odontosystem.dump | grep "TABLE DATA"
   ```
5. **Restaurar en una base nueva o vacía** (nunca directo sobre producción sin un respaldo de su estado actual).
   La base de destino necesita antes las extensiones que usa el esquema:
   ```sql
   CREATE EXTENSION IF NOT EXISTS pgcrypto;
   CREATE EXTENSION IF NOT EXISTS btree_gist;
   CREATE EXTENSION IF NOT EXISTS postgis;
   ```
   ```bash
   pg_restore --dbname "postgresql://...base-de-destino..." --no-owner --no-privileges odontosystem.dump
   ```
   Para recuperar una sola tabla: `pg_restore --data-only --table=liquidaciones ...`
6. Apuntar el backend a la base restaurada (`DB_USERNAME` / `DB_PASSWORD` / URL en Railway). Flyway verá que ya
   está en la última migración y no ejecutará nada.

Borra los archivos `.dump` descifrados cuando termines: tienen datos personales de pacientes (Ley N.º 29733).
