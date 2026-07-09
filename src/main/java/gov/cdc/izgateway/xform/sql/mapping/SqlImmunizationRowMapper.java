package gov.cdc.izgateway.xform.sql.mapping;

import org.hl7.fhir.r4.model.*;
import java.util.Date;
import java.util.Map;

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
    protected void applyField(Immunization imm, ResourceMapping m, String value, Map<String, Object> row) {
        switch (m.getPath()) {
            case "identifier" -> {
                Identifier id = imm.addIdentifier();
                id.setSystem(m.getSystem());
                id.setValue(value);
            }
            case "vaccineCode.coding" -> {
                Object evaluated = MappingValueExpression.evaluate(m.getValue(), value, row);
                if (evaluated instanceof Coding c) {
                    imm.getVaccineCode().getCoding().add(c);
                }
            }
            case "occurrenceDateTime" -> imm.setOccurrence(toDateTime(value));
            case "manufacturer" -> {
                Object evaluated = MappingValueExpression.evaluate(m.getValue(), value, row);
                if (evaluated instanceof Reference ref) {
                    imm.setManufacturer(ref);
                } else {
                    imm.getManufacturer().setDisplay(value);
                }
            }
            case "lotNumber" -> imm.setLotNumber(value);
            case "expirationDate" -> {
                Date d = parseDate(value);
                if (d != null) imm.setExpirationDate(d);
            }
            case "site" -> {
                Object evaluated = MappingValueExpression.evaluate(m.getValue(), value, row);
                if (evaluated instanceof CodeableConcept cc) {
                    imm.setSite(cc);
                } else {
                    imm.getSite().addCoding().setSystem(m.getSystem()).setCode(value);
                }
            }
            case "route" -> {
                Object evaluated = MappingValueExpression.evaluate(m.getValue(), value, row);
                if (evaluated instanceof CodeableConcept cc) {
                    imm.setRoute(cc);
                } else {
                    imm.getRoute().addCoding().setSystem(m.getSystem()).setCode(value);
                }
            }
            case "doseQuantity" -> {
                Object evaluated = MappingValueExpression.evaluate(m.getValue(), value, row);
                if (evaluated instanceof Quantity q) {
                    imm.setDoseQuantity(q);
                } else {
                    imm.getDoseQuantity().setValue(new java.math.BigDecimal(value));
                }
            }
            case "primarySource" -> imm.setPrimarySource(toBoolean(value).getValue());
            case "reportOrigin" -> {
                Object evaluated = MappingValueExpression.evaluate(m.getValue(), value, row);
                if (evaluated instanceof CodeableConcept cc) {
                    imm.setReportOrigin(cc);
                } else {
                    imm.getReportOrigin().setText(value);
                }
            }
            case "fundingSource" -> {
                Object evaluated = MappingValueExpression.evaluate(m.getValue(), value, row);
                if (evaluated instanceof CodeableConcept cc) {
                    imm.setFundingSource(cc);
                } else {
                    imm.getFundingSource().setText(value);
                }
            }
            case "recorded" -> imm.setRecordedElement(toDateTime(value));
            case "protocolApplied.doseNumberString" -> imm.getProtocolAppliedFirstRep()
                .setDoseNumber(new StringType(value));
            case "protocolApplied.seriesDosesString" -> imm.getProtocolAppliedFirstRep()
                .setSeriesDoses(new StringType(value));
            case "meta.lastUpdated" -> imm.getMeta().setLastUpdatedElement(toInstant(value));
            default -> log.debug("Unhandled Immunization path: {}", m.getPath());
        }
    }
}
