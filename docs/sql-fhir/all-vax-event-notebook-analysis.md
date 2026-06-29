# AllVaxEventTable Query Analysis

Source: `docs/sql-fhir/wa-doh-all-vax-event-notebook.txt`, lines 1-971

---

## Notebook Setup (lines 1-9)

```python
%run ../config_prod
sql(f"""USE CATALOG tc_iis_prod""")
sql(f""" USE SCHEMA data_integration_interoperability """)
```

The notebook begins by running `../config_prod`, a sibling notebook that defines Python
variables for all source table paths. Every `{iisPATIENT_MASTER}`,
`{iisPATIENT_RACE_RESERVE}`, etc. in the subsequent SQL is a Python f-string
interpolation resolved by config_prod. We do not have that notebook, so exact table
paths are unknown. The catalog is `tc_iis_prod`, schema is
`data_integration_interoperability`. The output table is
`tc_iis_prod.analytic_tables.all_vax_event` (different schema: `analytic_tables`).

---

## Subqueries (Cached Tables, lines 22-521)

These are computed first and stored in Spark's cache as lazy tables. They are the
inputs to the main AllVaxEventTable SELECT.

---

### MULTI_RACE (lines 22-46)

**Purpose:** Produces one row per patient with up to six race codes pivoted into
columns named `1` through `6`. Selects the most recently recorded race data.

```sql
CACHE LAZY TABLE MULTI_RACE
    SELECT DISTINCT *
    FROM (
        SELECT asiis_pat_id_ptr, pat_race,
               ROW_NUMBER() OVER(PARTITION BY asiis_pat_id_ptr ORDER BY pat_race) RECORDS
        FROM (
            SELECT DISTINCT rm.asiis_pat_id_ptr,
                   try_cast(rm.pat_race as int) as pat_race,
                   DENSE_RANK() OVER(PARTITION BY rm.asiis_pat_id_ptr
                       ORDER BY CAST(rm.update_stamp AS TIMESTAMP) DESC) RACERANK
            FROM {iisPATIENT_RACE_RESERVE} RM
        ) A
        WHERE RACERANK=1
    ) B
    PIVOT(MIN(PAT_RACE) FOR RECORDS IN (1,2,3,4,5,6))
```

**Step by step:**

1. **Inner query:** Reads all rows from PATIENT_RACE_RESERVE. `try_cast(rm.pat_race as
   int)` converts the race code to an integer; if it cannot be cast (e.g., if `pat_race`
   stores text like `"2106-3"`), the result is NULL and that row is silently dropped.
   `DENSE_RANK()` ranks records per patient by `update_stamp DESC` -- most recent gets
   rank 1. Ties in update_stamp get the same rank.

2. **WHERE RACERANK=1:** Keeps only the most recently updated race records.

3. **ROW_NUMBER():** Assigns sequence numbers 1-6 to the remaining rows **ordered by
   `pat_race` ascending** (the integer value). This is not a priority ordering -- it is
   alphabetical/numeric ordering of the race code integers. The patient's "first" race
   code (slot 1) is the one with the smallest integer value.

4. **PIVOT:** Transposes row numbers 1-6 into column names. `MIN(PAT_RACE)` takes the
   value for each slot; since each slot should have at most one value after DISTINCT,
   MIN just selects it.

**Critical implications:**
- `PAT_RACE1` is NOT the "primary" race -- it is the race with the lowest integer code
  for that patient. A patient who is White (code 1) and Asian (code 4) would have
  `PAT_RACE1=1`, `PAT_RACE2=4`.
- If `pat_race` stores non-integer values (OMB codes, text), `try_cast` returns NULL
  and those races are lost entirely.
- A patient with no race records, or with non-castable race values, produces no row in
  MULTI_RACE. The main query LEFT JOINs, so those patients get NULL for all PAT_RACE
  columns.
- Maximum 6 race codes per patient. A 7th code would be silently dropped (PIVOT only
  captures RECORDS IN (1,2,3,4,5,6)).

---

### PAT_ADDRESS (lines 56-134)

**Purpose:** Most recently updated address per patient, with ZIP and county normalized
to standard formats.

**Key logic:**
- Source: `iisADDRESS_RESERVE` (address change history table, not PATIENT_MASTER)
- Selects most recent by `ROW_NUMBER() ORDER BY update_stamp DESC, insert_stamp DESC, address_id`
- **Filters to records with non-null ZIP or non-null county code.** Patients whose
  most recent address has neither ZIP nor county are excluded from this subquery
  entirely. Those patients have NULL for all PAT_ADDRESS fields in the main output.
- ZIP normalization: handles leading-zero states (NJ, NY, MA, etc.) and territories
  (PR, VI). Takes leftmost 5 digits. ZIPs shorter than 5 and not in the special-case
  states produce NULL.
- County normalization: handles 4-digit FIPS that need a leading zero (certain states).
  Produces NULL for non-standard lengths.
- Falls back to `iisGEOCODING_ZIPCODES` for state and county FIPS when the address
  record lacks them.

**Implication for mapping:** PAT_ADDRESS_* fields can be NULL for patients with
incomplete address data. The ZIP and county are normalized but may still be NULL.

---

### FAC_ZIP (lines 144-195)

**Purpose:** Facility ZIP and county normalized to standard formats, with geocoded
city, state, and county FIPS as fallback.

**Key logic:** Same ZIP and county normalization as PAT_ADDRESS, applied to
FACILITY_MASTER. Joins to GEOCODING_ZIPCODES for city, state, and county fallback.

**Implication for mapping:** Provides the geocoded facility address fields
(FAC_ADDRESS_CITY, FAC_ADDRESS_COUNTY_CODE, FAC_ADDRESS_STATE, FAC_ADDRESS_ZIP).

---

### PAT_PHONE (lines 205-242)

**Purpose:** Most recently updated phone number per patient, from two sources.

```sql
UNION of:
  -- Source 1: PHONE_RESERVE
  SELECT asiis_pat_id_ptr,
         REGEXP_REPLACE(PHONE_NUMBER, '[^0-9A-Za-z]', '') AS address_phone,
         update_stamp
  FROM {iisPHONE_RESERVE}
  WHERE insert_stamp IS NOT NULL

  -- Source 2: PATIENT_RESERVE
  SELECT DISTINCT asiis_pat_id_ptr, address_phone, update_stamp
  FROM {iisPATIENT_RESERVE}
  WHERE address_phone IS NOT NULL AND LENGTH(address_phone) >= 10
```

**Key logic:**
- PHONE_RESERVE: Strips non-alphanumeric characters (`[^0-9A-Za-z]`) from the phone
  number. Accepts all records with a non-null insert_stamp.
- PATIENT_RESERVE: Takes `address_phone` as-is (no regex cleanup). Requires length
  >= 10 (filters short/invalid numbers).
- Combined, ranked by `update_stamp DESC` with SHA2 hash as deterministic tiebreaker.
  Selects record 1 per patient.

**Implication for mapping:** The phone number format varies by source. PHONE_RESERVE
numbers are digits/letters only (no dashes, spaces, parens). PATIENT_RESERVE numbers
are raw. PAT_ADDRESS_PHONE in the main output may be in either format.

---

### ADDRESS_ATO_VAX (lines 252-422)

**Purpose:** Address closest in time to each vaccination event -- "address at time of
vaccination."

**Structure:** Three UNION branches, each finding addresses near a different event type:

| Branch | Event source | Date field | EVENT_ADDRESS_ASSOCIATOR |
|---|---|---|---|
| 1 | VACCINATION_MASTER | VACC_DATE | `CONCAT(asiis_vacc_code, vacc_date)` |
| 2 | PATIENT_CONTRA_RESERVE | INSERT_STAMP | `CONCAT(asiis_vacc_code, INSERT_STAMP)` |
| 3 | PATIENT_VACCINE_DEFERRAL | DEFERRAL_DATE | `CONCAT(asiis_vacc_code, DEFERRAL_DATE)` |

