# Persistent PostgreSQL database

The app uses local file-based H2 by default for development. For a hosted deployment, use a managed PostgreSQL database so redeploying or restarting the web service does not erase accounts and rides.

## Neon setup

1. Create a Neon PostgreSQL project and database in a region near the Render web service.
2. Keep the existing local H2 database and `data/aadhaar-hmac.key` as a backup. Stop the local Mithila Go app before migrating; H2 locks an open database file.
3. In the Neon dashboard, get the connection details. Set these environment variables in the local PowerShell window used for the one-time migration:

   ```powershell
   $env:DB_URL = "jdbc:postgresql://HOST/DB_NAME?sslmode=require"
   $env:DB_USERNAME = "DATABASE_USER"
   $secureDbPassword = Read-Host "Neon database password" -AsSecureString
   $dbPasswordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureDbPassword)
   $env:DB_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($dbPasswordPointer)
   [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($dbPasswordPointer)
   Remove-Variable secureDbPassword
   $env:MIGRATION_SOURCE_URL = "jdbc:h2:file:./data/mithila-go;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0"
   $env:MIGRATION_SOURCE_USERNAME = "sa"
   $env:MIGRATION_SOURCE_PASSWORD = ""
   $env:MIGRATE_H2_TO_POSTGRES = "true"
   ```

   Replace the host, database name, user, and password with the values from Neon. Do not put credentials in source files or commit them.
4. Run the migration from the project directory:

   ```powershell
   .\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--spring.main.web-application-type=none"
   ```

   The command creates/updates the PostgreSQL schema, copies account, ride, offer, complaint, and notification rows in foreign-key order, and reports row counts only. It stops if the destination tables already contain data. Keep the source H2 files unchanged until you verify the migrated account and ride counts. Then clear the password from the current PowerShell session with `Remove-Item Env:DB_PASSWORD`.
5. In Render's web service environment, set `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` to the same Neon connection details. Set `MITHILA_AADHAAR_HMAC_KEY` to the existing value in `data/aadhaar-hmac.key` so existing Aadhaar-linked sign-ins continue to match. Enter this secret directly in Render; never commit it or send it in chat.
6. Disable `MIGRATE_H2_TO_POSTGRES` (or leave it unset) for the normal web service. Deploy and verify sign-in and ride history before retiring the local H2 database.

The included free Neon plan provides persistent database storage independent of Render web-service restarts. Its published limits are a six-hour history window and one manual snapshot; it does not include scheduled snapshots. For recovery from changes older than that window or provider incidents, enable a paid backup/restore plan and schedule independent backups. A database by itself is not a backup.

## Local development and tests

Without `DB_URL`, the app continues to use its local H2 database. Run tests with:

```powershell
.\mvnw.cmd test
```
