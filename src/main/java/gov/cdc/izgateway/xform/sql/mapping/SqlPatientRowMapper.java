package gov.cdc.izgateway.xform.sql.mapping;

import org.hl7.fhir.r4.model.*;

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
            case "telecom.phone" -> {
                ContactPoint cp = patient.addTelecom();
                cp.setSystem(ContactPoint.ContactPointSystem.PHONE);
                cp.setValue(value);
            }
            case "identifier" -> {
                Identifier id = patient.addIdentifier();
                id.setSystem(m.getSystem() != null ? m.getSystem() : "urn:oid:2.16.840.1.114222.4.1");
                id.setValue(value);
            }
            case "meta.lastUpdated" -> patient.getMeta().setLastUpdatedElement(toInstant(value));
            default -> log.debug("Unhandled Patient path: {}", m.getPath());
        }
    }
}
