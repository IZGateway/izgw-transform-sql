#!/usr/bin/env python3
"""
Convert RSP-Test-Messages.hl7 (IZ Gateway Hub QBP test data) to all_vax_event CSV format
and hub-format patients.csv / immunizations.csv for the sql-dev test backend.

Usage:
    python tools/rsp-to-all-vax-event.py [RSP_FILE] [OUTPUT_CSV]

Defaults:
    RSP_FILE   : ../../izgw-hub/src/main/resources/RSP-Test-Messages.hl7
                 (relative to this script's directory)
    OUTPUT_CSV : src/test/resources/sql-dev/all_vax_event.csv

Hub-format CSVs are written alongside OUTPUT_CSV as patients.csv and immunizations.csv.

All three files share the same patient/immunization data so SqlDevBackend (hub CSVs) and
SqlTestBackend (all_vax_event.csv) serve identical records for QBP/FHIR cross-validation.
"""

import csv
import os
import sys

# ---------------------------------------------------------------------------
# Lookup tables
# ---------------------------------------------------------------------------

# RSP PID-10 race text -> WAIIS integer code
# Derived by reversing sql-mapping-wadoh.yml concept_maps (WAIIS -> OMB)
RACE_TO_WAIIS = {
    "WHITE":    "1",   # 2106-3  White
    "BLACK":    "2",   # 2054-5  Black or African American
    "ASIAN":    "4",   # 2028-9  Asian
    "INDIAN":   "5",   # 1002-5  American Indian or Alaska Native
    "HAWAIIAN": "7",   # 2076-8  Native Hawaiian or Other Pacific Islander
    "OTHER":    "6",   # 2131-1  Some other race
}

# RSP PID-22 ethnicity text -> WAIIS/DOH code (H/N)
ETHNICITY_TO_CODE = {
    "HISPANIC":     "H",
    "NOT HISPANIC": "N",
}

# NCI C-code (from RXR-1) -> hub abbreviation for immunizations.csv Route column
ROUTE_NCI_TO_HUB = {
    "C28161": "IM",
    "C38238": "ID",
    "C38284": "IN",
    "C38276": "IV",
    "C38288": "PO",
    "C38299": "SC",
    "C38305": "TD",
}

HUB_PATIENTS_COLUMNS = [
    "IIS Patient ID", "Name - First", "Name - Middle", "Name - Last",
    "Date of Birth", "Gender", "Address: Street", "Address: City",
    "Address: State", "Address: Zip", "Telephone Number", "Record Creation Date",
]

HUB_IMMUNIZATIONS_COLUMNS = [
    "IIS Patient ID", "IIS Vaccination Event ID",
    "Vaccine Type (CVX)", "Vaccine Type (NDC)",
    "Administration Date", "Manufacturer", "Lot Number",
    "Event Record Type", "Route of Administration", "Site of Administration",
    "Expiration Date", "Dose Volume", "Record Creation Date",
]