**For each branch:** Joins ADDRESS_RESERVE to the event table. Filters to addresses
inserted before or very close to the event date (`datediff(event_date, address_insert) >= -1`
or `>= 0`). Ranks by shortest positive datediff (closest prior address).
`ROW_NUMBER() ORDER BY datediff ASC NULLS LAST` selects the closest address.

**Critical issue -- JOIN mismatch with main query:**

The main query joins ADDRESS_ATO_VAX with:
```sql
ON AAV.EVENT_ADDRESS_ASSOCIATOR = CONCAT(VM.ASIIS_VACC_CODE, VM.VACC_DATE)
```

This matches only Branch 1 (vaccination branch), where the associator is
`CONCAT(asiis_vacc_code, vacc_date)`. Branches 2 and 3 use INSERT_STAMP or
DEFERRAL_DATE in their associators, which will not match `VACC_DATE`. Branches 2 and 3
produce rows in ADDRESS_ATO_VAX that are never joined in the main query.

**Practical consequence:** ATO_PAT_ADDRESS fields are populated from vaccination-event
address matching only. Contraindication and deferral address matching in the subquery
is dead code -- it runs but its results are never used.

**Implication for mapping:** When the ATO address join finds no match (no address
within range, or no vaccination-branch record), all ATO fields are NULL and fall back
to `pma.county_fips`/`pma.address_zip`/`pma.state` via COALESCE. This fallback uses
PAT_MASTER data, not PAT_ADDRESS.

---

### PAT_MASTER (lines 432-515)

**Purpose:** Cleansed copy of PATIENT_MASTER with normalized ZIP and county, used as
a fallback when PAT_ADDRESS has no record.

**Key differences from PAT_ADDRESS:**
- Source is `iisPATIENT_MASTER` directly (not the change-history ADDRESS_RESERVE)
- Same ZIP/county normalization logic
- Also filtered to records with ZIP or county: patients with neither are excluded
- Includes demographic fields (gender, ethnicity, language) but these are not used as
  fallbacks in the main query -- the main query reads demographics directly from
  PATIENT_MASTER

**Implication:** `pma` (PAT_MASTER alias) appears in COALESCE fallbacks for county and
state in the main SELECT. It is a ZIP/county-normalized copy of PATIENT_MASTER's own
address fields, used only when PAT_ADDRESS (`pz`) is NULL.

---

## How to Read the Notebook Text Export

Every line in this file is prefixed with `-- MAGIC`. This is the Databricks notebook
text export format. It marks cell content. Strip `-- MAGIC ` and you have the actual
code. The entire block at lines 528-822 is one Python cell:

```python
spark.sql(f'''CREATE OR REPLACE TEMPORARY VIEW AllVaxEventTable AS
    select distinct ...
''')
```

Within that SQL triple-quoted string, `--` introduces SQL single-line comments exactly
as in any SQL dialect. `/* ... */` introduces SQL block comments.

**Distinguishing active code from comments:**

| File line looks like | Actual cell content | Status |
|---|---|---|
| `-- MAGIC     pm.pat_gender  PAT_GENDER` | `    pm.pat_gender  PAT_GENDER` | Active SQL |
| `-- MAGIC     --CASE` | `    --CASE` | SQL comment |
| `-- MAGIC     --'NO'  VACC_REFUSAL` | `    --'NO'  VACC_REFUSAL` | SQL comment |

---

## Setup (lines 522-526)

```sql
DROP TABLE IF EXISTS AllVaxEventTable
```

Drops any prior persistent table named `AllVaxEventTable` before recreating it as a
temporary view. The view itself is not persisted -- it is later queried into a cached
table `tbl` and then merged into `tc_iis_prod.analytic_tables.all_vax_event`.

---

## The Main SELECT (lines 529-822)

```sql
CREATE OR REPLACE TEMPORARY VIEW AllVaxEventTable AS
    SELECT DISTINCT ...
FROM ... WHERE vm.deletion_date IS NULL
```

`SELECT DISTINCT` deduplicates rows where every output column is identical. With 50+
columns this is expensive but prevents duplicate rows from the multiple LEFT JOINs
below.

---

## Output Columns, One by One

### VACC_EVENT_ID (lines 530-537)

```sql
REPLACE(CONCAT('WA', 'V',
    COALESCE(CAST(pm.asiis_pat_id AS STRING), ''),
    COALESCE(CAST(vm.asiis_vacc_code AS STRING), ''),
    COALESCE(CAST(LEFT(REPLACE(vm.vacc_date,'-',''),8) AS STRING), ''),
    COALESCE(CAST(vm.last_irms_sys_id_to_update AS STRING), ''),
    COALESCE(CAST(vf.family_code AS STRING), '')),
Char(9), '')  AS  VACC_EVENT_ID
```

**What it does:** Constructs a synthetic unique key by concatenating fixed literals
`"WA"` + `"V"` with five numeric fields (no separators between parts), then strips
any tab characters (Char(9)). `LEFT(REPLACE(vm.vacc_date,'-',''),8)` strips hyphens
from the date and takes the first 8 characters, yielding `YYYYMMDD`.

**Result type:** String. Example: `WAV1234567890208202001151001245678`.

**Implication for mapping:** This is a synthetic IIS-scoped identifier. The system
URI (`https://doh.wa.gov/NamingSystem/VACC_EVENT_ID` in the YAML) is appropriate since
WA DOH constructs this key. It is NOT an industry-standard identifier.

**Note:** No NULL protection on the concatenation itself -- if all COALESCE inputs were
null they produce empty strings, so the key degrades to `"WAV"` but never NULL.

---

### IRMS_SYS_ID (line 538)

```sql
CAST(i.irms_sys_id AS DOUBLE)  AS  IRMS_SYS_ID
```

**Source:** `iisIRMS.irms_sys_id`. IRMS is the Immunization Registry Management
System -- the reporting organization (e.g., an IRMS-registered clinic).

**Why DOUBLE:** The source column is apparently stored as a numeric type that Databricks
reads as a Long or BigInt; casting to DOUBLE produces a float string in CSV exports
(e.g., `"1001.0"`). `stripDoubleZero()` in the mapper normalizes this to `"1001"`.

**Implication for mapping:** This is an internal WA DOH facility-system identifier,
not a standard code. Mapped to `Organization.identifier` in the YAML.

---

### ASIIS_FAC_ID (line 539)

```sql
CAST(fm.asiis_fac_id AS DOUBLE)  AS  ASIIS_FAC_ID
```

**Source:** `iisFACILITY_MASTER.asiis_fac_id`. The ASIIS (Washington Automated
Immunization Information System) internal facility identifier.

**Why DOUBLE:** Same pattern as IRMS_SYS_ID. `stripDoubleZero()` normalizes.

**Implication for mapping:** Internal WA DOH facility ID, mapped to `Location.identifier`.

---

### Asiis_Vacc_Code (lines 540-541)

```sql
CAST(vm.asiis_vacc_code AS DOUBLE)  AS  Asiis_Vacc_Code
```

**Source:** `iisVACCINATION_MASTER.asiis_vacc_code`. The WAIIS internal vaccine code.
This is an IIS-internal code, NOT CVX. CVX comes from the ASIIS_VACC_CODE lookup table
as `BEST_CDC_CODE` below.

**Why DOUBLE:** Same pattern. After `stripDoubleZero()`, yields the integer string.

**Note:** Mixed-case column name `Asiis_Vacc_Code` (capital A, V, C) per the live
dictionary. Case-insensitive column matching in the mapper handles this.

**Implication for mapping:** Mapped to `Immunization.vaccineCode` with the WA DOH
NamingSystem URI. This is a secondary identifier -- `BEST_CDC_CODE` (CVX) is the
primary national code.

---

### Columns commented out here (lines 542-543)

```sql
-- 'D'   ExtractType
-- ''    PPRLGeneratedID
```

**VACC_REFUSAL (line 726):**
```sql
-- 'NO'  VACC_REFUSAL
```

