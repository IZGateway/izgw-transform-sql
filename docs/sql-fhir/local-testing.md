# Running the SQL Backend Locally with Test Data

This guide walks a competent engineer through running the IZ Gateway SQL FHIR backend
in Docker against a local CSV file of immunization data, without any database or
cloud infrastructure.

## Prerequisites

- Docker Desktop (Windows, Mac, or Linux)
- A CSV extract in the `all_vax_event` column format (see [CSV Format](#csv-format) below)
- `openssl` on your PATH (standard on Mac/Linux; available via Git Bash or WSL on Windows)

---

## Step 1: Pull the SQL-Enabled Image

```bash
docker pull ghcr.io/izgateway/izgw-transform-sql:latest
```

---

## Step 2: Prepare Your Data Folder

Create a local folder to hold your CSV file and (optionally) a custom column mapping.

**Unix / Mac:**
```bash
mkdir -p ~/izgw-sql-test
```

**Windows (Command Prompt):**
```cmd
mkdir %USERPROFILE%\izgw-sql-test
```

**Windows (PowerShell):**
```powershell
New-Item -ItemType Directory -Force "$env:USERPROFILE\izgw-sql-test"
```

Copy your CSV extract into that folder. By default the service expects the file to be
named `all_vax_event.csv`. If your file has a different name, see
[Overriding the Filename](#overriding-the-filename).

---

## Step 3: Generate a Secret Key

The service validates requests using a JWT signed with a shared secret. Generate a
random 32-byte key and save it somewhere safe (treat it like a password).

**Unix / Mac / Git Bash:**
```bash
export XFORM_JWT_SECRET=$(openssl rand -base64 32)
echo $XFORM_JWT_SECRET   # save this value
```

**Windows (PowerShell):**
```powershell
$env:XFORM_JWT_SECRET = [Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Maximum 256 }))
Write-Host $env:XFORM_JWT_SECRET   # save this value
```

---

## Step 4: Generate Access Tokens

Use the built-in token generator to create JWTs signed with your secret.

**Unix / Mac:**
```bash
docker run --rm \
  -e XFORM_JWT_SECRET=$XFORM_JWT_SECRET \
  ghcr.io/izgateway/izgw-transform-sql:latest generate-token
```

**Windows (Command Prompt):**
```cmd
docker run --rm -e XFORM_JWT_SECRET=%XFORM_JWT_SECRET% ^
  ghcr.io/izgateway/izgw-transform-sql:latest generate-token
```

**Windows (PowerShell):**
```powershell
docker run --rm `
  -e XFORM_JWT_SECRET=$env:XFORM_JWT_SECRET `
  ghcr.io/izgateway/izgw-transform-sql:latest generate-token
```

The command prints two tokens, each valid for one hour:

- **Sender token** -- for patient queries and bulk export (`xform-sender` role)
- **Admin token** -- includes both `xform-sender` and `admin` roles

Copy the appropriate token for use in Step 6. To get fresh tokens after expiry, re-run
this command with the same `XFORM_JWT_SECRET`.

---

## Step 5: Run the Container

Mount your data folder to `/data` inside the container. The self-signed TLS certificate
is baked into the image and the service listens on port 444.

**Unix / Mac:**
```bash
docker run -d \
  --name izgw-sql-test \
  -p 444:444 \
  -e XFORM_JWT_SECRET=$XFORM_JWT_SECRET \
  -e COMMON_PASS=changeit \
  -e XFORM_CRYPTO_STORE_KEY_TOMCAT_SERVER_FILE=/ssl/local/server.bcfks \
  -e XFORM_CRYPTO_STORE_TRUST_TOMCAT_SERVER_FILE=/ssl/local/trust.bcfks \
  -e XFORM_CRYPTO_STORE_KEY_WS_CLIENT_FILE=/ssl/local/server.bcfks \
  -e XFORM_CRYPTO_STORE_TRUST_WS_CLIENT_FILE=/ssl/local/trust.bcfks \
  -e XFORM_CONFIGURATIONS_DIRECTORY=/usr/share/izg-transform/quickstart/configuration \
  -v ~/izgw-sql-test:/data \
  ghcr.io/izgateway/izgw-transform-sql:latest
```

**Windows (Command Prompt):**
```cmd
docker run -d ^
  --name izgw-sql-test ^
  -p 444:444 ^
  -e XFORM_JWT_SECRET=%XFORM_JWT_SECRET% ^
  -e COMMON_PASS=changeit ^
  -e XFORM_CRYPTO_STORE_KEY_TOMCAT_SERVER_FILE=/ssl/local/server.bcfks ^
  -e XFORM_CRYPTO_STORE_TRUST_TOMCAT_SERVER_FILE=/ssl/local/trust.bcfks ^
  -e XFORM_CRYPTO_STORE_KEY_WS_CLIENT_FILE=/ssl/local/server.bcfks ^
  -e XFORM_CRYPTO_STORE_TRUST_WS_CLIENT_FILE=/ssl/local/trust.bcfks ^
  -e XFORM_CONFIGURATIONS_DIRECTORY=/usr/share/izg-transform/quickstart/configuration ^
  -v %USERPROFILE%\izgw-sql-test:/data ^
  ghcr.io/izgateway/izgw-transform-sql:latest
```

**Windows (PowerShell):**
```powershell
docker run -d `
  --name izgw-sql-test `
  -p 444:444 `
  -e XFORM_JWT_SECRET=$env:XFORM_JWT_SECRET `
  -e COMMON_PASS=changeit `
  -e XFORM_CRYPTO_STORE_KEY_TOMCAT_SERVER_FILE=/ssl/local/server.bcfks `
  -e XFORM_CRYPTO_STORE_TRUST_TOMCAT_SERVER_FILE=/ssl/local/trust.bcfks `
  -e XFORM_CRYPTO_STORE_KEY_WS_CLIENT_FILE=/ssl/local/server.bcfks `
  -e XFORM_CRYPTO_STORE_TRUST_WS_CLIENT_FILE=/ssl/local/trust.bcfks `
  -e XFORM_CONFIGURATIONS_DIRECTORY=/usr/share/izg-transform/quickstart/configuration `
  -v "${env:USERPROFILE}\izgw-sql-test:/data" `
  ghcr.io/izgateway/izgw-transform-sql:latest
```

Wait a few seconds for startup, then check logs:

```bash
docker logs izgw-sql-test
```

Look for a line containing `Xform application loaded`.

> **Note:** The TLS certificate is self-signed (`CN=sql.xform.testing.local`). Browsers
> and curl will warn about it. Use the `-k` flag with curl (see Step 6) or
> `-SkipCertificateCheck` with PowerShell's `Invoke-RestMethod`.

---

## Step 6: Query the Test Endpoint

Substitute `<SENDER_TOKEN>` with the sender token printed in Step 4.

### Single-patient query

**Unix / Mac / Git Bash:**
```bash
curl -k \
  -H "Authorization: Bearer <SENDER_TOKEN>" \
  "https://localhost:444/sql/fhir/test/Patient?family=Smith&birthdate=1985-03-15&_format=json"
```

**Windows (Command Prompt):**
```cmd
curl -k ^
  -H "Authorization: Bearer <SENDER_TOKEN>" ^
  "https://localhost:444/sql/fhir/test/Patient?family=Smith&birthdate=1985-03-15&_format=json"
```

**Windows (PowerShell):**
```powershell
Invoke-RestMethod -SkipCertificateCheck `
  -Headers @{ Authorization = "Bearer <SENDER_TOKEN>" } `
  -Uri "https://localhost:444/sql/fhir/test/Patient?family=Smith&birthdate=1985-03-15&_format=json"
```

A matching patient returns a `searchset` Bundle containing a `Patient` resource and one
`Immunization` entry per vaccination event in your CSV. An unrecognised patient returns
an empty Bundle (`total: 0`).

### With temporal filtering

Add `_lastUpdated=ge2020-01-01` to restrict results to records created on or after
a given date. The filter applies to the column marked `is_last_updated: true` in your
mapping file.

```bash
curl -k \
  -H "Authorization: Bearer <SENDER_TOKEN>" \
  "https://localhost:444/sql/fhir/test/Patient?family=Smith&birthdate=1985-03-15&_lastUpdated=ge2020-01-01&_format=json"
```

---

## Overriding the Filename

If your CSV has a name other than `all_vax_event.csv`, pass the filename override as
an environment variable when running the container:

```bash
-e SQL_BACKENDS_TEST_DATA_PATH=/data/my_extract.csv
```

---

## Supplying a Custom Column Mapping

If your CSV uses different column names from the WA DOH defaults, provide a custom
`sql-mapping.yml` in your data folder and point the container at it:

```bash
-e SQL_BACKENDS_TEST_MAPPING_CONFIG_PATH=/data/sql-mapping.yml
```

See [sql-mapping.yml Format](#sql-mapping-yml-format) below for the structure.

---

## Running the Postman Collection

A ready-made Postman collection is in `testing/scripts/sql-local/` of the
`izgw-transform` repository:

- **Collection**: `sql-local-test.postman_collection.json` -- 10 tests (LT_01-LT_10)
- **Environment**: `localhost.sql-xform.postman_environment.json`

Before importing, fill in the environment variables:

| Variable | What to set |
|---|---|
| `jwt_sender_token` | Sender token printed by `generate-token` (Step 4) |
| `jwt_admin_token` | Admin token printed by `generate-token` (Step 4) |
| `test_patient_family` | Last name of a patient that exists in your CSV |
| `test_patient_birthdate` | Date of birth matching that patient (YYYY-MM-DD) |
| `test_last_updated_from` | `ge` + a date that your CSV has records after (e.g. `ge2020-01-01`) |

To run via Newman (from the `izgw-transform` repo root):

**Unix / Mac:**
```bash
newman run testing/scripts/sql-local/sql-local-test.postman_collection.json \
  --environment testing/scripts/sql-local/localhost.sql-xform.postman_environment.json \
  --insecure
```

**Windows:**
```cmd
newman run testing\scripts\sql-local\sql-local-test.postman_collection.json ^
  --environment testing\scripts\sql-local\localhost.sql-xform.postman_environment.json ^
  --insecure
```

The `--insecure` flag is required because the container uses a self-signed certificate.

---

## Stopping and Cleaning Up

```bash
docker stop izgw-sql-test
docker rm izgw-sql-test
```

---

## CSV Format

The `test` backend expects a single denormalized CSV file where each row represents
one vaccination event. A patient with multiple vaccinations appears on multiple rows,
with the same demographic columns repeated on each row.

The default column names match the WA DOH `all_vax_event` view. Key columns:

| Column | FHIR Mapping | Notes |
|---|---|---|
| `IIS Patient ID` | `Patient.identifier` | Unique patient identifier; used to group rows |
| `Name - Last` | `Patient.name.family` | Used for patient search |
| `Name - First` | `Patient.name.given` | |
| `Date of Birth` | `Patient.birthDate` | ISO date `YYYY-MM-DD` |
| `Gender` | `Patient.gender` | `M` or `F` |
| `IIS Vaccination Event ID` | `Immunization.identifier` | Unique per row |
| `Vaccine Type (CVX)` | `Immunization.vaccineCode` | CVX code |
| `Administration Date` | `Immunization.occurrenceDateTime` | |
| `Record Creation Date` | `meta.lastUpdated` | Used for `_lastUpdated` filtering |

The first row must be a header row with column names. Values may be quoted with `"`.

---

## sql-mapping.yml Format

```yaml
mappings:
  - column: "IIS Patient ID"
    resource: Patient
    path: identifier
    type: string
    system: "urn:oid:2.16.840.1.114222.4.1"

  - column: "Name - Last"
    resource: Patient
    path: name.family
    type: string

  - column: "Date of Birth"
    resource: Patient
    path: birthDate
    type: date

  - column: "Record Creation Date"
    resource: Patient
    path: meta.lastUpdated
    type: dateTime
    is_last_updated: true   # marks this column for _lastUpdated filtering

  - column: "Vaccine Type (CVX)"
    resource: Immunization
    path: vaccineCode.cvx
    type: code
  # ... additional mappings
```

The `is_last_updated: true` flag on exactly one Patient column and one Immunization
column enables server-side `_lastUpdated` filtering. If omitted, `_lastUpdated`
parameters are accepted but ignored.

---

## Environment Variable Reference

| Variable | Default | Description |
|---|---|---|
| `XFORM_JWT_SECRET` | *(required)* | Base64-encoded HMAC-SHA256 signing key |
| `COMMON_PASS` | *(required)* | Password for the built-in TLS keystore |
| `SQL_BACKENDS_TEST_DATA_PATH` | `/data/all_vax_event.csv` | Path to your CSV file inside the container |
| `SQL_BACKENDS_TEST_MAPPING_CONFIG_PATH` | *(classpath default)* | Path to a custom `sql-mapping.yml` |
| `XFORM_CRYPTO_STORE_KEY_TOMCAT_SERVER_FILE` | *(required)* | Server TLS keystore path |
| `XFORM_CRYPTO_STORE_TRUST_TOMCAT_SERVER_FILE` | *(required)* | Server trust store path |
