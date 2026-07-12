# Plano: Sincronização na nuvem (Azure Blob + Azure Function)

> **Status: código das Fases 1–5 implementado** (compila; APK monta). Falta você
> executar a **Fase 0 (provisionar no Azure)** e apontar o app para a Function.
> Ver seção 6 (provisionamento) e, ao final, "O que você precisa fazer".
> A Azure Function ainda **não** foi build-testada (não rodei `npm install`).

Substitui o desenho anterior baseado em Syncthing/SAF. Objetivo: **o próprio app**
sincroniza biblioteca (PDFs, capas, seções) e progresso de leitura (página,
`fontScale`, bookmarks) entre vários aparelhos, sem pasta local compartilhada.

Decisões já tomadas:
- **Arquivos** (PDF + capas) → **Azure Blob Storage** (contêiner privado).
- **Progresso/metadados** → **JSON no próprio Blob** (um sidecar por aparelho),
  reaproveitando o motor de merge que já existe. Sem Postgres/Neon por ora.
- **Autenticação** → **e-mail + senha** (conta única), validada por uma
  **Azure Function** fina que emite tokens e SAS. A chave da Storage Account
  **nunca** vai no APK.

---

## 1. Arquitetura

```
App Android ──POST /login (email+senha)──▶ Azure Function ──▶ devolve JWT
App Android ──POST /sas (Bearer JWT)─────▶ Azure Function ──▶ devolve SAS (TTL ~1h)
App Android ──usa o SAS──────────────────▶ Azure Blob (list/get/put direto)
```

- A Function **não** faz proxy dos PDFs (evita custo/lentidão). Ela só autentica e
  emite um **SAS de curta duração** com o qual o app fala direto com o Blob.
- O motor de sync atual (`ProgressStore.readMerged/upsertLocal`, identidade por
  `contentHash`, last-write-wins por aparelho) é reaproveitado; troca-se apenas o
  backend de armazenamento (`SyncedFolder`/SAF → `BlobStore`).

### Layout do contêiner Blob (`meupdf`, privado)

```
users/<userId>/
├── books/<contentHash>.pdf      ← bytes do PDF (identidade = SHA-256)
├── covers/<contentHash>.jpg     ← capa
└── progress/<deviceId>.json     ← 1 sidecar por aparelho (só aquele device escreve)
```

`<userId>` = derivado do e-mail (ex.: SHA-256 do e-mail em minúsculas), para o SAS
escopar tudo do usuário. Para uso pessoal, um contêiner só já basta.

### Esquema do `progress/<deviceId>.json` (expandido)

Agora carrega a **biblioteca inteira** (não só a página), com tombstones para
propagar exclusões e renomeações:

```json
{
  "version": 2,
  "deviceId": "a1b2c3d4",
  "sections": [
    { "syncId": "uuid", "name": "Aventura", "orderIndex": 0, "updatedAt": 0, "deleted": false }
  ],
  "books": {
    "<contentHash>": {
      "title": "Nome do livro",
      "sectionSyncId": "uuid-ou-null",
      "pageCount": 340,
      "lastPage": 12,
      "fontScale": 1.2,
      "bookmarks": [ { "page": 3, "label": "Cap 2" } ],
      "updatedAt": 1720800000000,
      "deleted": false
    }
  }
}
```

**Merge (client-side):** lê todos os `progress/*.json`, e para cada entidade
(seção por `syncId`, livro por `contentHash`) mantém a versão com maior
`updatedAt`. `deleted:true` vence se for o mais recente (tombstone).

---

## 2. Mudanças no modelo local (Room v2 → v3)

- `SectionEntity`: adicionar `syncId: String` (UUID gerado na criação) — identidade
  estável entre aparelhos. `id` Long continua sendo PK local.
