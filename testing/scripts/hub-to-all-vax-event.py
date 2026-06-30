"""
hub-to-all-vax-event.py -- Convert hub fixture CSVs to all_vax_event format.

Reads the two-file hub test data format used by the SqlDevBackend:
  patients.csv     -- one row per patient
  immunizations.csv -- one row per vaccination event

Writes a single denormalized all_vax_event.csv where each row is one
vaccination event joined with its patient demographics. This is the format
consumed by the WA DOH WAIIS SQL backend and the local-test `test` backend.

Usage:
  python hub-to-all-vax-event.py [patients.csv] [immunizations.csv] [output.csv]

Defaults (relative to this script's location):
  patients       -- ../../src/test/resources/sql-dev/patients.csv
  immunizations  -- ../../src/test/resources/sql-dev/immunizations.csv
  output         -- ../../src/test/resources/sql-dev/all_vax_event.csv

Value format assumptions (matching sql-mapping-wadoh.yml):
  - CAST-AS-DOUBLE columns (ASIIS_PAT_ID, BEST_CDC_CODE, Asiis_Vacc_Code,
    IRMS_SYS_ID, ASIIS_FAC_ID, VFC_ELIGIBLE) are written as "<int>.0"
  - Dates: YYYY-MM-DD (pass-through from hub format)
  - INSERT_STAMP timestamp: YYYY-MM-DDT00:00:00.000 (hub has date only)
  - HISTORICAL: hub Event Record Type "00" -> "N" (administered), else "Y"
  - DOSE_NUMBER: "UNK" (not in hub format)
  - ANATOMICAL_ROUTE: mapped from abbreviated hub values to NCI Thesaurus C-codes
  - VFC_ELIGIBLE: "0.0" (not in hub format; defaults to not eligible)
  - IRMS_SYS_ID, ASIIS_FAC_ID: "0.0" (no facility data in hub format)
  - ATO address columns: copied from current patient address (hub has no ATO data)
  - Race, ethnicity, language: empty (not in hub format)
"""

import csv
import os
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
RESOURCES_DIR = os.path.join(SCRIPT_DIR, "..", "..", "src", "test", "resources", "sql-dev")

PATIENTS_DEFAULT = os.path.join(RESOURCES_DIR, "patients.csv")
IMMUNIZATIONS_DEFAULT = os.path.join(RESOURCES_DIR, "immunizations.csv")
OUTPUT_DEFAULT = os.path.join(RESOURCES_DIR, "all_vax_event.csv")

# Map abbreviated route codes (hub format) to NCI Thesaurus C-codes (WAIIS format).
# Source: Databricks notebook ANATOMICAL_ROUTE CASE expression.
ROUTE_MAP = {
    "IM":   "C28161",  # Intramuscular
    "SC":   "C38299",  # Subcutaneous
    "ID":   "C38238",  # Intradermal
    "IN":   "C38284",  # Intranasal
    "IV":   "C38276",  # Intravenous
    "ORAL": "C38288",  # Oral
    "PO":   "C38288",  # Oral (alternate abbreviation)
    "TD":   "C38307",  # Transdermal
}

