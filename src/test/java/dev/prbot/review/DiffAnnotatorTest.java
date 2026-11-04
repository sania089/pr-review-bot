package dev.prbot.review;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DiffAnnotatorTest {

    private static final String DIFF = """
            diff --git a/src/Foo.java b/src/Foo.java
            index 111..222 100644
            --- a/src/Foo.java
            +++ b/src/Foo.java
            @@ -10,4 +10,5 @@ class Foo {
             int a = 1;
            -int b = 2;
            +int b = 3;
            +int c = 4;
             int d = 5;
            """;

    @Test
    void numbersAddedAndContextLinesInNewFile() {
        var result = DiffAnnotator.annotate(DIFF);

        assertTrue(result.text().contains("L10:  int a = 1;"));
        assertTrue(result.text().contains("L11: +int b = 3;"));
        assertTrue(result.text().contains("L12: +int c = 4;"));
        assertTrue(result.text().contains("L13:  int d = 5;"));
    }

    @Test
    void removedLinesAndOutsideLinesAreNotCommentable() {
        var result = DiffAnnotator.annotate(DIFF);

        assertTrue(result.canComment("src/Foo.java", 11));
        assertFalse(result.canComment("src/Foo.java", 99));
        assertFalse(result.canComment("src/Other.java", 11));
    }
}
