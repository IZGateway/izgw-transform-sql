package gov.cdc.izgateway.xform.sql.mapping;

import gov.cdc.izgw.v2tofhir.segment.PIDParser;
import gov.cdc.izgw.v2tofhir.terminology.RaceAndEthnicity;
import gov.cdc.izgw.v2tofhir.terminology.Systems;
import org.hl7.fhir.r4.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests specific to sql-mapping-wadoh.yml: race extension, ATO address
 * accumulation, and verification that the three removed columns
 * (DELETION_DATE, REGISTRY_ENTRY_STAMP, SERIES_COMPLETE) do not corrupt
 * the mapped Immunization resource.
 */
class SqlWaDohMappingTests {

    private SqlPatientRowMapper patientMapper;
    private SqlImmunizationRowMapper immunizationMapper;

    @BeforeEach
    void setUp() throws Exception {
        String path = new ClassPathResource("sql-mapping-wadoh.yml").getFile().getAbsolutePath();
        SqlMappingConfiguration config = SqlMappingConfigLoader.load(path);
        patientMapper = new SqlPatientRowMapper(config);
        immunizationMapper = new SqlImmunizationRowMapper(config);
    }

    // ── Race extension ───────────────────────────────────────────────────────

    @Test
    void race_singleSlot_addsOmbCategoryExtension() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("PAT_RACE1", "1");  // 1 -> "2106-3" (White)

        Patient patient = patientMapper.map(row);

        Extension raceExt = patient.getExtensionByUrl(RaceAndEthnicity.US_CORE_RACE);
        assertNotNull(raceExt, "US Core race extension should be present");

        List<Extension> ombCats = raceExt.getExtensionsByUrl(PIDParser.OMB_CATEGORY);
        assertEquals(1, ombCats.size());
        Coding coding = (Coding) ombCats.get(0).getValue();
        assertEquals("2106-3", coding.getCode());
        assertEquals(Systems.CDCREC, coding.getSystem());
        assertNotNull(coding.getDisplay(), "display should be populated via Mapping.getDisplay()");