# Output column order matches the all_vax_event enriched mapping CSV.
OUTPUT_COLUMNS = [
    "VACC_EVENT_ID",
    "IRMS_SYS_ID",
    "ASIIS_FAC_ID",
    "Asiis_Vacc_Code",
    "ASIIS_PAT_ID",
    "IWEB_VACC_EVENT_ID",
    "PAT_FIRST_NAME",
    "PAT_MIDDLE_NAME",
    "PAT_LAST_NAME",
    "PAT_BIRTH_DATE",
    "PAT_GENDER",
    "PAT_ETHNICITY_CODE",
    "PAT_LANGUAGE",
    "PAT_ADDRESS_PHONE",
    "PAT_ADDRESS_EMAIL",
    "PAT_ADDRESS_STREET1",
    "PAT_ADDRESS_STREET2",
    "PAT_ADDRESS_CITY",
    "PAT_COUNTY_CODE",
    "PAT_ADDRESS_STATE",
    "PAT_ADDRESS_ZIP",
    "ATO_PAT_ADDRESS_STREET1",
    "ATO_PAT_ADDRESS_STREET2",
    "ATO_PAT_ADDRESS_CITY",
    "ATO_PAT_COUNTY_CODE",
    "ATO_PAT_ADDRESS_STATE",
    "ATO_PAT_ADDRESS_ZIP",
    "PAT_RACE1",
    "PAT_RACE2",
    "PAT_RACE3",
    "PAT_RACE4",
    "PAT_RACE5",
    "PAT_RACE6",
    "VACC_DATE",
    "BEST_CDC_CODE",
    "NDC_CODE",
    "MANU_CODE",
    "LOT_NUM",
    "EXPIRATION_DATE",
    "ANATOMICAL_SITE",
    "ANATOMICAL_ROUTE",
    "DOSE_NUMBER",
    "SERIES_COMPLETE",
    "HISTORICAL",
    "REPORTING_METHOD",
    "VFC_ELIGIBLE",
    "CC_VOLUME",
    "INSERT_STAMP",
    "REGISTRY_ENTRY_STAMP",
    "DELETION_DATE",
    "FAMILY_CODE",
    "ORG_NAME",
    "FAC_NAME",
    "VFC_PIN",
    "PROVIDER_FACILITY_TYPE",
    "FAC_ADDRESS_STREET1",
    "FAC_ADDRESS_STREET2",
    "FAC_ADDRESS_CITY",
    "FAC_ADDRESS_COUNTY_CODE",
    "FAC_ADDRESS_STATE",
    "FAC_ADDRESS_ZIP",
]


def as_double(value):
    """Format an integer-valued string as a CAST-AS-DOUBLE float string ("1001" -> "1001.0")."""
    if not value:
        return ""
    try:
        return f"{int(float(value))}.0"
    except (ValueError, TypeError):
        return value


def map_route(route):
    """Map hub route abbreviation to NCI Thesaurus C-code."""
    if not route:
        return ""
    return ROUTE_MAP.get(route.strip().upper(), route)


def map_historical(event_record_type):
    """Map hub Event Record Type to WAIIS HISTORICAL flag (N=administered, Y=historical)."""
    if not event_record_type:
        return "N"
    return "Y" if event_record_type.strip().upper() == "H" else "N"


def to_insert_stamp(date_str):
    """Convert YYYY-MM-DD date to INSERT_STAMP timestamp format YYYY-MM-DDT00:00:00.000."""
    if not date_str:
        return ""
    date_str = date_str.strip()
    if len(date_str) == 10 and date_str[4] == "-":
        return f"{date_str}T00:00:00.000"
    return date_str


def load_csv(path, label):
    rows = []
    with open(path, newline="", encoding="utf-8-sig") as f:
        reader = csv.DictReader(f)
        for row in reader:
            rows.append(row)
    print(f"Loaded {len(rows)} {label} from {path}")
    return rows


