package gov.cdc.izgateway.xform.sql;

import java.util.Map;

public final class PatientSearchResult {

    public enum Outcome { MATCH, NO_MATCH, AMBIGUOUS }

    private final Outcome outcome;
    private final Map<String, Object> matchedRow;

    private PatientSearchResult(Outcome outcome, Map<String, Object> matchedRow) {
        this.outcome = outcome;
        this.matchedRow = matchedRow;
    }

    public static PatientSearchResult match(Map<String, Object> row) {
        return new PatientSearchResult(Outcome.MATCH, row);
    }

    public static PatientSearchResult noMatch() {
        return new PatientSearchResult(Outcome.NO_MATCH, null);
    }

    public static PatientSearchResult ambiguous() {
        return new PatientSearchResult(Outcome.AMBIGUOUS, null);
    }

    public Outcome getOutcome() { return outcome; }
    public Map<String, Object> getMatchedRow() { return matchedRow; }
    public boolean isMatch() { return outcome == Outcome.MATCH; }
    public boolean isAmbiguous() { return outcome == Outcome.AMBIGUOUS; }
}
