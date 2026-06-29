package gov.cdc.izgateway.xform.sql.mapping;

import org.hl7.fhir.r4.model.*;
import java.util.List;
import java.util.Map;

/**
 * Assembles a FHIR searchset Bundle from tabular SQL rows using registered
 * SqlTableMapper instances. Contains no type conversion logic of its own.
 */
public class TabularFhirConverter {

    private final SqlPatientRowMapper patientMapper;
    private final SqlImmunizationRowMapper immunizationMapper;

    public TabularFhirConverter(SqlPatientRowMapper patientMapper,
                                SqlImmunizationRowMapper immunizationMapper) {
        this.patientMapper = patientMapper;
        this.immunizationMapper = immunizationMapper;
    }

    public Bundle toBundle(Patient patient, List<Map<String, Object>> immunizationRows) {
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);

        Bundle.BundleEntryComponent patientEntry = bundle.addEntry();
        patientEntry.setResource(patient);
        patientEntry.getSearch().setMode(Bundle.SearchEntryMode.MATCH);

        Reference patientRef = new Reference("Patient/" + patient.getIdElement().getIdPart());

        patientMapper.accumulateAtoAddresses(patient, immunizationRows);

        for (Map<String, Object> row : immunizationRows) {
            Immunization imm = immunizationMapper.map(row);
            imm.setPatient(patientRef);
            Bundle.BundleEntryComponent entry = bundle.addEntry();
            entry.setResource(imm);
            entry.getSearch().setMode(Bundle.SearchEntryMode.MATCH);
        }

        bundle.setTotal(bundle.getEntry().size());
        return bundle;
    }
}
