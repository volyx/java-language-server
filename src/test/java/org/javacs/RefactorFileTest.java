package org.javacs;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextEdit;
import org.junit.Test;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.net.URI;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;

public class RefactorFileTest {

    private static final Logger LOG = Logger.getLogger("main");
    private static final URI FAKE_FILE = URI.create("test/imaginary-resources/org/javacs/Example.java");

    @Test
    public void addImportToEmpty() {
        String before =
                "package org.javacs;\n" +
                "\n" +
                "public class Example { void main() { } }";
        List<TextEdit> edits = addImport(file(before), "org.javacs", "Foo");
        String after = applyEdits(before, edits);

        assertThat(after, equalTo(
                "package org.javacs;\n" +
                "\n" +
                "import org.javacs.Foo;\n" +
                "\n" +
                "public class Example { void main() { } }"
        ));
    }

    @Test
    public void addImportToExisting() {
        String before =
                "package org.javacs;\n" +
                "\n" +
                "import java.util.List;\n" +
                "\n" +
                "public class Example { void main() { } }";
        List<TextEdit> edits = addImport(file(before), "org.javacs", "Foo");
        String after = applyEdits(before, edits);

        assertThat(after, equalTo(
                "package org.javacs;\n" +
                "\n" +
                "import java.util.List;\n" +
                "import org.javacs.Foo;\n" +
                "\n" +
                "public class Example { void main() { } }"
        ));
    }

    @Test
    public void addImportAtBeginning() {
        String before =
                "package org.javacs;\n" +
                "\n" +
                "import org.javacs.Foo;\n" +
                "\n" +
                "public class Example { void main() { } }";
        List<TextEdit> edits = addImport(file(before), "java.util", "List");
        String after = applyEdits(before, edits);

        assertThat(after, equalTo(
                "package org.javacs;\n" +
                "\n" +
                "import java.util.List;\n" +
                "import org.javacs.Foo;\n" +
                "\n" +
                "public class Example { void main() { } }"
        ));
    }

    @Test
    public void importAlreadyExists() {
        String before =
                "package org.javacs;\n" +
                "\n" +
                "import java.util.List;\n" +
                "\n" +
                "public class Example { void main() { } }";
        List<TextEdit> edits = addImport(file(before), "java.util", "List");
        String after = applyEdits(before, edits);

        assertThat(after, equalTo(
                "package org.javacs;\n" +
                "\n" +
                "import java.util.List;\n" +
                "\n" +
                "public class Example { void main() { } }"
        ));
    }

    @Test
    public void noPackage() {
        String before =
                "import java.util.List;\n" +
                "\n" +
                "public class Example { void main() { } }";
        List<TextEdit> edits = addImport(file(before), "org.javacs", "Foo");
        String after = applyEdits(before, edits);

        assertThat(after, equalTo(
                "import java.util.List;\n" +
                "import org.javacs.Foo;\n" +
                "\n" +
                "public class Example { void main() { } }"
        ));
    }

    @Test
    public void noPackageNoImports() {
        String before = "public class Example { void main() { } }";
        List<TextEdit> edits = addImport(file(before), "org.javacs", "Foo");
        String after = applyEdits(before, edits);

        assertThat(after, equalTo(
                        "import org.javacs.Foo;\n" +
                        "\n" +
                        "public class Example { void main() { } }"
        ));
    }

    private List<TextEdit> addImport(ParseResult parse, String packageName, String className) {
        return new RefactorFile(parse.task, parse.tree).addImport(packageName, className);
    }

    private String applyEdits(String before, List<TextEdit> edits) {
        StringBuffer buffer = new StringBuffer(before);

        edits.stream()
                .sorted(this::compareEdits)
                .forEach(edit -> applyEdit(buffer, edit));

        return buffer.toString();
    }

    private int compareEdits(TextEdit left, TextEdit right) {
        int compareLines = -Integer.compare(left.getRange().getEnd().getLine(), right.getRange().getEnd().getLine());

        if (compareLines != 0)
            return compareLines;
        else
            return -Integer.compare(left.getRange().getStart().getCharacter(), right.getRange().getEnd().getCharacter());
    }

    private void applyEdit(StringBuffer buffer, TextEdit edit) {
        buffer.replace(offset(edit.getRange().getStart(), buffer), offset(edit.getRange().getEnd(), buffer), edit.getNewText());
    }

    private int offset(Position pos, StringBuffer buffer) {
        return (int) findOffset(buffer.toString(), pos.getLine(), pos.getCharacter());
    }

    public static long findOffset(String content, int targetLine, int targetCharacter) {
        try(Reader in = new StringReader(content)) {
            long offset = 0;
            int line = 0;
            int character = 0;

            while (line < targetLine) {
                int next = in.read();

                if (next < 0)
                    return offset;
                else {
                    offset++;

                    if (next == '\n')
                        line++;
                }
            }

            while (character < targetCharacter) {
                int next = in.read();

                if (next < 0)
                    return offset;
                else {
                    offset++;
                    character++;
                }
            }

            return offset;
        } catch (IOException e) {
            throw ShowMessageException.error(e.getMessage(), e);
        }
    }

    private ParseResult file(String content) {
        JavacHolder compiler = JavacHolder.create(
                Collections.singleton(Paths.get("src/test/test-project/workspace/src")),
                Collections.emptySet(),
                Paths.get("target/test-output")
        );

        return compiler.parse(FAKE_FILE, Optional.of(content), error -> LOG.warning(error.toString()));
    }
}
