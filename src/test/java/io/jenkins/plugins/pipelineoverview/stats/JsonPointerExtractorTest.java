package io.jenkins.plugins.pipelineoverview.stats;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonPointerExtractorTest {

    private static final long AT = 1_700_000_000_000L;

    @Test
    void scalarAtPointerIsReturnedAsNumber() throws IOException {
        StatValue v = JsonPointerExtractor.extract(
                "{\"total_count\":4}", "/total_count", JsonPointerExtractor.Mode.VALUE, AT);
        assertTrue(v.isNumeric());
        assertEquals(4.0, v.getNumeric());
        assertEquals("4", v.getDisplay());
        assertEquals(AT, v.getFetchedAt());
    }

    @Test
    void nestedPointerIsResolved() throws IOException {
        StatValue v = JsonPointerExtractor.extract(
                "{\"data\":{\"stats\":{\"open\":7}}}", "/data/stats/open",
                JsonPointerExtractor.Mode.VALUE, AT);
        assertEquals(7.0, v.getNumeric());
    }

    @Test
    void countOnArrayReturnsLength() throws IOException {
        StatValue v = JsonPointerExtractor.extract(
                "{\"items\":[{\"a\":1},{\"a\":2},{\"a\":3}]}", "/items",
                JsonPointerExtractor.Mode.COUNT, AT);
        assertEquals(3.0, v.getNumeric());
    }

    @Test
    void countOnObjectReturnsKeyCount() throws IOException {
        StatValue v = JsonPointerExtractor.extract(
                "{\"envs\":{\"a\":1,\"b\":2}}", "/envs", JsonPointerExtractor.Mode.COUNT, AT);
        assertEquals(2.0, v.getNumeric());
    }

    @Test
    void emptyPointerMeansDocumentRoot() throws IOException {
        StatValue v = JsonPointerExtractor.extract(
                "[10,20,30]", "", JsonPointerExtractor.Mode.COUNT, AT);
        assertEquals(3.0, v.getNumeric());
    }

    @Test
    void arrayIndexTokenIsSupported() throws IOException {
        StatValue v = JsonPointerExtractor.extract(
                "{\"rows\":[{\"n\":5},{\"n\":9}]}", "/rows/1/n",
                JsonPointerExtractor.Mode.VALUE, AT);
        assertEquals(9.0, v.getNumeric());
    }

    @Test
    void escapedPointerTokensAreDecoded() throws IOException {
        StatValue v = JsonPointerExtractor.extract(
                "{\"a/b\":{\"c~d\":2}}", "/a~1b/c~0d", JsonPointerExtractor.Mode.VALUE, AT);
        assertEquals(2.0, v.getNumeric());
    }

    @Test
    void numericStringIsTreatedAsNumber() throws IOException {
        StatValue v = JsonPointerExtractor.extract(
                "{\"count\":\"12\"}", "/count", JsonPointerExtractor.Mode.VALUE, AT);
        assertTrue(v.isNumeric());
        assertEquals(12.0, v.getNumeric());
    }

    @Test
    void nonNumericScalarBecomesText() throws IOException {
        StatValue v = JsonPointerExtractor.extract(
                "{\"cluster\":\"aws-test\"}", "/cluster", JsonPointerExtractor.Mode.VALUE, AT);
        assertFalse(v.isNumeric());
        assertEquals("aws-test", v.getDisplay());
    }

    @Test
    void fractionalValueKeepsOneDecimal() throws IOException {
        StatValue v = JsonPointerExtractor.extract(
                "{\"rate\":98.76}", "/rate", JsonPointerExtractor.Mode.VALUE, AT);
        assertEquals("98.8", v.getDisplay());
    }

    @Test
    void missingPointerIsAnError() {
        assertThrows(IOException.class, () -> JsonPointerExtractor.extract(
                "{\"total_count\":4}", "/nope", JsonPointerExtractor.Mode.VALUE, AT));
    }

    @Test
    void valueModeOnArrayIsAnError() {
        assertThrows(IOException.class, () -> JsonPointerExtractor.extract(
                "{\"items\":[1,2]}", "/items", JsonPointerExtractor.Mode.VALUE, AT));
    }

    @Test
    void countModeOnScalarIsAnError() {
        assertThrows(IOException.class, () -> JsonPointerExtractor.extract(
                "{\"total_count\":4}", "/total_count", JsonPointerExtractor.Mode.COUNT, AT));
    }

    @Test
    void pointerWithoutLeadingSlashIsAnError() {
        assertThrows(IOException.class, () -> JsonPointerExtractor.extract(
                "{\"a\":1}", "a", JsonPointerExtractor.Mode.VALUE, AT));
    }

    @Test
    void malformedBodyIsAnError() {
        assertThrows(IOException.class, () -> JsonPointerExtractor.extract(
                "<html>not json</html>", "/a", JsonPointerExtractor.Mode.VALUE, AT));
    }

    @Test
    void nullAtPointerIsAnError() {
        assertThrows(IOException.class, () -> JsonPointerExtractor.extract(
                "{\"a\":null}", "/a", JsonPointerExtractor.Mode.VALUE, AT));
    }
}
