package gov.cdc.izgateway.xform.sql.mapping;

import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.ContactPoint;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Evaluates DSL value expressions from sql-mapping.yml.
 *
 * Syntax: TypeName{field: value, ...}
 *
 * Supported types: Coding, CodeableConcept, ContactPoint, Reference, Identifier, Quantity.
 *
 * Variable substitution:
 *   $value        -- the mapped column value (after concept_map)
 *   $row.COLUMN   -- raw value of any column in the current row
 *
 * Field values may be:
 *   'literal string'       -- unquoted after stripping the surrounding quotes
 *   $value / $row.COL      -- runtime variable
 *   TypeName{...}          -- nested expression (recursive)
 */
public class MappingValueExpression {

    private static final Logger log = LoggerFactory.getLogger(MappingValueExpression.class);

    private MappingValueExpression() {}

    /**
     * Evaluates an expression and returns the constructed FHIR object, or null if the
     * expression is blank or cannot be evaluated (e.g., null mapped value).
     */
    public static Object evaluate(String expression, String mappedValue, Map<String, Object> row) {
        if (expression == null || expression.isBlank()) return null;

        String expr = expression.trim();
        int brace = expr.indexOf('{');
        if (brace < 0) {
            // Plain variable or literal with no constructor -- treat as string
            return resolveScalar(expr, mappedValue, row);
        }

        String typeName = expr.substring(0, brace).trim();
        int closingBrace = lastMatchingBrace(expr, brace);
        if (closingBrace < 0) {
            log.warn("Unmatched braces in expression: {}", expression);
            return null;
        }
        String inner = expr.substring(brace + 1, closingBrace).trim();
        Map<String, String> fields = parseFields(inner, mappedValue, row);

        return switch (typeName) {
            case "Coding" -> buildCoding(fields, mappedValue, row);
            case "CodeableConcept" -> buildCodeableConcept(fields, mappedValue, row);
            case "ContactPoint" -> buildContactPoint(fields, mappedValue, row);
            case "Reference" -> buildReference(fields, mappedValue, row);
            case "Identifier" -> buildIdentifier(fields, mappedValue, row);
            case "Quantity" -> buildQuantity(fields, mappedValue, row);
            default -> {
                log.warn("Unknown expression type: {}", typeName);
                yield null;
            }
        };
    }

    // -- Builders ---------------------------------------------------------------

    private static Coding buildCoding(Map<String, String> fields, String mv, Map<String, Object> row) {
        Coding c = new Coding();
        if (fields.containsKey("system")) c.setSystem(resolveScalar(fields.get("system"), mv, row));
        if (fields.containsKey("code"))   c.setCode(resolveScalar(fields.get("code"), mv, row));
        if (fields.containsKey("display")) c.setDisplay(resolveScalar(fields.get("display"), mv, row));
        return c;
    }

    private static CodeableConcept buildCodeableConcept(Map<String, String> fields, String mv, Map<String, Object> row) {
        CodeableConcept cc = new CodeableConcept();
        if (fields.containsKey("coding")) {
            Object nested = evaluate(fields.get("coding"), mv, row);
            if (nested instanceof Coding c) cc.addCoding(c);
        }
        if (fields.containsKey("text")) cc.setText(resolveScalar(fields.get("text"), mv, row));
        return cc;
    }

    private static ContactPoint buildContactPoint(Map<String, String> fields, String mv, Map<String, Object> row) {
        ContactPoint cp = new ContactPoint();
        if (fields.containsKey("system")) {
            String sys = resolveScalar(fields.get("system"), mv, row);
            if (sys != null) {
                try { cp.setSystem(ContactPoint.ContactPointSystem.fromCode(sys)); }
                catch (Exception e) { log.warn("Unknown ContactPoint system: {}", sys); }
            }
        }
        if (fields.containsKey("value")) cp.setValue(resolveScalar(fields.get("value"), mv, row));
        return cp;
    }

    private static Reference buildReference(Map<String, String> fields, String mv, Map<String, Object> row) {
        Reference ref = new Reference();
        if (fields.containsKey("display")) ref.setDisplay(resolveScalar(fields.get("display"), mv, row));
        if (fields.containsKey("reference")) ref.setReference(resolveScalar(fields.get("reference"), mv, row));
        if (fields.containsKey("identifier")) {
            Object nested = evaluate(fields.get("identifier"), mv, row);
            if (nested instanceof Identifier id) ref.setIdentifier(id);
        }
        return ref;
    }