# all_vax_event column order (must match sql-mapping-wadoh.yml column names exactly)
COLUMNS = [
    "VACC_EVENT_ID", "IRMS_SYS_ID", "ASIIS_FAC_ID", "Asiis_Vacc_Code", "ASIIS_PAT_ID",
    "IWEB_VACC_EVENT_ID",
    "PAT_FIRST_NAME", "PAT_MIDDLE_NAME", "PAT_LAST_NAME",
    "PAT_BIRTH_DATE", "PAT_GENDER", "PAT_ETHNICITY_CODE", "PAT_LANGUAGE",
    "PAT_ADDRESS_PHONE", "PAT_ADDRESS_EMAIL",
    "PAT_ADDRESS_STREET1", "PAT_ADDRESS_STREET2",
    "PAT_ADDRESS_CITY", "PAT_COUNTY_CODE", "PAT_ADDRESS_STATE", "PAT_ADDRESS_ZIP",
    "ATO_PAT_ADDRESS_STREET1", "ATO_PAT_ADDRESS_STREET2",
    "ATO_PAT_ADDRESS_CITY", "ATO_PAT_COUNTY_CODE",
    "ATO_PAT_ADDRESS_STATE", "ATO_PAT_ADDRESS_ZIP",
    "PAT_RACE1", "PAT_RACE2", "PAT_RACE3", "PAT_RACE4", "PAT_RACE5", "PAT_RACE6",
    "VACC_DATE", "BEST_CDC_CODE", "NDC_CODE", "MANU_CODE", "LOT_NUM", "EXPIRATION_DATE",
    "ANATOMICAL_SITE", "ANATOMICAL_ROUTE",
    "DOSE_NUMBER", "SERIES_COMPLETE", "HISTORICAL", "REPORTING_METHOD",
    "VFC_ELIGIBLE", "CC_VOLUME",
    "INSERT_STAMP", "REGISTRY_ENTRY_STAMP", "DELETION_DATE",
    "FAMILY_CODE", "ORG_NAME", "FAC_NAME", "VFC_PIN", "PROVIDER_FACILITY_TYPE",
    "FAC_ADDRESS_STREET1", "FAC_ADDRESS_STREET2",
    "FAC_ADDRESS_CITY", "FAC_ADDRESS_COUNTY_CODE",
    "FAC_ADDRESS_STATE", "FAC_ADDRESS_ZIP",
]

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def component(field, n):
    """Return the Nth (1-based) HL7 component of a field, or empty string."""
    parts = field.split("^")
    return parts[n - 1].strip() if len(parts) >= n else ""


def hl7_date(yyyymmdd):
    """Convert YYYYMMDD to YYYY-MM-DD. Returns the input unchanged if not 8 digits."""
    d = yyyymmdd.strip()
    if len(d) == 8 and d.isdigit():
        return f"{d[:4]}-{d[4:6]}-{d[6:8]}"
    return d


def as_double(value):
    """Format a numeric string as a Databricks DOUBLE-cast column (e.g. '208' -> '208.0')."""
    try:
        return f"{int(float(value))}.0"
    except (ValueError, TypeError):
        return ""


def clean_phone(raw):
    """Strip non-digit characters from a phone number."""
    return "".join(c for c in raw if c.isdigit())


def parse_race(pid10):
    """Map RSP PID-10 race text to a single WAIIS integer code, or empty string."""
    return RACE_TO_WAIIS.get(pid10.strip().upper(), "")


def parse_ethnicity(pid22):
    """Map RSP PID-22 ethnicity text to H/N, or empty string."""
    return ETHNICITY_TO_CODE.get(pid22.strip().upper(), "")


# ---------------------------------------------------------------------------
# RSP parser
# ---------------------------------------------------------------------------

def parse_rsp_file(path):
    """
    Yield one dict per message from the RSP file.
    Each dict has keys: 'pid', 'events' (list of dicts with 'orc', 'rxa', 'rxr').
    """
    with open(path, encoding="utf-8") as fh:
        lines = [line.rstrip("\r\n") for line in fh]

    # Split into message blocks on MSH lines
    blocks = []
    current = []
    for line in lines:
        if line.startswith("MSH|") and current:
            blocks.append(current)
            current = []
        if line:
            current.append(line)
    if current:
        blocks.append(current)

    for block in blocks:
        segments = {seg.split("|")[0]: [] for seg in block}
        for seg in block:
            key = seg.split("|")[0]
            if key not in segments:
                segments[key] = []
            segments[key].append(seg.split("|"))

        pid = segments.get("PID", [[]])[0]
        events = []

        # Walk through ORC/RXA/RXR in order
        orc = rxr = None
        for seg in block:
            fields = seg.split("|")
            tag = fields[0]
            if tag == "ORC":
                orc = fields
            elif tag == "RXA":
                rxr = None   # reset; RXR follows RXA
                events.append({"orc": orc, "rxa": fields, "rxr": None})
            elif tag == "RXR" and events:
                events[-1]["rxr"] = fields

        yield {"pid": pid, "events": events}