        Extension textExt = raceExt.getExtensionByUrl("text");
        assertNotNull(textExt, "US Core race extension requires a text sub-extension");
        assertFalse(((StringType) textExt.getValue()).getValue().isBlank());
    }

    @Test
    void race_unknownCode_extensionNotAdded() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("PAT_RACE1", "9");  // 9 -> "UNK" -> skipped by applyField

        Patient patient = patientMapper.map(row);

        Extension raceExt = patient.getExtensionByUrl(RaceAndEthnicity.US_CORE_RACE);
        assertNull(raceExt, "Race extension should not be added when only code is UNK");
    }

    @Test
    void race_multipleSlots_accumulateIntoOneExtension() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("PAT_RACE1", "1");  // White -> "2106-3"
        row.put("PAT_RACE2", "2");  // Black or African American -> "2054-5"

        Patient patient = patientMapper.map(row);

        List<Extension> raceExts = patient.getExtension().stream()
            .filter(e -> RaceAndEthnicity.US_CORE_RACE.equals(e.getUrl()))
            .toList();
        assertEquals(1, raceExts.size(), "All race slots should share one top-level race extension");
        assertEquals(2, raceExts.get(0).getExtensionsByUrl(PIDParser.OMB_CATEGORY).size(),
            "Both OMB categories should be present");

        Extension textExt = raceExts.get(0).getExtensionByUrl("text");
        assertNotNull(textExt, "text sub-extension must be present");
        String textValue = ((StringType) textExt.getValue()).getValue();
        assertTrue(textValue.contains(";"), "text should concatenate multiple race display values with semicolons");
    }

    @Test
    void race_slotMixedKnownAndUnknown_onlyKnownAdded() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("PAT_RACE1", "1");  // White
        row.put("PAT_RACE2", "9");  // UNK -> skipped

        Patient patient = patientMapper.map(row);

        Extension raceExt = patient.getExtensionByUrl(RaceAndEthnicity.US_CORE_RACE);
        assertNotNull(raceExt);
        assertEquals(1, raceExt.getExtensionsByUrl(PIDParser.OMB_CATEGORY).size(),
            "Only the known race code should produce an ombCategory sub-extension");
    }

    // ── ATO address accumulation ─────────────────────────────────────────────

    @Test
    void atoAddresses_distinctRows_appended() {
        Patient patient = new Patient();

        Map<String, Object> row1 = new LinkedHashMap<>();
        row1.put("ATO_PAT_ADDRESS_STREET1", "100 Elm St");
        row1.put("ATO_PAT_ADDRESS_CITY", "Seattle");
        row1.put("ATO_PAT_ADDRESS_STATE", "WA");
        row1.put("ATO_PAT_ADDRESS_ZIP", "98101");

        Map<String, Object> row2 = new LinkedHashMap<>();
        row2.put("ATO_PAT_ADDRESS_STREET1", "200 Oak Ave");
        row2.put("ATO_PAT_ADDRESS_CITY", "Tacoma");
        row2.put("ATO_PAT_ADDRESS_STATE", "WA");
        row2.put("ATO_PAT_ADDRESS_ZIP", "98401");

        patientMapper.accumulateAtoAddresses(patient, List.of(row1, row2));

        List<Address> addresses = patient.getAddress();
        assertEquals(2, addresses.size());
        assertNull(addresses.get(0).getUse(), "No use code is set on ATO addresses; slot order conveys relationship");
        assertEquals("Seattle", addresses.get(0).getCity());
        assertEquals("Tacoma", addresses.get(1).getCity());
    }

    @Test
    void atoAddresses_consecutiveDuplicates_collapsed() {
        Patient patient = new Patient();

        Map<String, Object> row = Map.of(
            "ATO_PAT_ADDRESS_STREET1", "100 Elm St",
            "ATO_PAT_ADDRESS_CITY", "Seattle",
            "ATO_PAT_ADDRESS_ZIP", "98101"
        );

        patientMapper.accumulateAtoAddresses(patient, List.of(row, row, row));

        assertEquals(1, patient.getAddress().size(),
            "Consecutive identical ATO addresses should be collapsed to one");
    }

    @Test
    void atoAddresses_emptyRow_noAddressAdded() {
        Patient patient = new Patient();

        patientMapper.accumulateAtoAddresses(patient, List.of(Map.of()));

        assertEquals(0, patient.getAddress().size());
    }

    // ── Removed mapping columns do not corrupt Immunization ─────────────────

    @Test
    void deletionDateAbsent_statusRemainsCompleted() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("DELETION_DATE", "2021-01-01");
        row.put("BEST_CDC_CODE", "208.0");

        Immunization imm = immunizationMapper.map(row);

        assertEquals(Immunization.ImmunizationStatus.COMPLETED, imm.getStatus(),
            "DELETION_DATE is removed from mapping; status must default to COMPLETED");
    }

    @Test
    void insertStampSetsRecorded_registryEntryStampIgnored() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("INSERT_STAMP", "2021-05-01T00:00:00.000");
        row.put("REGISTRY_ENTRY_STAMP", "2020-01-01");

        Immunization imm = immunizationMapper.map(row);

        assertNotNull(imm.getRecordedElement(), "recorded should be set from INSERT_STAMP");
        String recorded = imm.getRecordedElement().asStringValue();
        assertTrue(recorded.startsWith("2021-05-01"),
            "recorded must come from INSERT_STAMP, not REGISTRY_ENTRY_STAMP; got: " + recorded);
    }

    @Test
    void seriesCompleteIgnored_noProtocolAppliedDoseString() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("SERIES_COMPLETE", "YES");
        row.put("BEST_CDC_CODE", "208.0");

        Immunization imm = immunizationMapper.map(row);

        boolean hasDoseString = imm.getProtocolApplied().stream()
            .anyMatch(Immunization.ImmunizationProtocolAppliedComponent::hasSeriesDosesStringType);
        assertFalse(hasDoseString,
            "SERIES_COMPLETE is removed from mapping; seriesDosesString must not be set");
    }
}
