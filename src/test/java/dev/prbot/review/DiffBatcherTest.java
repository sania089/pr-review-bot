package dev.prbot.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class DiffBatcherTest {

    private static FileDiff file(String path, int size) {
        return new FileDiff(path, "x".repeat(size));
    }

    @Test
    void groupsFilesInOrderUpToTheLimit() {
        List<List<FileDiff>> batches =
                DiffBatcher.batches(List.of(file("a", 40), file("b", 40), file("c", 40)), 100);

        assertThat(batches).hasSize(2);
        assertThat(batches.get(0)).extracting(FileDiff::path).containsExactly("a", "b");
        assertThat(batches.get(1)).extracting(FileDiff::path).containsExactly("c");
    }

    @Test
    void aFileOverTheLimitGetsItsOwnBatchAndIsNeverSplit() {
        List<List<FileDiff>> batches =
                DiffBatcher.batches(List.of(file("a", 10), file("big", 500), file("c", 10)), 100);

        assertThat(batches).hasSize(3);
        assertThat(batches.get(1)).extracting(FileDiff::path).containsExactly("big");
    }

    @Test
    void noFilesMeansNoBatches() {
        assertThat(DiffBatcher.batches(List.of(), 100)).isEmpty();
    }

    @Test
    void joinKeepsTheOriginalText() {
        assertThat(DiffBatcher.join(List.of(new FileDiff("a", "A\n"), new FileDiff("b", "B\n"))))
                .isEqualTo("A\nB\n");
    }
}