These three columns were removed from the output. `ExtractType` would have been a
literal `'D'` (delta/differential extract indicator). `PPRLGeneratedID` would have been
an empty Privacy-Preserving Record Linkage identifier. `VACC_REFUSAL` would have been
the literal string `'NO'` for all records. None carry meaningful data and none are in
the live dictionary. Do not map these.

---

### ASIIS_PAT_ID (line 544)

```sql
CAST(pm.asiis_pat_id AS DOUBLE)  AS  ASIIS_PAT_ID
```

**Source:** `iisPATIENT_MASTER.asiis_pat_id`. The WAIIS internal patient identifier.

**Why DOUBLE:** Same pattern. `stripDoubleZero()` yields the integer string.

**Implication for mapping:** Primary patient identifier. Mapped to `Patient.identifier`
with system `https://doh.wa.gov/NamingSystem/ASIIS_PAT_ID`. This is the key used to
group multiple vaccination rows into a single Patient resource.

---

### PAT_FIRST_NAME, PAT_MIDDLE_NAME, PAT_LAST_NAME (lines 545-547)

```sql
pm.pat_first_name    PAT_FIRST_NAME,
pm.pat_middle_name   PAT_MIDDLE_NAME,
pm.pat_last_name     PAT_LAST_NAME,
```

**Source:** `iisPATIENT_MASTER`. Passed through as-is. No transformation.

**Implication for mapping:** Plain string pass-through. Map to `Patient.name.family`
(last) and `Patient.name.given` (first, middle).

---

### PAT_BIRTH_DATE (line 548)

```sql
to_date(pm.pat_birth_date,'yyyy-MM-dd')  AS  PAT_BIRTH_DATE
```

**What it does:** Parses the source column using ISO date format and returns a Spark
`DateType`. In CSV export this renders as `YYYY-MM-DD`.

**Implication for mapping:** Safe to use as FHIR `date` type directly.

---

### PAT_GENDER (line 549)

```sql
pm.pat_gender  AS  PAT_GENDER
```

**Source:** `iisPATIENT_MASTER.pat_gender`. Passed through unchanged. No transformation.

**Unknown values:** We do not know from this notebook what values PATIENT_MASTER stores
in `pat_gender`. WAIIS systems typically store `M`, `F`, or `U`. This must be confirmed
with WA DOH. The current YAML concept_map (M/F/U) is an assumption.

---

### PAT_ADDRESS_STREET1/2, PAT_ADDRESS_CITY (lines 550-552)

```sql
REPLACE(pz.address_street1, Char(9), '')  PAT_ADDRESS_STREET1,
REPLACE(pz.address_street2, Char(9), '')  PAT_ADDRESS_STREET2,
REPLACE(pz.address_city,    Char(9), '')  PAT_ADDRESS_CITY,
```

**Source:** `pat_address` cached subquery (alias `pz`), which selects the patient's
most recently updated address. `REPLACE(..., Char(9), '')` strips tab characters.

**Implication for mapping:** Cleaned string, map to `Patient.address` fields.

---

### PAT_COUNTY_CODE (line 553)

```sql
COALESCE(pz.county_fips, pma.county_fips)  AS  PAT_COUNTY_CODE
```

**What it does:** Prefers the county FIPS code from the current address subquery (`pz`);
falls back to the PAT_MASTER subquery (`pma`) if the address subquery found no record.

**Result:** A 5-digit FIPS county code (e.g., `"53033"` for King County, WA).

**Implication for mapping:** Map to `Patient.address.district`. This is a FIPS code,
not a county name. FHIR `district` is a string field with no code constraint.

---

### PAT_ADDRESS_STATE, PAT_ADDRESS_ZIP (lines 554-555)

```sql
COALESCE(pz.state,        pma.state)        AS  PAT_ADDRESS_STATE,
COALESCE(pz.address_zip,  pma.address_zip)  AS  PAT_ADDRESS_ZIP,
```

**Same COALESCE pattern:** Prefers current address subquery, falls back to PAT_MASTER.
ZIP is normalized to 5 digits by the address subquery.

---

### ATO_PAT_ADDRESS fields (lines 557-562)

```sql
REPLACE(AAV.ADDRESS_STREET1, CHAR(9),'')   ATO_PAT_ADDRESS_STREET1,
REPLACE(AAV.ADDRESS_STREET2, CHAR(9),'')   ATO_PAT_ADDRESS_STREET2,
REPLACE(AAV.ADDRESS_CITY,    CHAR(9),'')   ATO_PAT_ADDRESS_CITY,
COALESCE(AAV.county_fips,  pma.county_fips)  ATO_PAT_COUNTY_CODE,
COALESCE(AAV.state,        pma.state)        ATO_PAT_ADDRESS_STATE,
COALESCE(AAV.address_zip,  pma.address_zip)  ATO_PAT_ADDRESS_ZIP,
```

**Source:** `ADDRESS_ATO_VAX` cached subquery (alias `AAV`). This subquery finds the
address record closest in time to the vaccination date -- "address at time of
vaccination" -- using `datediff(v.vacc_date, A.insert_stamp) >= -1` and
`ROW_NUMBER() ... ORDER BY datediff ASC`.

**Implication for mapping:** These are per-vaccination-event historical addresses, not
the patient's current address. They cannot be correctly placed on `Patient.address`
without a `use` code (`old`, `temp`) and without a mechanism to associate them with the
specific Immunization row. Currently deferred from the mapping. Requires a design
decision (Immunization extension? Patient.address with use=old? Omit?).

---

### RecipientRace1-6 -- COMMENTED OUT (lines 563-628)

```sql
--CASE
    --WHEN mr.`1` = '5' THEN '1002-5'
    --WHEN mr.`1` = '4' THEN '2028-9'
    --WHEN mr.`1` = '7' THEN '2076-8'
    --WHEN mr.`1` = '2' THEN '2054-5'
    --WHEN mr.`1` = '1' THEN '2106-3'
    --WHEN mr.`1` = '6' THEN '2131-1'
    --WHEN mr.`1` = '8' THEN '2131-1'
    --WHEN mr.`1` = '9' THEN 'UNK'
    --ELSE 'UNK'
--  END  RecipientRace1,
```

Repeated for slots 2-6 (with `--ELSE NULL` for slots 2-6 vs. `--ELSE 'UNK'` for
slot 1).

**What this would have done:** Mapped WAIIS integer race codes to CDC OMB race codes:

| WAIIS integer | CDC OMB code | OMB description |
|---|---|---|
| 1 | 2106-3 | White |
| 2 | 2054-5 | Black or African American |
| 4 | 2028-9 | Asian |
| 5 | 1002-5 | American Indian or Alaska Native |
| 6 | 2131-1 | Some Other Race |
| 7 | 2076-8 | Native Hawaiian or Other Pacific Islander |
| 8 | 2131-1 | Some Other Race |
| 9 | UNK | Unknown |

**Why commented out:** Unknown -- could be a performance decision, a data quality
concern, or a choice to defer mapping to the consuming application. The column names
would have been `RecipientRace1`-`RecipientRace6` (different from the active
`PAT_RACE1`-`PAT_RACE6`).

**Key implication:** The active `PAT_RACE1`-`PAT_RACE6` columns (lines 750-755)
contain the raw WAIIS integer codes (1-9 etc.), NOT OMB codes. The table above shows
what those integers mean IF we trust the commented-out mapping. Whether to apply that
mapping in our layer, or ask WA DOH to restore it in Databricks, is an open question.
The integers are output of `try_cast(rm.pat_race as int)` from the MULTI_RACE subquery.

---

### PAT_ETHNICITY_CODE (line 629)

```sql
CAST(pm.pat_ethnicity_code AS string)  AS  PAT_ETHNICITY_CODE
```

**Source:** `iisPATIENT_MASTER.pat_ethnicity_code`. Cast to string, otherwise passed
through unchanged.

**Unknown values:** We do not know what values PATIENT_MASTER stores. No transformation
is applied. It could be HL7 v2 HL70189 codes (H=Hispanic, N=Non-Hispanic, U=Unknown),
WAIIS integer codes, or something else. Must confirm with WA DOH before mapping.

