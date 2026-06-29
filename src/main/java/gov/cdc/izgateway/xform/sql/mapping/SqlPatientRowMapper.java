package gov.cdc.izgateway.xform.sql.mapping;

import org.hl7.fhir.r4.model.*;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class SqlPatientRowMapper extends SqlTableMapper<Patient> {

    public SqlPatientRowMapper(SqlMappingConfiguration config) {
        super(config, "Patient");
    }

    @Override
    protected Patient newResource() {
        return new Patient();
    }

    @Override
    protected void applyField(Patient patient, ResourceMapping m, String value) {
        switch (m.getPath()) {
            case "name.family" -> patient.getNameFirstRep().setFamily(value);
            case "name.given" -> patient.getNameFirstRep().addGiven(value);
            case "birthDate" -> patient.setBirthDateElement(toDate(value));
            case "gender" -> {
                String code = switch (value.toLowerCase()) {
                    case "m", "male" -> "male";
                    case "f", "female" -> "female";
                    default -> "unknown";
                };
                patient.setGender(Enumerations.AdministrativeGender.fromCode(code));
            }
            case "address.line" -> patient.getAddressFirstRep().addLine(value);
            case "address.city" -> patient.getAddressFirstRep().setCity(value);
            case "address.state" -> patient.getAddressFirstRep().setState(value);
            case "address.postalCode" -> patient.getAddressFirstRep().setPostalCode(value);
            case "address.district" -> patient.getAddressFirstRep().setDistrict(value);
            case "telecom.phone" -> {
                ContactPoint cp = patient.addTelecom();
                cp.setSystem(ContactPoint.ContactPointSystem.PHONE);
                cp.setValue(value);
            }
            case "telecom.email" -> {
                ContactPoint cp = patient.addTelecom();
                cp.setSystem(ContactPoint.ContactPointSystem.EMAIL);
                cp.setValue(value);
            }
            case "communication.language" -> patient.addCommunication()
                .setLanguage(toCodeableConcept(value, "urn:ietf:bcp:47", null));
            case "identifier" -> {
                Identifier id = patient.addIdentifier();
                id.setSystem(m.getSystem() != null ? m.getSystem() : "urn:oid:2.16.840.1.114222.4.1");
                id.setValue(value);
            }
            case "meta.lastUpdated" -> patient.getMeta().setLastUpdatedElement(toInstant(value));
            case "extension.race" -> {
                if ("UNK".equals(value)) break;
                Extension raceExt = patient.getExtension().stream()
                    .filter(e -> "http://hl7.org/fhir/us/core/StructureDefinition/us-core-race".equals(e.getUrl()))
                    .findFirst()
                    .orElseGet(() -> patient.addExtension()
                        .setUrl("http://hl7.org/fhir/us/core/StructureDefinition/us-core-race"));
                raceExt.addExtension("ombCategory",
                    toCoding(value, "urn:oid:2.16.840.1.113883.6.238", null));
            }
            // address.historical.* paths are processed by accumulateAtoAddresses(), not here
            case "address.historical.line",
                 "address.historical.city",
                 "address.historical.state",
                 "address.historical.postalCode",
                 "address.historical.district" -> { /* no-op */ }
            default -> log.debug("Unhandled Patient path: {}", m.getPath());
        }
    }

    /**
     * Appends historical (address-at-time-of-vaccination) addresses to the patient.
     * Rows must already be sorted by vaccination date descending so that the most
     * recent address appears first. Consecutive duplicate addresses are collapsed.
     */
    public void accumulateAtoAddresses(Patient patient, List<Map<String, Object>> rows) {
        List<ResourceMapping> atoMappings = config.forResource("Patient").stream()
            .filter(m -> m.getPath().startsWith("address.historical."))
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
        a.setUse(Address.AddressUse.OLD);
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
            switch (m.getPath()) {
                case "address.historical.line"       -> a.addLine(value);
                case "address.historical.city"       -> a.setCity(value);
                case "address.historical.state"      -> a.setState(value);
                case "address.historical.postalCode" -> a.setPostalCode(value);
                case "address.historical.district"   -> a.setDistrict(value);
                default -> { /* ignore */ }
            }
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