    private static Identifier buildIdentifier(Map<String, String> fields, String mv, Map<String, Object> row) {
        Identifier id = new Identifier();
        if (fields.containsKey("system")) id.setSystem(resolveScalar(fields.get("system"), mv, row));
        if (fields.containsKey("value"))  id.setValue(resolveScalar(fields.get("value"), mv, row));
        return id;
    }

    private static Quantity buildQuantity(Map<String, String> fields, String mv, Map<String, Object> row) {
        Quantity q = new Quantity();
        if (fields.containsKey("value")) {
            String raw = resolveScalar(fields.get("value"), mv, row);
            if (raw != null) {
                try { q.setValue(new BigDecimal(raw)); }
                catch (NumberFormatException e) { log.warn("Non-numeric Quantity value: {}", raw); }
            }
        }
        if (fields.containsKey("unit"))   q.setUnit(resolveScalar(fields.get("unit"), mv, row));
        if (fields.containsKey("system")) q.setSystem(resolveScalar(fields.get("system"), mv, row));
        if (fields.containsKey("code"))   q.setCode(resolveScalar(fields.get("code"), mv, row));
        return q;
    }

    // -- Parsing ----------------------------------------------------------------

    /**
     * Resolves a single scalar token (literal, $value, or $row.COL) to its string value.
     * Quoted literals have their surrounding single quotes stripped.
     */
    static String resolveScalar(String token, String mappedValue, Map<String, Object> row) {
        if (token == null) return null;
        String t = token.trim();
        if (t.startsWith("'") && t.endsWith("'")) return t.substring(1, t.length() - 1);
        if (t.equals("$value")) return mappedValue;
        if (t.startsWith("$row.")) {
            String col = t.substring(5);
            Object v = row == null ? null : row.get(col);
            return v == null ? null : v.toString();
        }
        return t;
    }

    /**
     * Parses the inner content of a TypeName{...} into a name->raw-token map.
     * Raw token values are NOT yet resolved -- callers pass them to resolveScalar or evaluate.
     */
    private static Map<String, String> parseFields(String inner, String mv, Map<String, Object> row) {
        Map<String, String> result = new LinkedHashMap<>();
        int i = 0;
        int len = inner.length();
        while (i < len) {
            // skip commas and whitespace between fields
            while (i < len && (inner.charAt(i) == ',' || Character.isWhitespace(inner.charAt(i)))) i++;
            if (i >= len) break;

            // read field name (up to the next ':')
            int colon = inner.indexOf(':', i);
            if (colon < 0) break;
            String name = inner.substring(i, colon).trim();
            i = colon + 1;

            // skip whitespace after colon
            while (i < len && Character.isWhitespace(inner.charAt(i))) i++;
            if (i >= len) break;

            // read field value token
            String token;
            char ch = inner.charAt(i);
            if (ch == '\'') {
                // quoted literal: find closing quote
                int end = inner.indexOf('\'', i + 1);
                if (end < 0) end = len - 1;
                token = inner.substring(i, end + 1);
                i = end + 1;
            } else if (ch == '$') {
                // variable: read to next comma, closing brace, or end
                int end = i;
                while (end < len && inner.charAt(end) != ',' && inner.charAt(end) != '}') end++;
                token = inner.substring(i, end).trim();
                i = end;
            } else {
                // TypeName{...} or plain identifier: scan depth-0 boundary
                int depth = 0;
                int j = i;
                while (j < len) {
                    char c = inner.charAt(j);
                    if (c == '{') depth++;
                    else if (c == '}') {
                        depth--;
                        if (depth < 0) { j--; break; } // unmatched: stop before it
                    } else if (c == ',' && depth == 0) break;
                    j++;
                }
                token = inner.substring(i, j).trim();
                i = j;
            }

            if (!name.isEmpty()) result.put(name, token);
        }
        return result;
    }

    /**
     * Returns the index of the closing brace that matches the opening brace at {@code openPos}.
     * Returns -1 if not found.
     */
    private static int lastMatchingBrace(String s, int openPos) {
        int depth = 0;
        for (int i = openPos; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }
}
