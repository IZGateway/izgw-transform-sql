# WA DOH Bulk FHIR Pilot — IZ Gateway Transformation CR Workspace

This folder contains reference materials for a Change Request (CR) to the
**IZ Gateway Transformation Service** to support the Washington State Department
of Health (WA DOH) Bulk FHIR Pilot.

The pilot, led by Public Health – Seattle & King County (PHSKC), enables
immunization investigators to retrieve vaccination histories for **multiple
patients in a single bulk query** using HL7® FHIR® $export, replacing a
time-consuming one-by-one manual lookup workflow in the Communicable Disease
Database (CDDB).

---

## Files

| File | Description |
|---|---|
| [All_Vax_Event_notebook_2026_05_27.txt](All_Vax_Event_notebook_2026_05_27.txt) | Databricks SQL notebook that builds the `all_vax_event` analytical table from the WAIIS (Washington Automated Immunization Information System) source tables |
| [all_vax_event_dictionary_2026_05_27.csv](all_vax_event_dictionary_2026_05_27.csv) | Published WA DOH data dictionary: 58 column names and Spark data types for the `all_vax_event` table |
| [all_vax_event_enriched_mapping.csv](all_vax_event_enriched_mapping.csv) | **Enriched field mapping** (generated): 61 fields with data types, categories, source expressions from the notebook, descriptions, and suggested FHIR resource/element mappings for the CR |
| `Bulk FHIR Presentation_FINAL.pptx` | Conference presentation describing the pilot architecture, workflow, and goals. Binary file — available in the shared OneDrive folder; not committed to this repository. |

---

## Source Dataset: `all_vax_event`

The `tc_iis_prod.analytic_tables.all_vax_event` table is the integration surface
between WAIIS and the IZ Gateway Transformation Service. It is produced by the
Databricks notebook via a series of subqueries and a main SELECT joining
approximately 15 source tables.

### Field Categories

| Category | Field Count | Description |
|---|---|---|
| Identifier | 6 | Synthetic and source system IDs |
| Patient | 10 | Demographics: name, DOB, gender, language, phone, email |
| PatientAddress | 6 | Current patient address (from address reserve with geocoding) |
| ATOAddress | 6 | Address **at time of vaccination** (from `ADDRESS_ATO_VAX`) |
| PatientRace | 6 | Multi-race pivot slots (ASIIS internal codes) |
| Vaccination | 17 | Vaccine product, dates, lot, route, site, series status |
| Facility | 12 | Administering facility/organization name, address, type, VFC PIN |
| Audit | 3 | Insert timestamp, registry entry date, deletion date |

### Fields in Notebook Not in Published Dictionary

Three fields appear in the notebook SELECT but are absent from the published
data dictionary (`all_vax_event_dictionary_2026_05_27.csv`):

| Field | Type | Source |
|---|---|---|
| `ANATOMICAL_SITE` | string | Mapped from `vm.anatomical_site` to HL7 body-site codes |
| `ANATOMICAL_ROUTE` | string | Mapped from `vm.anatomical_route` to NCI C-codes |
| `CC_VOLUME` | — | `vm.cc_volume` — dose volume in mL |
| `IWEB_VACC_EVENT_ID` | — | `vm.iweb_vacc_event_id` — legacy iWeb ID |
| `DELETION_DATE` | date | `vm.DELETION_DATE`; active records have NULL (filtered in view) |

---

## Architecture Summary (from Presentation)

```
WAIIS  →  Databricks (all_vax_event)  →  Rhapsody Transform  →  Aidbox FHIR Server
                                                                        ↓
                                               PHSKC  ←  OneHealthPort HIE
                                         (FHIR $match + $export)
```

Key FHIR operations:
- **`$match`** — patient identity matching; investigators verify candidates
- **`$export`** (Bulk FHIR) — cohort-level Patient + Immunization export

---

## CR Scope Notes

The enriched mapping CSV (`all_vax_event_enriched_mapping.csv`) provides a
starting point for the FHIR transformation spec. Key transformation areas for
the CR:

1. **Patient resource** — Name, DOB, gender, address (current + ATO), race
   (multi-race pivot → FHIR extension), ethnicity, language, telecom
2. **Immunization resource** — CVX/NDC vaccine codes, MVX manufacturer, lot,
   expiration, anatomical site/route, dose number, series complete, historical
   flag, VFC eligibility, facility reference
3. **Organization/Location resources** — Facility name, address, VFC PIN,
   facility type (HL7 table 0442)
4. **Code translations** — ASIIS race codes → OMB/CDC; gender → FHIR gender;
   facility type strings → HL7 numeric codes; anatomical route → NCI C-codes
5. **Identity** — `VACC_EVENT_ID` synthetic key strategy; patient MPI
   integration via `ASIIS_PAT_ID`

---

*Generated 2026-06-09 by GitHub Copilot CLI*
