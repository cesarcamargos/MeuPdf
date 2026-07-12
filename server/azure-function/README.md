# MeuPDF sync backend (Azure Function)

Thin API that the Android app calls. It authenticates with email + password and
issues short-lived Azure Blob **SAS** tokens so the app talks to Blob storage
directly — the Storage Account key never leaves the Function.

## Endpoints

| Method | Route        | Auth            | Returns |
|--------|--------------|-----------------|---------|
| POST   | `/api/login` | none            | `{ token, userId }` (JWT, 30d) |
| POST   | `/api/sas`   | `Bearer <jwt>`  | `{ sasUrl, expiresAt, userId }` (container SAS, 1h) |
| GET    | `/api/health`| none            | `{ ok: true }` |

## App settings (secrets — set in Azure, never commit)

- `STORAGE_CONNECTION_STRING` — connection string of the **data** Storage Account.
- `STORAGE_CONTAINER` — e.g. `meupdf`.
- `AUTH_EMAIL` — your login email.
- `AUTH_PASSWORD_HASH` — bcrypt hash of your password (see below).
- `JWT_SECRET` — a long random string used to sign tokens.

Generate the password hash:

```bash
npm install
npm run hash -- "yourPassword"   # prints the bcrypt hash -> AUTH_PASSWORD_HASH
```

## Run locally

```bash
npm install
cp local.settings.json.example local.settings.json   # fill in the values
npm run build
npm start            # requires Azure Functions Core Tools (func)
# test:
curl -X POST http://localhost:7071/api/login -d '{"email":"...","password":"..."}'
```

## Deploy

```bash
npm install && npm run build
func azure functionapp publish <seu-function-app>    # ex.: meupdf-func
```

Then set the app settings on the Function App (portal or `az functionapp config
appsettings set ...`, see docs/CLOUD_SYNC_PLAN.md section 6).

The base URL the app needs is `https://<seu-function-app>.azurewebsites.net`.
Put it in the Android build via `-PfunctionBaseUrl=...` or `local.properties`
(`meupdf.functionBaseUrl=...`).
