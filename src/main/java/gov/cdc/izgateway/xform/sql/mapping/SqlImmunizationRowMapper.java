package gov.cdc.izgateway.xform.sql.mapping;

import org.hl7.fhir.r4.model.*;
import java.util.Date;

public class SqlImmunizationRowMapper extends SqlTableMapper<Immunization> {

    public SqlImmunizationRowMapper(SqlMappingConfiguration config) {
        super(config, "Immunization");
    }

    @Override
    protected Immunization newResource() {
        Immunization imm = new Immunization();
        imm.setStatus(Immunization.ImmunizationStatus.COMPLETED);
        return imm;
    }

    @Override
    protected void applyField(Immunization imm, ResourceMapping m, String value) {
        switch (m.getPath()) {
            case "identifier" -> {
                Identifier id = imm.addIdentifier();
                id.setSystem(m.getSystem() != null ? m.getSystem() : "urn:oid:2.16.840.1.114222.4.1");
                id.setValue(value);
            }
            case "vaccineCode.cvx" -> imm.getVaccineCode().addCoding()
                .setSystem("http://hl7.org/fhir/sid/cvx").setCode(value);
            case "vaccineCode.ndc" -> imm.getVaccineCode().addCoding()
                .setSystem("http://hl7.org/fhir/sid/ndc").setCode(value);
            case "occurrenceDateTime" -> imm.setOccurrence(toDateTime(value));
            case "manufacturer" -> imm.getManufacturer().setDisplay(value);
            case "lotNumber" -> imm.setLotNumber(value);
            case "status" -> {
                String mapped = value.equals("00") ? "completed" : value.equals("01") ? "not-done" : "completed";
                imm.setStatus(Immunization.ImmunizationStatus.fromCode(mapped));
            }
            case "route" -> imm.getRoute().addCoding()
                .setSystem("http://terminology.hl7.org/CodeSystem/v3-RouteOfAdministration").setCode(value);
            case "site" -> imm.getSite().addCoding()
                .setSystem("http://terminology.hl7.org/CodeSystem/v3-ActSite").setCode(value);
            case "expirationDate" -> {
                Date d = parseDate(value);
                if (d != null) imm.setExpirationDate(d);
            }
            case "doseQuantity" -> imm.getDoseQuantity().setValue(new java.math.BigDecimal(value));
            case "meta.lastUpdated" -> imm.getMeta().setLastUpdatedElement(toInstant(value));
            default -> log.debug("Unhandled Immunization path: {}", m.getPath());
        }
    }
}