---

### VACC_DATE (line 630)

```sql
to_date(vm.vacc_date, 'yyyy-MM-dd')  AS  VACC_DATE
```

**Source:** `iisVACCINATION_MASTER.vacc_date`. Parsed and formatted as ISO date.

**Result:** `YYYY-MM-DD` string in CSV. Safe to use as FHIR `date` or `dateTime`.

---

### BEST_CDC_CODE (line 631)

```sql
CAST(vc.best_cdc_code AS DOUBLE)  AS  BEST_CDC_CODE
```

**Source:** `iisASIIS_VACC_CODE.best_cdc_code` (alias `vc`), joined on
`vc.asiis_vacc_code = vm.asiis_vacc_code`. This lookup table translates the WAIIS
internal vaccine code to the "best" CDC/CVX code for that vaccine.

**Why DOUBLE:** Same pattern. `stripDoubleZero()` yields integer string like `"208"`.

**Implication for mapping:** This IS the standard CVX code. Map to
`Immunization.vaccineCode` with system `http://hl7.org/fhir/sid/cvx`. It is a LEFT
JOIN so this field is NULL when no CVX code is available for the vaccine.

---

### NDC_CODE (line 632)

```sql
m.ndc_number  AS  NDC_CODE
```

**Source:** `iisVACCINE_PRODUCT_MAPPING.ndc_number` (alias `m`), joined via lot number.
The JOIN condition `AND M.IS_USE_NDC IS NOT NULL` means only mappings flagged for NDC
use are selected.

**Result:** Raw NDC string. Format varies (may or may not be normalized to 11-digit
NDC format). Passed through as-is.

**Implication for mapping:** Map to `Immunization.vaccineCode` with system
`http://hl7.org/fhir/sid/ndc`. NULL when no lot-based NDC mapping exists.

---

### MANU_CODE (line 633)

```sql
vm.manu_code  AS  MANU_CODE
```

**Source:** `iisVACCINATION_MASTER.manu_code`. Passed through unchanged.

**Unknown values:** The WAIIS `manu_code` is likely an MVX code (HL7 manufacturer code
for vaccines), but this is not confirmed by the notebook. Must verify with WA DOH.

**Implication for mapping:** Mapped to `Immunization.manufacturer` via MVX system
`http://terminology.hl7.org/CodeSystem/mvx`. If `manu_code` is not MVX, the system URI
is wrong.

---

### LOT_NUM (line 634)

```sql
REPLACE(vm.lot_num, Char(9), '')  AS  LOT_NUM
```

**Source:** `iisVACCINATION_MASTER.lot_num`. Tabs stripped. Otherwise raw string.

**Implication for mapping:** Map to `Immunization.lotNumber`. Free-text string field
in FHIR.

---

### EXPIRATION_DATE (line 635)

```sql
to_date(ln.expiration_date,'yyyy-MM-dd')  AS  EXPIRATION_DATE
```

**Source:** `iisLOT_NUMBER.expiration_date` (alias `ln`), joined on lot number code.
Parsed as ISO date.

**Implication for mapping:** Map to `Immunization.expirationDate`. NULL if no lot
number record.

---

### ANATOMICAL_SITE (lines 636-650) -- ACTIVE CASE expression

```sql
CASE
    WHEN vm.anatomical_site = 'LEFT_THIGH'    THEN 'LT'
    WHEN vm.anatomical_site = 'LEFT_ARM'      THEN 'LA'
    WHEN vm.anatomical_site = 'LD'            THEN 'LD'
    WHEN vm.anatomical_site = 'LEFT_GLUTEUS'  THEN 'LG'
    WHEN vm.anatomical_site = 'LVL'           THEN 'LVL'
    WHEN vm.anatomical_site = 'LLFA'          THEN 'LLFA'
    WHEN vm.anatomical_site = 'RIGHT_THIGH'   THEN 'RT'
    WHEN vm.anatomical_site = 'RIGHT_ARM'     THEN 'RA'
    WHEN vm.anatomical_site = 'RD'            THEN 'RD'
    WHEN vm.anatomical_site = 'RIGHT_GLUTEUS' THEN 'RG'
    WHEN vm.anatomical_site = 'RVL'           THEN 'RVL'
    WHEN vm.anatomical_site = 'RLFA'          THEN 'RLFA'
    ELSE NULL
END  AS  ANATOMICAL_SITE
```

**What it does:** Maps WAIIS internal body-site strings to abbreviated codes. Inputs
not in the list produce NULL (not 'UNK').

**Notable:** `LD` and `RD` pass through unchanged (they are already abbreviated). `LVL`,
`LLFA`, `RVL`, `RLFA` also pass through. Mixed-format source data.

**Result values:** LT, LA, LD, LG, LVL, LLFA, RT, RA, RD, RG, RVL, RLFA, or NULL.

**Are these HL7 v2 Table 0163 codes?** HL7 v2 Table 0163 (Body site) includes LA, LD,
LG, LT, LVL, RA, RD, RG, RT, RVL. LLFA and RLFA are "lower left/right forearm" and
may be local extensions. The system `http://terminology.hl7.org/CodeSystem/v2-0163`
in the YAML is the correct base but LLFA/RLFA may not be standard codes.

**Implication for mapping:** Values are pre-mapped by Databricks. No concept_map needed
in the YAML. System `http://terminology.hl7.org/CodeSystem/v2-0163`.

---

### ANATOMICAL_ROUTE (lines 651-663) -- ACTIVE CASE expression

```sql
CASE
    WHEN vm.anatomical_route = 'INTRAMUSCULAR'  THEN 'C28161'
    WHEN vm.anatomical_route = 'INTRADERMAL'    THEN 'C38238'
    WHEN vm.anatomical_route = 'INTRANASAL'     THEN 'C38284'
    WHEN vm.anatomical_route = 'INTRAVENOUS'    THEN 'C38276'
    WHEN vm.anatomical_route = 'IV'             THEN 'C38276'
    WHEN vm.anatomical_route = 'ORAL'           THEN 'C38288'
    WHEN vm.anatomical_route = 'SUBCATANEOUS'   THEN 'C38299'  -- note: typo in source
    WHEN vm.anatomical_route = 'TRANSDERMAL'    THEN 'C38305'
    WHEN vm.anatomical_route IN ('C28161','C38238','C38284',
                                 'C38276','C38288','C38299','C38305')
        THEN vm.anatomical_route   -- already an NCI code, pass through
    ELSE NULL
END  AS  ANATOMICAL_ROUTE
```

**What it does:** Maps WAIIS internal route strings to NCI Thesaurus C-codes. The
last WHEN clause handles data that is already in C-code form (pass-through). `SUBCATANEOUS`
is a typo in the source data (should be `SUBCUTANEOUS`); Databricks matches the typo.

**NCI code meanings:**
| C-code | Route |
|---|---|
| C28161 | Intramuscular |
| C38238 | Intradermal |
| C38284 | Intranasal |
| C38276 | Intravenous |
| C38288 | Oral |
| C38299 | Subcutaneous |
| C38305 | Transdermal |

**Result values:** C-codes or NULL. Values not matching any WHEN produce NULL, not
'UNK'.

**Implication for mapping:** Values are pre-mapped. No concept_map needed. System
`http://ncimeta.nci.nih.gov`.

---

### DOSE_NUMBER (lines 664-668) -- ACTIVE CASE expression

```sql
CASE
    WHEN ( vv.valid_vacc = 'N' OR iv.invalid_reason_code IS NOT NULL ) THEN 'INV'
    WHEN ( vv.valid_vacc IS NULL OR vv.dose_number IS NULL )           THEN 'UNK'
    ELSE CAST(vv.dose_number AS STRING)
END  AS  DOSE_NUMBER
```

**Sources:** `iisVALID_VACCINATION` (alias `vv`) and `iisINVALID_VACCINATION` (alias
`iv`). Both are LEFT JOINs on patient + vaccine code + date + family code.

