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
                id.setSystem(m.getSystem());
                id.setValue(value);
            }
            case "vaccineCode", "vaccineCode.cvx", "vaccineCode.ndc" ->
                imm.getVaccineCode().addCoding().setSystem(m.getSystem()).setCode(value);
            case "occurrenceDateTime" -> imm.setOccurrence(toDateTime(value));
            case "manufacturer" -> imm.getManufacturer()
                .setIdentifier(new Identifier().setSystem(m.getSystem()).setValue(value));
            case "lotNumber" -> imm.setLotNumber(value);
            case "expirationDate" -> {
                Date d = parseDate(value);
                if (d != null) imm.setExpirationDate(d);
            }
            case "site" -> imm.getSite().addCoding().setSystem(m.getSystem()).setCode(value);
            case "route" -> imm.getRoute().addCoding().setSystem(m.getSystem()).setCode(value);
            case "doseQuantity" -> imm.getDoseQuantity().setValue(new java.math.BigDecimal(value));
            case "primarySource" -> imm.setPrimarySource(toBoolean(value).getValue());
            case "reportOrigin" -> imm.getReportOrigin().setText(value);
            case "fundingSource" -> imm.getFundingSource().setText(value);
            case "recorded" -> imm.setRecordedElement(toDateTime(value));
            case "protocolApplied.doseNumber" -> imm.getProtocolAppliedFirstRep()
                .setDoseNumber(new StringType(value));
            case "protocolApplied.seriesDosesString" -> imm.getProtocolAppliedFirstRep()
                .setSeriesDoses(new StringType(value));
            case "meta.lastUpdated" -> imm.getMeta().setLastUpdatedElement(toInstant(value));
            default -> log.debug("Unhandled Immunization path: {}", m.getPath());
        }
    }
}