# ---------------------------------------------------------------------------
# Row builder
# ---------------------------------------------------------------------------

def build_rows(message):
    pid = message["pid"]
    rows = []

    # Patient demographics from PID
    pat_id_raw = component(pid[3] if len(pid) > 3 else "", 1)
    family    = component(pid[5] if len(pid) > 5 else "", 1)
    given     = component(pid[5] if len(pid) > 5 else "", 2)
    middle    = component(pid[5] if len(pid) > 5 else "", 3)
    dob       = hl7_date(pid[7] if len(pid) > 7 else "")
    gender    = pid[8].strip() if len(pid) > 8 else ""
    race_raw  = pid[10].strip() if len(pid) > 10 else ""
    addr_raw  = pid[11] if len(pid) > 11 else ""
    phone_raw = pid[13] if len(pid) > 13 else ""
    ethnicity = parse_ethnicity(pid[22].strip() if len(pid) > 22 else "")

    street1 = component(addr_raw, 1)
    city    = component(addr_raw, 3)
    state   = component(addr_raw, 4)
    zipcode = component(addr_raw, 5)
    phone   = clean_phone(component(phone_raw, 1) if "|" not in phone_raw else phone_raw)

    asiis_pat_id = as_double(pat_id_raw)
    race_waiis   = parse_race(race_raw)

    for ev in message["events"]:
        rxa = ev["rxa"]
        rxr = ev.get("rxr") or []
        orc = ev.get("orc") or []

        # Vaccination event ID from ORC-3 component 1
        event_id_raw = component(orc[3] if len(orc) > 3 else "", 1) if orc else ""
        if not event_id_raw:
            event_id_raw = pat_id_raw   # fallback: shouldn't happen with valid RSP data

        vacc_date_raw = rxa[3].strip() if len(rxa) > 3 else ""
        vacc_date     = hl7_date(vacc_date_raw)
        insert_stamp  = f"{vacc_date}T00:00:00.000" if vacc_date else ""

        cvx_raw  = component(rxa[5] if len(rxa) > 5 else "", 1)
        volume   = rxa[6].strip() if len(rxa) > 6 else ""
        lot_num  = rxa[15].strip() if len(rxa) > 15 else ""
        exp_date_raw = rxa[16].strip() if len(rxa) > 16 else ""
        exp_date = hl7_date(exp_date_raw) if exp_date_raw else ""
        manu_raw = component(rxa[17] if len(rxa) > 17 else "", 1) if len(rxa) > 17 else ""

        # Route: RXR-1 component 1 is already the NCI C-code (e.g. C28161)
        route = component(rxr[1] if len(rxr) > 1 else "", 1) if rxr else ""
        # Site:  RXR-2 component 1 is already the abbreviated code (e.g. RT)
        site  = component(rxr[2] if len(rxr) > 2 else "", 1) if rxr else ""

        row = {
            "VACC_EVENT_ID":           event_id_raw,
            "IRMS_SYS_ID":             "0.0",
            "ASIIS_FAC_ID":            "0.0",
            "Asiis_Vacc_Code":         as_double(cvx_raw),
            "ASIIS_PAT_ID":            asiis_pat_id,
            "IWEB_VACC_EVENT_ID":      event_id_raw,
            "PAT_FIRST_NAME":          given,
            "PAT_MIDDLE_NAME":         middle,
            "PAT_LAST_NAME":           family,
            "PAT_BIRTH_DATE":          dob,
            "PAT_GENDER":              gender,
            "PAT_ETHNICITY_CODE":      ethnicity,
            "PAT_LANGUAGE":            "",
            "PAT_ADDRESS_PHONE":       phone,
            "PAT_ADDRESS_EMAIL":       "",
            "PAT_ADDRESS_STREET1":     street1,
            "PAT_ADDRESS_STREET2":     "",
            "PAT_ADDRESS_CITY":        city,
            "PAT_COUNTY_CODE":         "",
            "PAT_ADDRESS_STATE":       state,
            "PAT_ADDRESS_ZIP":         zipcode,
            # ATO address: duplicate current address (no historical address in test data)
            "ATO_PAT_ADDRESS_STREET1": street1,
            "ATO_PAT_ADDRESS_STREET2": "",
            "ATO_PAT_ADDRESS_CITY":    city,
            "ATO_PAT_COUNTY_CODE":     "",
            "ATO_PAT_ADDRESS_STATE":   state,
            "ATO_PAT_ADDRESS_ZIP":     zipcode,
            "PAT_RACE1":               race_waiis,
            "PAT_RACE2":               "",
            "PAT_RACE3":               "",
            "PAT_RACE4":               "",
            "PAT_RACE5":               "",
            "PAT_RACE6":               "",
            "VACC_DATE":               vacc_date,
            "BEST_CDC_CODE":           as_double(cvx_raw),
            "NDC_CODE":                "",
            "MANU_CODE":               manu_raw,
            "LOT_NUM":                 lot_num,
            "EXPIRATION_DATE":         exp_date,
            "ANATOMICAL_SITE":         site,
            "ANATOMICAL_ROUTE":        route,
            "DOSE_NUMBER":             "UNK",
            "SERIES_COMPLETE":         "UNK",
            "HISTORICAL":              "N",
            "REPORTING_METHOD":        "",
            "VFC_ELIGIBLE":            "0.0",
            "CC_VOLUME":               volume,
            "INSERT_STAMP":            insert_stamp,
            "REGISTRY_ENTRY_STAMP":    vacc_date,
            "DELETION_DATE":           "",
            "FAMILY_CODE":             "",
            "ORG_NAME":                "",
            "FAC_NAME":                "",
            "VFC_PIN":                 "",
            "PROVIDER_FACILITY_TYPE":  "",
            "FAC_ADDRESS_STREET1":     "",
            "FAC_ADDRESS_STREET2":     "",
            "FAC_ADDRESS_CITY":        "",
            "FAC_ADDRESS_COUNTY_CODE": "",
            "FAC_ADDRESS_STATE":       "",
            "FAC_ADDRESS_ZIP":         "",
        }
        rows.append(row)

    return rows


