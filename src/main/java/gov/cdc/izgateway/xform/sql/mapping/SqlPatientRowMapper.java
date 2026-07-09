package gov.cdc.izgateway.xform.sql.mapping;

import gov.cdc.izgw.v2tofhir.segment.PIDParser;
import gov.cdc.izgw.v2tofhir.terminology.Mapping;
import gov.cdc.izgw.v2tofhir.terminology.RaceAndEthnicity;
import gov.cdc.izgw.v2tofhir.terminology.Systems;
import org.hl7.fhir.r4.model.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public class SqlPatientRowMapper extends SqlTableMapper<Patient> {

    public SqlPatientRowMapper(SqlMappingConfiguration config) {
        super(config, "Patient");
    }

    @Override
    protected Patient newResource() {
        return new Patient();
    }

    @Override
    public Patient map(Map<String, Object> row) {
        Patient patient = super.map(row);
        pruneEmptyAddressSlots(patient);
        buildRaceText(patient);
        return patient;
    }

    @Override
    protected void applyField(Patient patient, ResourceMapping m, String value, Map<String, Object> row) {
        String path = m.getPath();

        if (path.startsWith("address(")) {
            applyAddressField(patient, path, value);
            return;
        }
        if (path.startsWith("extension(")) {
            applyExtensionPath(patient, path, value);
            return;
        }

        switch (path) {
            case "name.family" -> patient.getNameFirstRep().setFamily(value);
            case "name.given"  -> patient.getNameFirstRep().addGiven(value);
            case "birthDate"   -> patient.setBirthDateElement(toDate(value));
            case "gender" -> {
                String code = switch (value.toLowerCase()) {
                    case "m", "male"   -> "male";
                    case "f", "female" -> "female";
                    default            -> "unknown";
                };
                patient.setGender(Enumerations.AdministrativeGender.fromCode(code));
            }
            case "telecom" -> {
                if (m.getValue() != null) {
                    Object evaluated = MappingValueExpression.evaluate(m.getValue(), value, row);
                    if (evaluated instanceof ContactPoint cp) patient.getTelecom().add(cp);
                }
            }
            case "communication.language" -> patient.addCommunication()
                .setLanguage(toCodeableConcept(value, "urn:ietf:bcp:47", null));
            case "identifier" -> {
                Identifier id = patient.addIdentifier();
                id.setSystem(m.getSystem() != null ? m.getSystem() : "urn:oid:2.16.840.1.114222.4.1");
                id.setValue(value);
            }
            case "meta.lastUpdated" -> patient.getMeta().setLastUpdatedElement(toInstant(value));
            default -> log.debug("Unhandled Patient path: {}", path);
        }
    }

    // -- Address slot helpers ---------------------------------------------------

    /**
     * Applies a value to the address slot identified in path (e.g., address(1).city).
     * Slots are 1-based: address(1) is the first address, address(2) the second.
     */
    private static void applyAddressField(Patient patient, String path, String value) {
        int open  = path.indexOf('(');
        int close = path.indexOf(')');
        int dot   = path.indexOf('.', close);
        if (open < 0 || close < 0 || dot < 0) return;

        int slot;
        try {
            slot = Integer.parseInt(path.substring(open + 1, close));
        } catch (NumberFormatException e) {
            return;
        }
        String field = path.substring(dot + 1);
        Address a = getOrCreateAddressSlot(patient, slot);
        applyAddressSubfield(a, field, value);
    }

    private static Address getOrCreateAddressSlot(Patient patient, int slot) {
        List<Address> addresses = patient.getAddress();
        while (addresses.size() < slot) {
            addresses.add(new Address());
        }
        return addresses.get(slot - 1);
    }

    private static void applyAddressSubfield(Address a, String field, String value) {
        switch (field) {
            case "line"       -> a.addLine(value);
            case "city"       -> a.setCity(value);
            case "state"      -> a.setState(value);
            case "postalCode" -> a.setPostalCode(value);
            case "district"   -> a.setDistrict(value);
            default -> { /* ignore unknown subfields */ }
        }
    }

    private static void pruneEmptyAddressSlots(Patient patient) {
        patient.getAddress().removeIf(a ->
            a.getLine().isEmpty()
            && a.getCity() == null
            && a.getState() == null
            && a.getPostalCode() == null
            && a.getDistrict() == null);
    }

    // -- Extension helpers ------------------------------------------------------

    private static void applyExtensionPath(Patient patient, String path, String value) {
        if (path.contains(RaceAndEthnicity.US_CORE_RACE)) {
            applyRaceExtension(patient, value);
        } else if (path.contains(RaceAndEthnicity.US_CORE_ETHNICITY)) {
            applyEthnicityExtension(patient, value);
        } else {
            log.debug("Unhandled extension path: {}", path);
        }
    }

    private static void applyRaceExtension(Patient patient, String value) {
        if ("UNK".equals(value)) return;
        Extension raceExt = patient.getExtension().stream()
            .filter(e -> RaceAndEthnicity.US_CORE_RACE.equals(e.getUrl()))
            .findFirst()
            .orElseGet(() -> patient.addExtension().setUrl(RaceAndEthnicity.US_CORE_RACE));
        Coding c = new Coding(Systems.CDCREC, value, Mapping.getDisplay(value, Systems.CDCREC));
        raceExt.addExtension(PIDParser.OMB_CATEGORY, c);
    }

    private static void applyEthnicityExtension(Patient patient, String value) {
        if ("UNK".equals(value)) return;
        String display = Mapping.getDisplay(value, Systems.CDCREC);
        CodeableConcept ethnicity = new CodeableConcept();
        if (display != null) ethnicity.setText(display);
        ethnicity.addCoding(new Coding(Systems.CDCREC, value, display));
        Extension ethExt = patient.addExtension().setUrl(RaceAndEthnicity.US_CORE_ETHNICITY);
        RaceAndEthnicity.setEthnicityCode(ethnicity, ethExt);
    }

    private static void buildRaceText(Patient patient) {
        Extension raceExt = patient.getExtensionByUrl(RaceAndEthnicity.US_CORE_RACE);
        if (raceExt == null || raceExt.hasExtension("text")) return;
        List<Extension> ombCats = raceExt.getExtensionsByUrl(PIDParser.OMB_CATEGORY);
        if (ombCats.isEmpty()) return;
        String text = ombCats.stream()
            .map(e -> {
                Coding c = (Coding) e.getValue();
                return c.hasDisplay() ? c.getDisplay() : c.getCode();
            })
            .collect(Collectors.joining("; "));
        raceExt.addExtension("text", new StringType(text));
    }

    // -- ATO address accumulation -----------------------------------------------

    /**
     * Appends at-time-of-vaccination addresses (address slot 2) to the patient.
     * Rows must already be sorted by vaccination date descending so the most recent
     * address appears first. Consecutive duplicate addresses are collapsed.
     */
    public void accumulateAtoAddresses(Patient patient, List<Map<String, Object>> rows) {
        List<ResourceMapping> atoMappings = config.forResource("Patient").stream()
            .filter(m -> m.getPath().startsWith("address(2)."))
            .toList();
        if (atoMappings.isEmpty()) return;

        Address prev = null;
        for (Map<String, Object> row : rows) {
            Address a = buildAtoAddress(row, atoMappings);
            if (a == null || isDuplicateAddress(a, prev)) continue;
            patient.addAddress(a);
            prev = a;
        }
    }

    private Address buildAtoAddress(Map<String, Object> row, List<ResourceMapping> atoMappings) {
        Address a = new Address();
        boolean hasData = false;
        for (ResourceMapping m : atoMappings) {
            String colKey = m.getColumn().toLowerCase();
            Object raw = row.entrySet().stream()
                .filter(e -> e.getKey().toLowerCase().equals(colKey))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
            if (raw == null) continue;
            String value = stripDoubleZero(raw.toString().trim());
            if (value.isEmpty()) continue;
            hasData = true;
            // strip "address(2)." prefix to get the subfield name
            String field = m.getPath().substring("address(2).".length());
            applyAddressSubfield(a, field, value);
        }
        return hasData ? a : null;
    }

    private boolean isDuplicateAddress(Address a, Address prev) {
        if (prev == null) return false;
        return Objects.equals(a.getPostalCode(), prev.getPostalCode())
            && Objects.equals(a.getCity(), prev.getCity())
            && a.getLine().stream().map(StringType::getValue).toList()
                .equals(prev.getLine().stream().map(StringType::getValue).toList());
    }
}