**Logic:**
1. If the vaccination is marked invalid (`vv.valid_vacc = 'N'`) OR there is an invalid
   reason code: output `'INV'`
2. Else if no valid vaccination record exists OR dose number is unknown: output `'UNK'`
3. Otherwise: the numeric dose number as a string (`'1'`, `'2'`, etc.)

**Implication for mapping:** Passed to `Immunization.protocolApplied.doseNumber[x]`
as a `StringType`. 'INV' and 'UNK' are not numeric -- they are status indicators. FHIR
R4 `doseNumber[x]` accepts string, so this is technically valid but semantically odd.
Consider whether 'INV' should affect `Immunization.status` instead.

---

### SERIES_COMPLETE (lines 669-674) -- ACTIVE CASE expression

```sql
CASE
    WHEN ( vv.series_complete = 'Y' OR iv.series_complete = 'Y' ) THEN 'YES'
    WHEN ( vv.series_complete = 'N' OR iv.series_complete = 'N' ) THEN 'NO'
    WHEN ( vv.series_complete IS NULL AND iv.series_complete IS NULL ) THEN 'UNK'
    ELSE 'UNK'
END  AS  SERIES_COMPLETE
```

**Logic:** Checks both valid and invalid vaccination records for series completion flag.
Either 'YES', 'NO', or 'UNK' -- never NULL.

**Implication for mapping:** Currently mapped to
`Immunization.protocolApplied.seriesDoses[x]` as StringType. 'YES'/'NO'/'UNK' are
not a series dose count. This mapping is semantically wrong for `seriesDoses`. FHIR
R4 has no direct field for "series complete?" on Immunization. Options: extension, or
omit until R5 (`completionStatus` element exists there).

---

### FAMILY_CODE (line 675)

```sql
vf.family_code  AS  FAMILY_CODE
```

**Source:** `iisVACCINE_FAMILY.family_code` (alias `vf`), joined on `asiis_vacc_code`.
This is the STC ImmuCast internal vaccine family grouping code (an integer, e.g., 5 =
MMR, 10 = Influenza). See ImmuCast documentation for the full table.

**Implication for mapping:** Passed through as whatever type Spark reads it as (likely
integer, but no CAST AS DOUBLE here). Map to `Immunization.vaccineCode` with the STC
documentation URI as system. Confirms there is no DOUBLE cast issue for FAMILY_CODE.

---

### ORG_NAME (line 676)

```sql
COALESCE(Replace(i.NAME, Char(9), ''), Replace(fm.NAME, Char(9), ''))  AS  ORG_NAME
```

**Logic:** Prefers the IRMS organization name; falls back to the facility name. Tabs
stripped from both.

**Implication for mapping:** Organization-level name (reporting org). Currently mapped
to `Organization.name`.

---

### FAC_NAME (line 677)

```sql
COALESCE(Replace(fm.NAME, Char(9), ''), Replace(i.NAME, Char(9), ''))  AS  FAC_NAME
```

**Logic:** Inverse of ORG_NAME -- prefers facility name, falls back to IRMS org name.

**Implication for mapping:** Facility-level name. Mapped to `Location.name`.

---

### VFC_PIN (line 678)

```sql
COALESCE(fm.pin, i.pin)  AS  VFC_PIN
```

**Source:** VFC (Vaccines for Children) program PIN for the facility or org. Prefers
facility PIN, falls back to IRMS PIN.

**Implication for mapping:** String identifier. Map to `Organization.identifier` with
system `https://doh.wa.gov/NamingSystem/VFC_PIN`.

---

### PROVIDER_FACILITY_TYPE (lines 679-719) -- ACTIVE CASE expression

Maps `fm.provider_facility_type` (a WAIIS internal enum string) to HL7 v2 Table 0442
numeric codes:

| WAIIS value | HL7 v2-0442 code | Meaning |
|---|---|---|
| PRIVATE_PRIVATE_HOSPITAL | 9 | Hospital |
| PRIVATE_PRIVATE_PRACTICE_SOLO_GROUP_HMO | 12 | Military Clinic |
| PRIVATE_COMMUNITY_HEALTH_CENTER | 3 | Community Health Clinic |
| PRIVATE_PHARMACY | 17 | Pharmacy |
| PRIVATE_SCHOOL_BASED_CLINIC | 1 | Ambulatory Health Care Facility |
| PRIVATE_TEEN_HEALTH_CENTER / PRIVATE_ADOLESCENT_ONLY_PROVIDER | 16 | |
| PUBLIC_PUBLIC_HEALTH_DEPARTMENT_CLINIC | 19 | Off Campus-Outpatient Hospital |
| PUBLIC_FQHC_RCH_COMMUNITY_MIGRANT_RURAL | 20 | Urgent Care |
| PUBLIC_TRIBAL_INDIAN_HEALTH_SERVICES_CLINIC | 11 | Indian Health Service Free-Standing |
| PUBLIC_STD_HIV | 6 | Psychiatric Hospital |
| PUBLIC_JUVENILE_DETENTION_CENTER / PUBLIC_CORRECTIONAL_FACILITY | 2 | |
| PUBLIC_MIGRANT_HEALTH_FACILITY / PUBLIC_REFUGEE_HEALTH_FACILITY | 4 | |
| PUBLIC_SCHOOL_BASED_CLINIC | 7 | |
| OTHER / Head Start / Child Care / School types | 28 | |
| PRIVATE | UNK | |
| Any unmatched | UNK | |

**Result values:** Numeric string ('9', '12', etc.) or `'UNK'`. Never NULL.

**Implication for mapping:** Values are pre-mapped. Map to `Location.type` with system
`http://terminology.hl7.org/CodeSystem/v2-0442`. No concept_map needed in YAML.

---

### FAC_ADDRESS fields (lines 720-725)

```sql
Replace(fm.address_street1, Char(9), '')             FAC_ADDRESS_STREET1,
substr(Replace(fm.address_street2, Char(9), ''),1,25) FAC_ADDRESS_STREET2,
fz.city                                               FAC_ADDRESS_CITY,
fz.county_fips                                        FAC_ADDRESS_COUNTY_CODE,
fz.state                                              FAC_ADDRESS_STATE,
fz.zip                                                FAC_ADDRESS_ZIP,
```

**Sources:** street lines from `iisFACILITY_MASTER`; city/county/state/zip from the
`fac_zip` cached subquery which joins facility ZIP to geocoding data.

**Note:** `FAC_ADDRESS_STREET2` is truncated to 25 characters. FHIR has no length limit
on address fields.

---

### COMORBIDITY_STATUS, SEROLOGY columns -- COMMENTED OUT (lines 726-743)

```sql
-- 'NO'  VACC_REFUSAL
-- CASE ... END  COMORBIDITY_STATUS
-- CASE ... END  SEROLOGY_RESULTS
-- to_date(...)  SEROLOG_DRAW_DATE
-- CAST(...)     SEROLOGY_TYPE_CODE
-- CAST(...)     SEROLOGY_RESULT_ID
```

These columns were removed from the output. COMORBIDITY_STATUS uses a `/* ... */`
block comment in addition to `--` line comments. None appear in the live dictionary.
The related SEROLOGY_RESERVE table JOIN is also commented out. Do not map these.

---

### HISTORICAL (line 744)

```sql
vm.historical  AS  HISTORICAL
```

**Source:** `iisVACCINATION_MASTER.historical`. Passed through unchanged. No
transformation applied.

**Unknown values:** The notebook gives no indication of what values are stored in this
column. Typical WAIIS IIS systems store 'Y'/'N', `true`/`false`, or `1`/`0`. The
current YAML concept_map (N=administered, Y=historical) is an assumption and must be
confirmed with WA DOH.

**Implication for FHIR:** This maps to `Immunization.primarySource` (true if
administered at this facility, false if historical/reported-by-parent). The boolean
sense of the flag must be confirmed.

---

### INSERT_STAMP (line 745)