- `BookEntity`: os campos `relativePath`/`uri` (SAF/legado) deixam de ser usados
  para resolução; passa a valer `contentHash` + download do Blob para cache. Manter
  as colunas por compatibilidade; adicionar `deleted: Boolean = false` +
  `sectionSyncId` derivável, ou resolver seção via join local (decidir na Fase 4).
- Migração `2 → 3`: `ALTER TABLE sections ADD COLUMN syncId TEXT`; popular os
  existentes com UUIDs.

---

## 3. Backend — Azure Function (Node.js/TypeScript, Functions v4)

Pacote: `@azure/storage-blob` para gerar SAS.

**App settings (segredos, no portal/CLI — nunca no repo):**
- `STORAGE_CONNECTION_STRING` — conexão da Storage Account.
- `STORAGE_CONTAINER` — ex.: `meupdf`.
- `AUTH_EMAIL` — seu e-mail.
- `AUTH_PASSWORD_HASH` — hash (bcrypt/scrypt) da senha (não a senha em texto).
- `JWT_SECRET` — segredo para assinar o JWT.

**Endpoints:**
- `POST /api/login` — body `{email, password}`. Valida contra `AUTH_EMAIL` +
  `AUTH_PASSWORD_HASH`. Devolve `{ token }` (JWT ~30 dias, claim `userId`).
- `POST /api/sas` — header `Authorization: Bearer <jwt>`. Valida o JWT e devolve
  `{ sasUrl, expiresAt }` — um **SAS de serviço** no contêiner com permissões
  `read, write, list, create, delete`, TTL ~1h, **HTTPS only**.
- `GET /api/health` — opcional, para diagnóstico.

> Para vários usuários no futuro: um contêiner por usuário (`meupdf-<userId>`) ou
> SAS escopado por prefixo via stored access policy. Para conta única, não precisa.

---

## 4. Cliente Android

Novo pacote `com.example.meupdf.cloud`:

- **HTTP client**: adicionar OkHttp (o app ainda não tem um). Usado para a Function
  e para o REST do Blob (com a SAS URL).
- `CloudPrefs` (DataStore): `email`, `jwt`, `userId`, `sasUrl`+`sasExpiry` (cache).
- `AuthApi`: `login(email, senha)` e `refreshSas()`. Renova o SAS quando expira.
- `BlobStore` (implementa a mesma interface que o `SyncedFolder` expõe hoje):
  - `listPdfHashes()` — `GET <container>?restype=container&comp=list&prefix=users/<u>/books/`.
  - `uploadPdf(hash, stream)` / `uploadCover` / `downloadPdf(hash) → File` (cache).
  - `readText(name)` / `writeText(name, text)` para os `progress/*.json`.
  - `resolveOpenUri(book)` → baixa `books/<hash>.pdf` para
    `cacheDir/pdfs/<hash>.pdf` (se ainda não estiver em cache) e devolve o `file://`.
- **Reuso**: `ProgressStore`/`SyncManager` re-apontados para o `BlobStore`. A lógica
  de merge/last-write-wins não muda.
- **Tela de login** (Compose): antes da biblioteca. App fica "travado" sem token
  válido. Logout limpa token + cache.
- **Cache local de PDFs**: `cacheDir/pdfs/<hash>.pdf`; baixa sob demanda ao abrir.
- **Background**: WorkManager para upload de novos imports (PDF+capa) e pull
  periódico dos sidecars; download do PDF é sob demanda ao abrir o livro.
- **Offline**: lê do cache; enfileira escritas de progresso; empurra ao voltar a
  ter rede + token válido.

### Aposentar o caminho SAF/Syncthing

- Remover: seletor de pasta (🗂), `SyncFolderPrompt`, `SyncedFolder` (SAF),
  `SyncPrefs.treeUri`. Manter `contentHash` (continua sendo a identidade) e
  `deviceId`.
- `importPdf` passa a: hashear → subir `books/<hash>.pdf` + capa para o Blob →
  registrar no Room → atualizar o sidecar → (WorkManager cuida do upload em
  background).

---

## 5. Fases de implementação

