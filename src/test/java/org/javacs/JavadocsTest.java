package org.javacs;

import com.sun.javadoc.MethodDoc;
import com.sun.javadoc.RootDoc;
import org.junit.Ignore;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Optional;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

public class JavadocsTest {

    private final Javadocs docs = new Javadocs(
            Collections.singleton(Paths.get("src/test/test-project/workspace/src")),
            Collections.emptySet(),
            __ -> Optional.empty()
    );

    @Test
    public void findSystemDoc() throws IOException {
        RootDoc root = docs.index("java.util.ArrayList");

        assertThat(root.classes(), not(emptyArray()));
    }

    @Test
    public void findMethodDoc() {
        assertTrue("Found method", docs.methodDoc("org.javacs.docs.TrickyDocstring#example(java.lang.String,java.lang.String[],java.util.List)").isPresent());
    }

    @Test
    public void findParameterizedDoc() {
        assertTrue("Found method", docs.methodDoc("org.javacs.docs.TrickyDocstring#parameterized(java.lang.Object)").isPresent());
    }

    @Test
    @Ignore // Blocked by emptyFileManager
    public void findInheritedDoc() {
        Optional<MethodDoc> found = docs.methodDoc("org.javacs.docs.SubDoc#method()");

        assertTrue("Found method", found.isPresent());

        Optional<String> docstring = found.flatMap(Javadocs::commentText);

        assertTrue("Has inherited doc", docstring.isPresent());
        assertThat("Inherited doc is not empty", docstring.get(), not(isEmptyOrNullString()));
    }

    @Test
    @Ignore // Doesn't work yet
    public void findInterfaceDoc() {
        Optional<MethodDoc> found = docs.methodDoc("org.javacs.docs.SubDoc#interfaceMethod()");

        assertTrue("Found method", found.isPresent());

        Optional<String> docstring = found.flatMap(Javadocs::commentText);

        assertTrue("Has inherited doc", docstring.isPresent());
        assertThat("Inherited doc is not empty", docstring.get(), not(isEmptyOrNullString()));
    }
}