```sql
vm.insert_stamp  AS  INSERT_STAMP
```

**Source:** `iisVACCINATION_MASTER.insert_stamp`. Raw timestamp, no transformation.

**Format in CSV:** Databricks will write this as a timestamp string. Format depends
on Spark CSV writer settings. Typically `YYYY-MM-DD HH:MM:SS.SSS` or ISO 8601.

**Implication for mapping:** This is the `_lastUpdated` anchor for temporal filtering.
Mapped to `Immunization.recorded` and marked `is_last_updated: true` in the YAML.

---

### REGISTRY_ENTRY_STAMP (line 746)

```sql
to_date(vr.registry_entry_stamp,'yyyy-MM-dd')  AS  REGISTRY_ENTRY_STAMP
```

**Source:** `iisVACCINATION_RESERVE.registry_entry_stamp`. Date when the vaccination
was entered into the registry. NULL if no VACCINATION_RESERVE record exists (LEFT
JOIN).

**Implication for mapping:** Currently mapped to `Immunization.recorded` as `date`.
Conflicts with INSERT_STAMP mapping to the same path. Only one should win; INSERT_STAMP
(timestamp) is higher fidelity and should take precedence.

---

### REPORTING_METHOD (line 747)

```sql
vr.reporting_method  AS  REPORTING_METHOD
```

**Source:** `iisVACCINATION_RESERVE.reporting_method`. Passed through unchanged.
NULL if no reserve record.

**Unknown values:** Not defined in the notebook. Could be free text or a coded value.

**Implication for mapping:** Mapped to `Immunization.reportOrigin.text` (free text
field on a CodeableConcept).

---

### VFC_ELIGIBLE (line 748)

```sql
CAST(vr.vfc_eligible AS DOUBLE)  AS  VFC_ELIGIBLE
```

**Source:** `iisVACCINATION_RESERVE.vfc_eligible`. Cast to DOUBLE. After
`stripDoubleZero()`, yields `"0"` or `"1"`.

**Implication for mapping:** The YAML maps `"1"` → `"public"` and `"0"` → `"private"`
against `Immunization.fundingSource` using HL7 immunization-funding-source codes. This
is a simplification: VFC eligibility indicates public-program funding for eligible
patients. Not all `vfc_eligible=0` cases are purely private pay.

---

### CC_VOLUME (line 749)

```sql
vm.cc_volume  AS  CC_VOLUME
```

**Source:** `iisVACCINATION_MASTER.cc_volume`. Passed through as-is. Presumably a
dose volume in cubic centimeters (milliliters).

**Note:** This column is in the notebook SELECT but absent from the 2026-05-27 live
dictionary -- it may have been dropped from the table.

**Implication for mapping:** If present, map to `Immunization.doseQuantity` with unit
`mL`.

---

### PAT_RACE1-6 (lines 750-755) -- ACTIVE columns

```sql
mr.`1` AS PAT_RACE1,
mr.`2` AS PAT_RACE2,
mr.`3` AS PAT_RACE3,
mr.`4` AS PAT_RACE4,
mr.`5` AS PAT_RACE5,
mr.`6` AS PAT_RACE6,
```

**Source:** `multi_race` cached subquery (alias `mr`). The MULTI_RACE subquery:
1. Reads `{iisPATIENT_RACE_RESERVE}` and does `try_cast(rm.pat_race as int) as pat_race`
2. Ranks records by most recent `update_stamp`
3. Keeps only the most recent rank (`RACERANK=1`)
4. Pivots up to 6 race codes per patient into columns named `1`, `2`, `3`, `4`, `5`, `6`

The PIVOT column numbers are ROW_NUMBER values (1st race, 2nd race, etc.), ordered by
`pat_race` value ascending. The VALUES in those columns are the `pat_race` integers.

**Result:** Columns contain integers like `1`, `2`, `4`, `5`, etc. -- or NULL for
slots with no data.

**Relationship to commented-out RecipientRace1-6:** The commented-out block (lines
563-628) shows the intended mapping from these integers to CDC OMB codes. Because it
was commented out, the mapping is NOT done by Databricks. The active columns pass raw
integers.

**The mapping table** (from the commented-out section, trusting it as authoritative):
- 1 = White (2106-3)
- 2 = Black or African American (2054-5)
- 4 = Asian (2028-9)
- 5 = American Indian or Alaska Native (1002-5)
- 6 = Some Other Race (2131-1)
- 7 = Native Hawaiian or Other Pacific Islander (2076-8)
- 8 = Some Other Race (2131-1)
- 9 = Unknown

Note there is no code `3` in the mapping. Code `3` (if present in data) would produce
NULL under the commented-out CASE. This may indicate code `3` does not appear in
WAIIS, or it maps to something not captured in the commented-out draft.

**Implication for mapping:** If we want conformant US Core race extensions, we must
apply this mapping in our layer (since Databricks did not). Deferred pending WA DOH
confirmation.

---

### PAT_LANGUAGE (line 756)

```sql
pm.pat_language  AS  PAT_LANGUAGE
```

**Source:** `iisPATIENT_MASTER.pat_language`. Passed through unchanged.

**Unknown values:** Could be BCP-47 language tags (`en`, `es-US`), HL7 v2 codes, or
free text. Must confirm with WA DOH.

**Implication for mapping:** Mapped to `Patient.communication.language` with system
`urn:ietf:bcp:47`. If values are not BCP-47 tags, the system URI is wrong.

---

### PAT_ADDRESS_PHONE (line 757)

```sql
pl.address_phone  AS  PAT_ADDRESS_PHONE
```

**Source:** `PAT_PHONE` cached subquery (alias `pl`), which extracts the most recently
updated phone number using `REGEXP_REPLACE(PHONE_NUMBER, '[^0-9A-Za-z]', '')` (strips
non-alphanumeric characters). Selects the most recent record per patient.

**Result:** Digits only (and A-Z, which may indicate extensions). No formatting.

**Implication for mapping:** Map to `Patient.telecom` with `system=phone`.

---

### PAT_ADDRESS_EMAIL (line 758)

```sql
pm.address_email  AS  PAT_ADDRESS_EMAIL
```

**Source:** `iisPATIENT_MASTER.address_email`. Passed through unchanged.

**Implication for mapping:** Map to `Patient.telecom` with `system=email`.

---

### IWEB_VACC_EVENT_ID (line 759)

```sql
vm.iweb_vacc_event_id  AS  IWEB_VACC_EVENT_ID
```

**Source:** `iisVACCINATION_MASTER.iweb_vacc_event_id`. Passed through. This is the
IWeb (legacy immunization web system) vaccination event ID -- a secondary identifier
for vaccination events that originated in IWeb.

**Note:** Present in the notebook SELECT but absent from the 2026-05-27 live dictionary.
May have been dropped from the table or may appear only when IWeb-sourced records exist.

---

### REFRESH_DATE -- COMMENTED OUT (line 760)

```sql
-- to_date(CURRENT_TIMESTAMP,'yyyy-MM-dd')  REFRESH_DATE
```

Would have been the date the view was run. Removed from output.

---

### DELETION_DATE (line 761)

```sql
to_date(vm.DELETION_DATE,'yyyy-MM-dd')  AS  DELETION_DATE
```

**Source:** `iisVACCINATION_MASTER.DELETION_DATE`. Parsed as ISO date.

**Critical note:** The WHERE clause at line 820 filters `vm.deletion_date IS NULL`.
This means every row in the output has `deletion_date IS NULL`, so this column is
ALWAYS NULL in the exported data. It is included for schema completeness but carries
no data.

**Implication for mapping:** No mapping needed. The current YAML entry maps it to
`Immunization.status` as a date, which is doubly wrong (wrong type, always null).
Remove this mapping.

---

## FROM / JOIN Clauses (lines 763-821)

### Primary JOIN (lines 763-765) -- INNER JOIN

```sql
FROM {iisPATIENT_MASTER} pm
join {iisVACCINATION_MASTER} vm
    ON vm.asiis_pat_id_ptr = pm.asiis_pat_id
```