# ---------------------------------------------------------------------------
# Hub-format row builder
# ---------------------------------------------------------------------------

def build_hub_rows(message):
    """Return (patient_dict, [imm_dict, ...]) in hub CSV column format."""
    pid = message["pid"]

    pat_id_raw = component(pid[3] if len(pid) > 3 else "", 1)
    family    = component(pid[5] if len(pid) > 5 else "", 1)
    given     = component(pid[5] if len(pid) > 5 else "", 2)
    middle    = component(pid[5] if len(pid) > 5 else "", 3)
    dob       = hl7_date(pid[7] if len(pid) > 7 else "")
    gender    = pid[8].strip() if len(pid) > 8 else ""
    addr_raw  = pid[11] if len(pid) > 11 else ""
    phone_raw = pid[13] if len(pid) > 13 else ""

    street1 = component(addr_raw, 1)
    city    = component(addr_raw, 3)
    state   = component(addr_raw, 4)
    zipcode = component(addr_raw, 5)
    phone   = clean_phone(component(phone_raw, 1) if "|" not in phone_raw else phone_raw)

    first_vacc_date = ""
    imm_rows = []
    for ev in message["events"]:
        rxa = ev["rxa"]
        rxr = ev.get("rxr") or []
        orc = ev.get("orc") or []

        event_id_raw = component(orc[3] if len(orc) > 3 else "", 1) if orc else ""
        if not event_id_raw:
            event_id_raw = pat_id_raw

        vacc_date_raw = rxa[3].strip() if len(rxa) > 3 else ""
        vacc_date     = hl7_date(vacc_date_raw)
        if not first_vacc_date:
            first_vacc_date = vacc_date

        cvx_raw  = component(rxa[5] if len(rxa) > 5 else "", 1)
        volume   = rxa[6].strip() if len(rxa) > 6 else ""
        lot_num  = rxa[15].strip() if len(rxa) > 15 else ""
        exp_date_raw = rxa[16].strip() if len(rxa) > 16 else ""
        exp_date = hl7_date(exp_date_raw) if exp_date_raw else ""
        manu_raw = component(rxa[17] if len(rxa) > 17 else "", 1) if len(rxa) > 17 else ""

        route_nci = component(rxr[1] if len(rxr) > 1 else "", 1) if rxr else ""
        route_hub = ROUTE_NCI_TO_HUB.get(route_nci, route_nci)
        site      = component(rxr[2] if len(rxr) > 2 else "", 1) if rxr else ""

        imm_rows.append({
            "IIS Patient ID":           pat_id_raw,
            "IIS Vaccination Event ID": event_id_raw,
            "Vaccine Type (CVX)":       cvx_raw,
            "Vaccine Type (NDC)":       "",
            "Administration Date":      vacc_date,
            "Manufacturer":             manu_raw,
            "Lot Number":               lot_num,
            "Event Record Type":        "00",
            "Route of Administration":  route_hub,
            "Site of Administration":   site,
            "Expiration Date":          exp_date,
            "Dose Volume":              volume,
            "Record Creation Date":     vacc_date,
        })

    patient_row = {
        "IIS Patient ID":     pat_id_raw,
        "Name - First":       given,
        "Name - Middle":      middle,
        "Name - Last":        family,
        "Date of Birth":      dob,
        "Gender":             gender,
        "Address: Street":    street1,
        "Address: City":      city,
        "Address: State":     state,
        "Address: Zip":       zipcode,
        "Telephone Number":   phone,
        "Record Creation Date": first_vacc_date,
    }
    return patient_row, imm_rows


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    repo_root  = os.path.dirname(script_dir)

    rsp_default = os.path.join(
        repo_root, "..", "izgw-hub",
        "src", "main", "resources", "RSP-Test-Messages.hl7"
    )
    out_default = os.path.join(
        repo_root, "src", "test", "resources", "sql-dev", "all_vax_event.csv"
    )

    rsp_path = sys.argv[1] if len(sys.argv) > 1 else rsp_default
    out_path = sys.argv[2] if len(sys.argv) > 2 else out_default

    rsp_path = os.path.normpath(rsp_path)
    out_path = os.path.normpath(out_path)

    out_dir = os.path.dirname(out_path)
    patients_path      = os.path.join(out_dir, "patients.csv")
    immunizations_path = os.path.join(out_dir, "immunizations.csv")

    print(f"Source         : {rsp_path}")
    print(f"all_vax_event  : {out_path}")
    print(f"patients       : {patients_path}")
    print(f"immunizations  : {immunizations_path}")

    rows_written = 0
    patient_count = 0

    with open(out_path, "w", newline="", encoding="utf-8") as fh_vax, \
         open(patients_path, "w", newline="", encoding="utf-8") as fh_pat, \
         open(immunizations_path, "w", newline="", encoding="utf-8") as fh_imm:

        vax_writer = csv.DictWriter(fh_vax, fieldnames=COLUMNS, extrasaction="ignore")
        pat_writer = csv.DictWriter(fh_pat, fieldnames=HUB_PATIENTS_COLUMNS)
        imm_writer = csv.DictWriter(fh_imm, fieldnames=HUB_IMMUNIZATIONS_COLUMNS)

        vax_writer.writeheader()
        pat_writer.writeheader()
        imm_writer.writeheader()

        for message in parse_rsp_file(rsp_path):
            rows = build_rows(message)
            if rows:
                patient_count += 1
                vax_writer.writerows(rows)
                rows_written += len(rows)

            patient_row, imm_rows = build_hub_rows(message)
            if imm_rows:
                pat_writer.writerow(patient_row)
                imm_writer.writerows(imm_rows)

    print(f"Written: {rows_written} all_vax_event rows, {patient_count} patients")


if __name__ == "__main__":
    main()