**Fase 0 — Provisionamento (você, no Azure).** Ver seção 6. Sem isso o resto não roda.

**Fase 1 — Azure Function.** login + sas + health, deployável. Testar com `curl`.

**Fase 2 — Auth no app.** OkHttp, `CloudPrefs`, `AuthApi`, tela de login, guarda de
token na navegação. Testar login real contra a Function.

**Fase 3 — `BlobStore` + cache.** Implementar o store sobre o Blob; re-apontar
`SyncManager`/`ProgressStore`; `resolveOpenUri` via download+cache. Testar abrir um
PDF que está só na nuvem.

**Fase 4 — Sync completo da biblioteca.** Expandir o JSON (seções+títulos+tombstones);
`syncId` nas seções (Room v3); merge; `importPdf` sobe PDF+capa+sidecar. Testar
ciclo A→B com dois aparelhos/emuladores.

**Fase 5 — Robustez.** WorkManager (upload/pull), fila offline, indicadores de
progresso de upload/download, migração da biblioteca local existente para a nuvem no
primeiro login, remoção do caminho SAF.

---

## 6. Passo a passo do provisionamento no Azure (Fase 0 — você)

> Pode ser pelo portal ou `az` CLI. Abaixo, a versão CLI resumida.

```bash
# 1. Grupo de recursos
az group create -n meupdf-rg -l brazilsouth

# 2. Storage Account + contêiner privado
az storage account create -n meupdfstore -g meupdf-rg -l brazilsouth --sku Standard_LRS
az storage container create --account-name meupdfstore -n meupdf --auth-mode login

# 3. Function App (consumo, Node 20)
az storage account create -n meupdffuncstore -g meupdf-rg -l brazilsouth --sku Standard_LRS
az functionapp create -g meupdf-rg -n meupdf-func \
  --storage-account meupdffuncstore --consumption-plan-location brazilsouth \
  --runtime node --runtime-version 20 --functions-version 4

# 4. Segredos nas app settings da Function
CONN=$(az storage account show-connection-string -n meupdfstore -g meupdf-rg -o tsv)
az functionapp config appsettings set -g meupdf-rg -n meupdf-func --settings \
  STORAGE_CONNECTION_STRING="$CONN" \
  STORAGE_CONTAINER="meupdf" \
  AUTH_EMAIL="voce@exemplo.com" \
  AUTH_PASSWORD_HASH="<hash bcrypt da sua senha>" \
  JWT_SECRET="<string aleatoria longa>"
```

**O que me passar depois de provisionar:**
- Nome da Storage Account e do contêiner.
- URL base da Function (ex.: `https://meupdf-func.azurewebsites.net`).
- (A senha em si você define; me passe só que já configurou o `AUTH_PASSWORD_HASH`.)

O `AUTH_PASSWORD_HASH` eu te dou um script pequeno para gerar (bcrypt) na Fase 1.

---

## 7. Segurança e custo

- Contêiner **privado** (sem acesso público). Acesso só via SAS de curta duração.
- SAS: **HTTPS only**, TTL ~1h, renovado pela Function sob demanda.
- Senha guardada como **hash** nas settings da Function (nunca texto puro; nunca no app).
- Egress: cada PDF é baixado **uma vez por aparelho** e fica em cache → custo baixo.
- Plano de consumo da Function + Standard_LRS: custo ~zero/centavos para uso pessoal.

---

## 8. Riscos / decisões em aberto

- **Relógio dos aparelhos**: `updatedAt` usa o relógio local; manter hora automática
  evita regressão de progresso. (Melhoria futura: contador lógico.)
- **Conflito de merge de seções**: renomear a mesma seção em dois aparelhos → vence o
  `updatedAt` maior. Aceitável para uso pessoal.
- **Multiusuário/compartilhamento**: fora do escopo agora; exigiria SAS por prefixo
  ou contêiner por usuário, e possivelmente o Neon para consultas no servidor.