**This is the only INNER JOIN.** Every row in the output represents one vaccination
event that has a matching patient. Patients with no vaccination events are excluded.
Vaccination events with no matching patient are excluded.

---

### LEFT JOIN iisIRMS (lines 766-767)

```sql
LEFT JOIN {iisIRMS} i
    ON i.irms_sys_id = vm.last_irms_sys_id_to_update
```

Provides: IRMS_SYS_ID (the reporting organization's system ID), ORG_NAME, VFC_PIN
(fallback). NULL if no IRMS record for this vaccination's reporting system.

---

### LEFT JOIN iisFACILITY_MASTER (lines 768-769)

```sql
LEFT JOIN {iisFACILITY_MASTER} fm
    ON vm.asiis_fac_id = fm.asiis_fac_id
```

Provides: ASIIS_FAC_ID, FAC_NAME, VFC_PIN (preferred), PROVIDER_FACILITY_TYPE,
FAC_ADDRESS fields. NULL if no facility record.

---

### LEFT JOIN iisLOT_NUMBER (lines 770-771)

```sql
LEFT JOIN {iisLOT_NUMBER} ln
    ON vm.lot_number_code = ln.lot_number_code
```

Provides: EXPIRATION_DATE. Also the `vaccine_product_id` used by the NDC JOIN below.
NULL if no lot record.

---

### LEFT JOIN pat_master (lines 772-773)

```sql
LEFT JOIN pat_master pma
    ON pm.asiis_pat_id = pma.asiis_pat_id
```

`pat_master` is a cached subquery (computed earlier in the notebook) that normalizes
ZIP and county codes from PATIENT_MASTER. Used as fallback for address fields when
the primary address subquery (`pz`) has no record.

---

### LEFT JOIN pat_address (lines 774-775)

```sql
LEFT JOIN pat_address pz
    ON pm.asiis_pat_id = pz.asiis_pat_id_ptr
```

`pat_address` is a cached subquery providing the patient's most recently updated
address (with ZIP and county normalization). Used for PAT_ADDRESS fields.

---

### LEFT JOIN multi_race (lines 776-777)

```sql
LEFT JOIN multi_race mr
    ON pm.asiis_pat_id = mr.asiis_pat_id_ptr
```

`multi_race` is a cached subquery providing the patient's most recently recorded race
codes, pivoted into up to 6 slots. Provides PAT_RACE1-6.

---

### LEFT JOIN iisASIIS_VACC_CODE (lines 778-779)

```sql
LEFT JOIN {iisASIIS_VACC_CODE} vc
    ON vc.asiis_vacc_code = vm.asiis_vacc_code
```

Provides: BEST_CDC_CODE (the CVX code corresponding to the WAIIS internal vaccine
code). NULL if no CVX mapping exists for this vaccine.

---

### LEFT JOIN iisVACCINE_PRODUCT_MAPPING (lines 780-782)

```sql
LEFT JOIN {iisVACCINE_PRODUCT_MAPPING} m
    ON m.vaccine_product_id = ln.vaccine_product_id
    AND M.IS_USE_NDC IS NOT NULL
```

Provides: NDC_CODE. Joined via lot number's vaccine product ID. The `IS_USE_NDC IS
NOT NULL` filter means only NDC-designated product mappings are selected. NULL if no
lot record or no NDC mapping.

---

### LEFT JOIN iisVACCINE_FAMILY (lines 783-784)

```sql
LEFT JOIN {iisVACCINE_FAMILY} vf
    ON vm.asiis_vacc_code = vf.asiis_vacc_code
```

Provides: FAMILY_CODE (STC ImmuCast vaccine family grouping integer). Also used as
a join key for VALID_VACCINATION and INVALID_VACCINATION below.

---

### LEFT JOIN iisVALID_VACCINATION (lines 785-789)

```sql
LEFT JOIN {iisVALID_VACCINATION} vv
    ON (vm.asiis_pat_id_ptr = vv.asiis_pat_id
    AND vm.asiis_vacc_code = vv.asiis_vacc_code
    AND vm.vacc_date = vv.vacc_date
    AND vf.family_code = vv.family_code)
```

Provides: `vv.valid_vacc`, `vv.dose_number`, `vv.series_complete` -- used by
DOSE_NUMBER and SERIES_COMPLETE CASE expressions. NULL if no validity record (dose
not yet evaluated).

---

### LEFT JOIN iisINVALID_VACCINATION (lines 790-795)

```sql
LEFT JOIN {iisINVALID_VACCINATION} iv
     ON ( vm.asiis_pat_id_ptr = iv.asiis_pat_id
    AND vm.asiis_vacc_code = iv.asiis_vacc_code
    AND vm.vacc_date = iv.vacc_date
    AND vm.invalid_reason_code = iv.INVALID_REASON_CODE
    AND vf.family_code = iv.family_code)
```

Provides: `iv.invalid_reason_code`, `iv.series_complete` -- used by DOSE_NUMBER and
SERIES_COMPLETE CASE expressions. NULL if no invalidity record.

---

### LEFT JOIN fac_zip (lines 796-797)

```sql
LEFT JOIN fac_zip fz
    ON fm.asiis_fac_id = fz.asiis_fac_id
```

`fac_zip` is a cached subquery normalizing facility ZIP/county from FACILITY_MASTER
and geocoding data. Provides FAC_ADDRESS_CITY, FAC_ADDRESS_COUNTY_CODE,
FAC_ADDRESS_STATE, FAC_ADDRESS_ZIP.

---

### LEFT JOIN iisPATIENT_CONTRAINDICATION (lines 798-803) -- JOIN with no SELECT output

```sql
LEFT JOIN {iisPATIENT_CONTRAINDICATION} pc
    ON (pm.asiis_pat_id = pc.asiis_pat_id_ptr
    AND pc.contra_code IN( 9, 15, 16, 19, 22, 23, 31, 43,
                           48, 53, 56, 58, 59, 97, 102, 106,
                           109, 116, 117, 118, 119, 243 )
    AND pc.deletion_date IS NULL)
```

**This JOIN produces no output columns.** It was used by the now-commented-out
COMORBIDITY_STATUS and SEROLOGY_RESULTS columns. The join is still present in the
query but has no effect on the output other than potentially affecting row
deduplication under SELECT DISTINCT (a patient with multiple matching contraindication
records could produce duplicate join rows, but DISTINCT suppresses these).

**Practical note:** This join is dead code. It adds computational cost without
contributing to output. WA DOH may not be aware it's still there.

---

### SEROLOGY_RESERVE -- COMMENTED OUT (lines 804-807)

```sql
-- LEFT JOIN {iisSEROLOGY_RESERVE} sr
--     ON ( PM.asiis_pat_id = SR.asiis_pat_id_ptr
--     AND sr.serology_type_code IN ( 56, 57, 58, 59, 60, 61, 62, 63 )
--     AND sr.deletion_date IS NULL )
```

Removed along with the SEROLOGY output columns. Not in the live table.

---

### LEFT JOIN iisVACCINATION_RESERVE (lines 808-814)

```sql
LEFT JOIN {iisVACCINATION_RESERVE} vr
    ON ( vm.asiis_pat_id_ptr = vr.asiis_pat_id_ptr
    AND vm.asiis_vacc_code = vr.asiis_vacc_code
    AND vm.vacc_date = vr.vacc_date
    AND vm.insert_stamp = vr.insert_stamp
    AND vm.last_irms_sys_id_to_update = vr.irms_sys_id
    AND vm.last_irms_pat_id_to_update = vr.irms_pat_id )
```

Five-column join key. Provides: REGISTRY_ENTRY_STAMP, REPORTING_METHOD, VFC_ELIGIBLE.
NULL if no reserve record for this vaccination.

---

### LEFT JOIN PAT_PHONE (lines 815-816)

```sql
LEFT JOIN PAT_PHONE pl
    ON pm.asiis_pat_id = pl.asiis_pat_id_ptr
```

`PAT_PHONE` is a cached subquery selecting the most recently updated phone number per
patient. Provides PAT_ADDRESS_PHONE.

---

### LEFT JOIN ADDRESS_ATO_VAX (lines 817-819)

```sql
LEFT JOIN ADDRESS_ATO_VAX AAV
    ON AAV.EVENT_ADDRESS_ASSOCIATOR = CONCAT(VM.ASIIS_VACC_CODE, VM.VACC_DATE)
    AND AAV.ASIIS_PAT_ID_PTR = VM.ASIIS_PAT_ID_PTR
```

`ADDRESS_ATO_VAX` is a cached subquery finding the address record closest in time to
the vaccination date. The join key `CONCAT(ASIIS_VACC_CODE, VACC_DATE)` has no
separator -- collision risk if a vacc_code ends with digits that prefix the date, but
this appears to be an accepted design choice. Provides ATO_PAT_ADDRESS fields.

---

### WHERE clause (lines 820-821)

```sql
WHERE vm.deletion_date IS NULL
```

Excludes logically deleted vaccination records. Since DELETION_DATE is always NULL in
the output (see above), every exported row represents an active record.

---

## Summary Table: What Databricks Transforms vs. Passes Through

| Column | Databricks transform | Output type | Values |
|---|---|---|---|
| VACC_EVENT_ID | Constructed string (concat + tab-strip) | string | `WAV...` synthetic key |
| IRMS_SYS_ID | CAST AS DOUBLE | float string | `"1001.0"` |
| ASIIS_FAC_ID | CAST AS DOUBLE | float string | `"5042.0"` |
| Asiis_Vacc_Code | CAST AS DOUBLE | float string | `"208.0"` |
| ASIIS_PAT_ID | CAST AS DOUBLE | float string | `"1234567.0"` |
| PAT_FIRST_NAME | Pass-through | string | Raw |
| PAT_MIDDLE_NAME | Pass-through | string | Raw |
| PAT_LAST_NAME | Pass-through | string | Raw |
| PAT_BIRTH_DATE | to_date(,'yyyy-MM-dd') | date string | `YYYY-MM-DD` |
| PAT_GENDER | Pass-through | string | Unknown - verify with WA DOH |
| PAT_ADDRESS_STREET1/2 | Tab-strip | string | Cleaned |
| PAT_ADDRESS_CITY | Tab-strip | string | Cleaned |
| PAT_COUNTY_CODE | COALESCE(pz, pma) | string | 5-digit FIPS |
| PAT_ADDRESS_STATE | COALESCE(pz, pma) | string | 2-letter state abbrev |
| PAT_ADDRESS_ZIP | COALESCE(pz, pma) | string | 5-digit ZIP |
| ATO_PAT_ADDRESS_* | Tab-strip + COALESCE | string | At-time-of-vacc address |
| PAT_ETHNICITY_CODE | CAST AS string | string | Unknown - verify with WA DOH |
| VACC_DATE | to_date(,'yyyy-MM-dd') | date string | `YYYY-MM-DD` |
| BEST_CDC_CODE | CAST AS DOUBLE | float string | `"208.0"` (CVX code) |
| NDC_CODE | Pass-through | string | Raw NDC |
| MANU_CODE | Pass-through | string | Presumed MVX - verify |
| LOT_NUM | Tab-strip | string | Cleaned |
| EXPIRATION_DATE | to_date(,'yyyy-MM-dd') | date string | `YYYY-MM-DD` |
| ANATOMICAL_SITE | CASE: WAIIS names to abbrev codes | string | LT/LA/RT/RA etc. or NULL |
| ANATOMICAL_ROUTE | CASE: WAIIS names to NCI C-codes | string | C28161 etc. or NULL |
| DOSE_NUMBER | CASE: validity logic | string | 'INV', 'UNK', or digit string |
| SERIES_COMPLETE | CASE: validity flags | string | 'YES', 'NO', 'UNK' |
| FAMILY_CODE | Pass-through | integer/string | STC ImmuCast integer |
| ORG_NAME | COALESCE(IRMS, FAC) tab-strip | string | Cleaned |
| FAC_NAME | COALESCE(FAC, IRMS) tab-strip | string | Cleaned |
| VFC_PIN | COALESCE(fm, i) | string | Raw |
| PROVIDER_FACILITY_TYPE | CASE: WAIIS enum to v2-0442 codes | string | '9','12' etc. or 'UNK' |
| FAC_ADDRESS_STREET1 | Tab-strip | string | Cleaned |
| FAC_ADDRESS_STREET2 | Tab-strip + substr(1,25) | string | Truncated to 25 chars |
| FAC_ADDRESS_CITY | From fac_zip subquery | string | Geocoded |
| FAC_ADDRESS_COUNTY_CODE | From fac_zip subquery | string | 5-digit FIPS |
| FAC_ADDRESS_STATE | From fac_zip subquery | string | 2-letter abbrev |
| FAC_ADDRESS_ZIP | From fac_zip subquery | string | 5-digit ZIP |
| HISTORICAL | Pass-through | string | Unknown - verify with WA DOH |
| INSERT_STAMP | Pass-through | timestamp | Databricks timestamp format |
| REGISTRY_ENTRY_STAMP | to_date(,'yyyy-MM-dd') | date string | `YYYY-MM-DD` or NULL |
| REPORTING_METHOD | Pass-through | string | Unknown - verify with WA DOH |
| VFC_ELIGIBLE | CAST AS DOUBLE | float string | `"1.0"` or `"0.0"` |
| CC_VOLUME | Pass-through | string/numeric | Dose volume in cc |
| PAT_RACE1-6 | MULTI_RACE pivot (try_cast int) | integer | 1-9 WAIIS codes or NULL |
| PAT_LANGUAGE | Pass-through | string | Unknown - verify with WA DOH |
| PAT_ADDRESS_PHONE | Regex-stripped digits | string | Digits only |
| PAT_ADDRESS_EMAIL | Pass-through | string | Raw |
| IWEB_VACC_EVENT_ID | Pass-through | string | Legacy IWeb ID or NULL |
| DELETION_DATE | to_date(,'yyyy-MM-dd') | date | ALWAYS NULL (WHERE filters it) |

---

## Open Questions for WA DOH

Based on this analysis, the following values cannot be determined from the notebook alone:

1. **PAT_GENDER**: What values does `iisPATIENT_MASTER.pat_gender` store? (M/F/U assumed)
2. **PAT_ETHNICITY_CODE**: What values does `iisPATIENT_MASTER.pat_ethnicity_code` store?
3. **HISTORICAL**: What values does `iisVACCINATION_MASTER.historical` store? What does each value mean (administered vs. historical)?
4. **MANU_CODE**: Is `iisVACCINATION_MASTER.manu_code` always an MVX code?
5. **PAT_LANGUAGE**: What values does `iisPATIENT_MASTER.pat_language` store? BCP-47 tags?
6. **REPORTING_METHOD**: What values does `iisVACCINATION_RESERVE.reporting_method` store?
7. **INSERT_STAMP format**: What timestamp format does the CSV export use?
8. **DOUBLE column format**: Confirm the CSV export produces float strings like `"208.0"` for the CAST AS DOUBLE columns.
9. **ATO address semantics**: The `ADDRESS_ATO_VAX` subquery provides the closest address to each vaccination event. We are currently accumulating these as historical `Patient.address` entries with `use=old`, ordered newest vaccination first. Is this the intended use of the ATO address data, or should it be associated with the specific Immunization record instead?
10. **ADDRESS_ATO_VAX join mismatch**: The contraindication and deferral UNION arms in `ADDRESS_ATO_VAX` build `EVENT_ADDRESS_ASSOCIATOR` using `INSERT_STAMP` and `DEFERRAL_DATE` respectively, but the main query joins on `CONCAT(VM.ASIIS_VACC_CODE, VM.VACC_DATE)`. Those two arms can never match. Is this intentional (i.e., only vaccination-event addresses are needed), or is this a bug in the notebook?