def convert(patients_path, immunizations_path, output_path):
    patients = load_csv(patients_path, "patients")
    immunizations = load_csv(immunizations_path, "immunizations")

    patient_index = {row["IIS Patient ID"].strip(): row for row in patients}

    output_rows = []
    skipped = 0
    for imm in immunizations:
        pat_id = imm.get("IIS Patient ID", "").strip()
        pat = patient_index.get(pat_id)
        if pat is None:
            print(f"  WARNING: no patient found for IIS Patient ID={pat_id!r}, skipping row")
            skipped += 1
            continue

        record_date = imm.get("Record Creation Date", "").strip()

        row = {
            "VACC_EVENT_ID":         imm.get("IIS Vaccination Event ID", "").strip(),
            "IRMS_SYS_ID":           "0.0",
            "ASIIS_FAC_ID":          "0.0",
            "Asiis_Vacc_Code":       as_double(imm.get("Vaccine Type (CVX)", "")),
            "ASIIS_PAT_ID":          as_double(pat_id),
            "IWEB_VACC_EVENT_ID":    imm.get("IIS Vaccination Event ID", "").strip(),
            "PAT_FIRST_NAME":        pat.get("Name - First", "").strip(),
            "PAT_MIDDLE_NAME":       pat.get("Name - Middle", "").strip(),
            "PAT_LAST_NAME":         pat.get("Name - Last", "").strip(),
            "PAT_BIRTH_DATE":        pat.get("Date of Birth", "").strip(),
            "PAT_GENDER":            pat.get("Gender", "").strip(),
            "PAT_ETHNICITY_CODE":    "",
            "PAT_LANGUAGE":          "",
            "PAT_ADDRESS_PHONE":     pat.get("Telephone Number", "").strip(),
            "PAT_ADDRESS_EMAIL":     "",
            "PAT_ADDRESS_STREET1":   pat.get("Address: Street", "").strip(),
            "PAT_ADDRESS_STREET2":   "",
            "PAT_ADDRESS_CITY":      pat.get("Address: City", "").strip(),
            "PAT_COUNTY_CODE":       "",
            "PAT_ADDRESS_STATE":     pat.get("Address: State", "").strip(),
            "PAT_ADDRESS_ZIP":       pat.get("Address: Zip", "").strip(),
            # ATO address: copy current address (hub format has no ATO data)
            "ATO_PAT_ADDRESS_STREET1": pat.get("Address: Street", "").strip(),
            "ATO_PAT_ADDRESS_STREET2": "",
            "ATO_PAT_ADDRESS_CITY":    pat.get("Address: City", "").strip(),
            "ATO_PAT_COUNTY_CODE":     "",
            "ATO_PAT_ADDRESS_STATE":   pat.get("Address: State", "").strip(),
            "ATO_PAT_ADDRESS_ZIP":     pat.get("Address: Zip", "").strip(),
            "PAT_RACE1":             "",
            "PAT_RACE2":             "",
            "PAT_RACE3":             "",
            "PAT_RACE4":             "",
            "PAT_RACE5":             "",
            "PAT_RACE6":             "",
            "VACC_DATE":             imm.get("Administration Date", "").strip(),
            "BEST_CDC_CODE":         as_double(imm.get("Vaccine Type (CVX)", "")),
            "NDC_CODE":              imm.get("Vaccine Type (NDC)", "").strip(),
            "MANU_CODE":             imm.get("Manufacturer", "").strip(),
            "LOT_NUM":               imm.get("Lot Number", "").strip(),
            "EXPIRATION_DATE":       imm.get("Expiration Date", "").strip(),
            "ANATOMICAL_SITE":       imm.get("Site of Administration", "").strip(),
            "ANATOMICAL_ROUTE":      map_route(imm.get("Route of Administration", "")),
            "DOSE_NUMBER":           "UNK",
            "SERIES_COMPLETE":       "UNK",
            "HISTORICAL":            map_historical(imm.get("Event Record Type", "")),
            "REPORTING_METHOD":      "",
            "VFC_ELIGIBLE":          "0.0",
            "CC_VOLUME":             imm.get("Dose Volume", "").strip(),
            "INSERT_STAMP":          to_insert_stamp(record_date),
            "REGISTRY_ENTRY_STAMP":  record_date,
            "DELETION_DATE":         "",
            "FAMILY_CODE":           "",
            "ORG_NAME":              "",
            "FAC_NAME":              "",
            "VFC_PIN":               "",
            "PROVIDER_FACILITY_TYPE": "",
            "FAC_ADDRESS_STREET1":   "",
            "FAC_ADDRESS_STREET2":   "",
            "FAC_ADDRESS_CITY":      "",
            "FAC_ADDRESS_COUNTY_CODE": "",
            "FAC_ADDRESS_STATE":     "",
            "FAC_ADDRESS_ZIP":       "",
        }
        output_rows.append(row)

    os.makedirs(os.path.dirname(os.path.abspath(output_path)), exist_ok=True)
    with open(output_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=OUTPUT_COLUMNS, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(output_rows)

    print(f"Wrote {len(output_rows)} rows to {output_path}")
    if skipped:
        print(f"Skipped {skipped} immunization rows (no matching patient)")


if __name__ == "__main__":
    args = sys.argv[1:]
    patients_path      = args[0] if len(args) > 0 else PATIENTS_DEFAULT
    immunizations_path = args[1] if len(args) > 1 else IMMUNIZATIONS_DEFAULT
    output_path        = args[2] if len(args) > 2 else OUTPUT_DEFAULT

    convert(patients_path, immunizations_path, output_path)
