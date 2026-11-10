package dev.prbot.review;

import static org.assertj.core.api.Assertions.assertThat;

import dev.prbot.review.RepoConfig.Strictness;
import java.util.List;
import org.junit.jupiter.api.Test;

class RepoConfigTest {

    @Test
    void missingOrEmptyFileMeansDefaultsWithoutWarning() {
        for (String yaml : new String[] {null, "", "  \n", "# only a comment\n"}) {
            RepoConfig.Loaded loaded = RepoConfig.parse(yaml);
            assertThat(loaded.config()).isEqualTo(RepoConfig.DEFAULTS);
            assertThat(loaded.warning()).isNull();
        }
    }

    @Test
    void readsIgnoreAndStrictness() {
        RepoConfig.Loaded loaded =
                RepoConfig.parse("ignore:\n  - docs/\n  - \"*.md\"\nstrictness: HIGH\n");

        assertThat(loaded.warning()).isNull();
        assertThat(loaded.config().ignore()).containsExactly("docs/", "*.md");
        assertThat(loaded.config().strictness()).isEqualTo(Strictness.HIGH);
    }

    @Test
    void anyProblemFallsBackToDefaultsAndExplainsWhy() {
        for (String bad :
                List.of(
                        "strictness: paranoid",
                        "strictness: [low]",
                        "ignore: docs/",
                        "ignore: [1, 2]",
                        "ignor: [docs/]",
                        "- just\n- a list",
                        "key: [unclosed",
                        "!!java.lang.Runtime {}",
                        "ignore: [a]\nignore: [b]")) {
            RepoConfig.Loaded loaded = RepoConfig.parse(bad);
            assertThat(loaded.config()).as(bad).isEqualTo(RepoConfig.DEFAULTS);
            assertThat(loaded.warning()).as(bad).contains(".prbot.yml").contains("defaults");
        }
    }

    @Test
    void warningDoesNotEchoTheFileContent() {
        RepoConfig.Loaded loaded = RepoConfig.parse("key: [unclosed secret-value");

        assertThat(loaded.warning()).doesNotContain("secret-value");
    }

    @Test
    void rejectsTooManyPatternsAndOversizedFiles() {
        String many = "ignore:\n" + "  - x\n".repeat(RepoConfig.MAX_PATTERNS + 1);
        String huge = "ignore:\n  - x\n#" + "a".repeat(RepoConfig.MAX_FILE_CHARS);

        assertThat(RepoConfig.parse(many).warning()).isNotNull();
        assertThat(RepoConfig.parse(huge).warning()).isNotNull();
    }

    private static boolean ignored(String pattern, String path) {
        return new RepoConfig(List.of(pattern), Strictness.NORMAL).ignores(path);
    }

    @Test
    void globsFollowGitignoreLikeRules() {
        // no slash: any depth
        assertThat(ignored("*.md", "README.md")).isTrue();
        assertThat(ignored("*.md", "docs/a/b.md")).isTrue();
        assertThat(ignored("*.md", "README.mdx")).isFalse();
        // a matching directory covers everything below it
        assertThat(ignored("docs/", "docs/a/b.txt")).isTrue();
        assertThat(ignored("docs", "docs/a.txt")).isTrue();
        assertThat(ignored("docs/", "src/docs/a.txt")).isTrue(); // no inner slash: any depth
        assertThat(ignored("/docs", "src/docs/a.txt")).isFalse(); // leading slash: root only
        assertThat(ignored("src/docs", "other/src/docs/a.txt")).isFalse();
        // * stays in one directory, ** crosses them
        assertThat(ignored("src/*.java", "src/A.java")).isTrue();
        assertThat(ignored("src/*.java", "src/x/A.java")).isFalse();
        assertThat(ignored("src/**/A.java", "src/A.java")).isTrue();
        assertThat(ignored("src/**/A.java", "src/x/y/A.java")).isTrue();
        assertThat(ignored("**/generated/**", "a/generated/b/C.java")).isTrue();
        // special regex characters are literal
        assertThat(ignored("a+b(1).txt", "a+b(1).txt")).isTrue();
        assertThat(ignored("a.txt", "aXtxt")).isFalse();
        assertThat(ignored("file?.txt", "file1.txt")).isTrue();
        assertThat(ignored("/build", "build/out.js")).isTrue();
    }

    @Test
    void strictnessMapsToSeverityAndCap() {
        assertThat(Strictness.LOW.allows("high")).isTrue();
        assertThat(Strictness.LOW.allows("medium")).isFalse();
        assertThat(Strictness.NORMAL.allows("medium")).isTrue();
        assertThat(Strictness.NORMAL.allows("low")).isFalse();
        assertThat(Strictness.HIGH.allows("low")).isTrue();
        assertThat(Strictness.HIGH.allows("surprise")).isTrue(); // unknown counts as lowest
        assertThat(Strictness.LOW.maxComments()).isLessThan(Strictness.HIGH.maxComments());
    }
}
