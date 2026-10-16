package github.freshchromatic.catbud_addons.formatting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.source.util.JavacTask;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;

class JavaFormatterTest {

    private final JavaFormatter formatter = new JavaFormatter(Path.of("../java-format.properties"));

    JavaFormatterTest() throws Exception {
    }

    @Test
    void alignsRelatedFieldsWithoutExtendingAcrossBlankLines() throws Exception {
        String source = """
            class Example {
                public static final String DIR_WORLDS = "/worlds/";
                public static final String DIR_INVENTORIES = "/inventories/";

                private final Map<String, ChunkGenerator> generatorMap;
                private final Map<String, WorldInventories> inventoryMap;
            }
            """;
        String result = this.formatter.format(source);
        assertTrue(result.contains("public static final String DIR_WORLDS      = \"/worlds/\";"));
        assertTrue(result.contains("private final Map<String, ChunkGenerator>   generatorMap;"));
        assertTrue(result.contains("private final Map<String, WorldInventories> inventoryMap;"));
        assertEquals(result, this.formatter.format(result));
    }

    @Test
    void keepsSimpleInitializersAndOuterCallsTogether() throws Exception {
        String source = """
            class Example {
                void render() {
                    LevelChunk chunk =
                        level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                    rows.add(
                        SettingsAddon.StatusRow.info(tr("phase"), () -> tr("phase." + session.phaseKey())));
                }
            }
            """;
        String result = this.formatter.format(source);
        assertTrue(result.contains(
            "LevelChunk chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);"));
        assertTrue(result.contains("rows.add(SettingsAddon.StatusRow.info("));
        assertFalse(result.contains("rows.add(\n"));
    }

    @Test
    void keepsMultilineInitializationWithItsDeclaration() throws Exception {
        String source = """
            class Example {
                void configure() {
                    var property =
                        this.addProperty(ConfigTypes.STRING_LIST,
                            "PlayerInfo.Format",
                            List.of("first", "second"),
                            () -> {
                                updateState();
                                publishState();
                            });
                }
            }
            """;
        String result = this.formatter.format(source);
        assertTrue(result.contains("var property = this.addProperty("));
        assertEquals(syntax(source), syntax(result));
        assertEquals(result, this.formatter.format(result));
    }

    @Test
    void preservesModernJavaSyntaxCommentsAndLineEndings() throws Exception {
        String source = """
            record Example(int count, String label) {
                // Why this value matters.
                String value() {
                    return switch (count) {
                        case 0 -> "empty";
                        default -> label + "=" + count;
                    };
                }
            }
            """.replace("\n", "\r\n");
        String result = this.formatter.format(source);
        assertEquals(syntax(source), syntax(result));
        assertTrue(result.contains("// Why this value matters."));
        assertFalse(result.replace("\r\n", "").contains("\n"));
        assertEquals(result, this.formatter.format(result));
    }

    private String syntax(String source) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            var input = new SimpleJavaFileObject(URI.create("string:///Example.java"), JavaFileObject.Kind.SOURCE) {

                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return source;
                }
            };
            var task = (JavacTask) compiler.getTask(null, manager, null, List.of("-proc:none"), null, List.of(input));
            return task.parse().iterator().next().toString();
        }
    }
}
