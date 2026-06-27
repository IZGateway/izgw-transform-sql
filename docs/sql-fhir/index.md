# IZ Gateway Transformation Service — SQL-Backed FHIR Interface

This module (`izgw-transform-sql`) adds two FHIR API surfaces that query an ANSI
SQL database directly rather than routing through the IZ Gateway Hub and IIS.

> **Availability:** These endpoints only exist when the service is built with the
> `sql-support` Maven profile (`mvn package -P sql-support,sql-mssql`) and deployed
> as `xform-service-sql-dev` (or equivalent). The standard APHL deployment image does
> not include them. Requests to `/sql/**` or `/bulk/sql/**` paths on a standard build
> return `503 Service Unavailable`.

---

## Relationship to the Standard FHIR Interface

The SQL-backed API is a companion to — not a replacement for — the standard
hub-routed FHIR interface documented in `izgw-transform`:

> **Standard FHIR interface** (hub-routed, always available):
> [`izgw-transform/docs/fhir/`](https://github.com/IZGateway/izgw-transform/tree/develop/docs/fhir/)
>
> - Endpoints: `GET /fhir/{destinationId}/Patient`, `Immunization`, `ImmunizationRecommendation`, etc.
> - Converts FHIR queries to HL7 V2 QBP, sends through IZ Gateway Hub, converts RSP response back to FHIR.
> - Supports all configured IIS destinations.

The SQL-backed interface follows the same FHIR R4 search conventions (parameters,
response format, mTLS authentication, roles) as the standard interface. The key
differences are the URL prefix and the data source:

| | Standard (hub-routed) | SQL-backed |
|---|---|---|
| Base URL | `/fhir/{destinationId}/` | `/sql/fhir/{name}/` |
| Bulk export | Not available | `/bulk/sql/fhir/$export` |
| Data source | IIS via IZ Gateway Hub | ANSI SQL database via JDBC |
| Backend config | Hub destination registration | `sql.backend.<name>` property |
| Image | Standard `transformation-service` | SQL-enabled `transformation-service-sql` |

---

## Documents

| Document | Description |
|---|---|
| [SQL FHIR API Reference](sql-fhir-api.md) | Single-patient query endpoints at `/sql/fhir/{name}/**` |
| [Bulk FHIR Export API](bulk-fhir-api.md) | Async `$export` endpoints at `/bulk/sql/fhir/$export` |

---

## How It Works

```
FHIR Client
    │  GET /sql/fhir/waiis/Patient?family=Smith&birthdate=1985-03-15
    ▼
SqlFhirController (izgw-transform-sql)
    │  stream-filters CSV fixture (dev) or queries SQL view (production)
    ▼
SqlPatientSearchService
    │  SELECT * FROM patient_view WHERE last_name = ? AND birth_date = ?
    │  IDIMatch scoring — singular match required
    ▼
SqlImmunizationRetrievalService
    │  SELECT * FROM immunization_view WHERE patient_id = ?
    ▼
TabularFhirConverter
    │  maps all_vax_event columns → FHIR Patient + Immunization
    │  using sql-mapping.yml (generated from all_vax_event_enriched_mapping.csv)
    ▼
FHIR Client receives Bundle (SearchSet)
```

For Bulk FHIR export the flow is:
```
FHIR Client  →  POST /bulk/sql/fhir/$export  →  202 Accepted + Content-Location
                     │
                     ▼
           BulkExportWorker (async)
                     │  SELECT * FROM immunization_view WHERE INSERT_STAMP >= :since
                     ▼
           NDJSON files (Patient + Immunization, chunked)
                     │
                     ▼
FHIR Client  →  GET /bulk/sql/fhir/$export-status/{jobId}  →  200 + manifest
FHIR Client  →  GET /bulk/sql/fhir/$export-files/{jobId}/{n}  →  NDJSON stream
FHIR Client  →  DELETE /bulk/sql/fhir/$export-status/{jobId}  →  202
```

---

## Authentication and Authorization

Same requirements as the standard interface:
- **mTLS** client certificate authentication
- Role **`XFORM_SENDING_SYSTEM`** or **`ADMIN`** required for single-patient queries
- Role **`XFORM_SENDING_SYSTEM`**, **`ADMIN`**, or **`BULK_EXPORT`** for bulk export

## Configuration

SQL backends are registered via Spring properties or environment variables:

```yaml
# application.yml — registers backend accessible at /sql/fhir/waiis/**
sql:
  backend:
    waiis: /configuration/waiis.yml
```

```
# or as environment variable
SQL_BACKEND_WAIIS=/configuration/waiis.yml
```

The built-in `dev` backend (`/sql/fhir/dev/**`) requires no configuration and loads
test data from CSV files at startup.

See [aws-sql-deployment.md](../aws-sql-deployment.md) for deployment configuration.
See [wa-doh-pilot/](../wa-doh-pilot/) for WA DOH schema reference materials.
