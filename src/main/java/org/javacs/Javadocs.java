package org.javacs;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.overzealous.remark.Options;
import com.overzealous.remark.Remark;
import com.sun.javadoc.*;
import com.sun.source.util.JavacTask;
import com.sun.tools.javac.api.JavacTool;
import com.sun.tools.javac.file.JavacFileManager;
import com.sun.tools.javadoc.api.JavadocTool;
import org.eclipse.lsp4j.CompletionItem;

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.DocumentationTool;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.BreakIterator;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class Javadocs {

    /**
     * Cache for performance reasons
     */
    private final JavacFileManager actualFileManager;

    /**
     * Empty file manager we pass to javadoc to prevent it from roaming all over the place
     */
    private final JavacFileManager emptyFileManager = JavacTool.create().getStandardFileManager(Javadocs::onDiagnostic, null, null);

    /**
     * All the classes we have indexed so far
     */
    private final Map<String, RootDoc> topLevelClasses = new ConcurrentHashMap<>();

    private final Types types;

    private final Elements elements;

    Javadocs(Set<Path> sourcePath, Set<Path> docPath, Function<URI, Optional<String>> activeContent) {
        actualFileManager = createFileManager(allSourcePaths(sourcePath, docPath));

        JavacTask task = JavacTool.create().getTask(
                null,
                emptyFileManager, 
                Javadocs::onDiagnostic,
                null,
                null,
                null
        );

        types = task.getTypes();
        elements = task.getElements();
    }

    private static Set<File> allSourcePaths(Set<Path>... userSourcePath) {
        Set<File> allSourcePaths = new HashSet<>();

        // Add userSourcePath
        for (Set<Path> eachPath : userSourcePath) {
            for (Path each : eachPath)
                allSourcePaths.add(each.toFile());
        }

        // Add src.zip from JDK
        findSrcZip().ifPresent(allSourcePaths::add);

        return allSourcePaths;
    }

    private static JavacFileManager createFileManager(Set<File> allSourcePaths) {
        JavacFileManager actualFileManager = JavacTool.create().getStandardFileManager(Javadocs::onDiagnostic, null, null);

        try {
            actualFileManager.setLocation(StandardLocation.SOURCE_PATH, allSourcePaths);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return actualFileManager;
    }

    /**
     * Convert Javadoc HTML to Markdown
     */
    static String htmlToMarkdown(String commentText) {		
        Options options = new Options();

		options.tables = Options.Tables.CONVERT_TO_CODE_BLOCK;
		options.hardwraps = true;
		options.inlineLinks = true;
		options.autoLinks = true;
		options.reverseHtmlSmartPunctuation = true;

        return new Remark(options).convertFragment(commentText);
    }

    /**
     * Get docstring for method, using inherited method if necessary
     */
    static Optional<String> commentText(MethodDoc doc) {
        // TODO search interfaces as well
        
        while (doc != null && Strings.isNullOrEmpty(doc.commentText()))
            doc = doc.overriddenMethod();
        
        if (doc == null || Strings.isNullOrEmpty(doc.commentText()))
            return Optional.empty();
        else 
            return Optional.of(doc.commentText());
    }

    Optional<? extends ProgramElementDoc> doc(Element el) {
        if (el instanceof ExecutableElement) {
            ExecutableElement method = (ExecutableElement) el;

            return methodDoc(methodKey(method));
        }
        else if (el instanceof TypeElement) {
            TypeElement type = (TypeElement) el;

            return classDoc(type.getQualifiedName().toString());
        }
        else return Optional.empty();
    }

    String methodKey(ExecutableElement method) {
        TypeElement classElement = (TypeElement) method.getEnclosingElement();

        return String.format(
            "%s#%s(%s)",
            classElement.getQualifiedName(),
            method.getSimpleName(),
            paramsKey(method.getParameters())
        );
    }
    
    private String paramsKey(List<? extends VariableElement> params) {
        return params.stream()
            .map(this::paramType)
            .collect(Collectors.joining(","));
    }

    Optional<MethodDoc> methodDoc(String methodKey) {
        String className = methodKey.substring(0, methodKey.indexOf('#'));

        return classDoc(className)
                .flatMap(classDoc -> doMethodDoc(classDoc, methodKey));
    }

    private Optional<MethodDoc> doMethodDoc(ClassDoc classDoc, String methodKey) {
        for (MethodDoc each : classDoc.methods(false)) {
            if (methodMatches(methodKey, each))
                return Optional.of(each);
        }

        return Optional.empty();
    }

    private boolean methodMatches(String methodKey, MethodDoc doc) {
        String docKey = String.format(
            "%s#%s(%s)", 
            doc.containingClass().qualifiedName(), 
            doc.name(), 
            paramSignature(doc.parameters())
        );

        return docKey.equals(methodKey);
    }

    private String paramSignature(Parameter[] params) {
        return Arrays.stream(params)
            .map(this::docType)
            .collect(Collectors.joining(","));
    }

    private String paramType(VariableElement param) {
        return types.erasure(param.asType()).toString();
    }

    private String docType(Parameter doc) {
        return doc.type().qualifiedTypeName() + doc.type().dimension();
    }

    Optional<ConstructorDoc> constructorDoc(String methodKey) {
        String className = methodKey.substring(0, methodKey.indexOf('#'));

        return classDoc(className)
                .flatMap(classDoc -> doConstructorDoc(classDoc, methodKey));
    }

    private Optional<ConstructorDoc> doConstructorDoc(ClassDoc classDoc, String methodKey) {
        for (ConstructorDoc each : classDoc.constructors(false)) {
            if (constructorMatches(methodKey, each))
                return Optional.of(each);
        }

        return Optional.empty();
    }

    private boolean constructorMatches(String methodKey, ConstructorDoc doc) {
        String docKey = String.format(
            "%s#<init>(%s)", 
            doc.containingClass().qualifiedName(), 
            paramSignature(doc.parameters())
        );

        return docKey.equals(methodKey);
    }

    Optional<ClassDoc> classDoc(String className) {
        RootDoc index = index(className);

        return Optional.ofNullable(index.classNamed(className));
    }

    void update(JavaFileObject source) {
        LOG.info("Update javadocs for " + source.toUri());

        DocumentationTool.DocumentationTask task = new JavadocTool().getTask(
            null,
            emptyFileManager,
            Javadocs::onDiagnostic,
            Javadocs.class,
            ImmutableList.of("-private"),
            ImmutableList.of(source)
        );

        task.call();

        getSneakyReturn().ifPresent(root -> updateCache(root, source));
    }

    private void updateCache(RootDoc root, JavaFileObject source) {
        for (ClassDoc each : root.classes()) {
            if (source.isNameCompatible(each.simpleTypeName(), JavaFileObject.Kind.SOURCE)) {
                topLevelClasses.put(each.qualifiedName(), root);

                return;
            }
        }
    }

    /**
     * Get or compute the javadoc for `className`
     */
    RootDoc index(String className) {
        return topLevelClasses.computeIfAbsent(className, this::doIndex);
    }

    /**
     * Read all the Javadoc for `className`
     */
    private RootDoc doIndex(String className) {
        try {
            JavaFileObject source = actualFileManager.getJavaFileForInput(StandardLocation.SOURCE_PATH, className, JavaFileObject.Kind.SOURCE);

            if (source == null) {
                LOG.warning("No source file for " + className);

                return EmptyRootDoc.INSTANCE;
            }
            
            LOG.info("Found " + source.toUri() + " for " + className);

            DocumentationTool.DocumentationTask task = new JavadocTool().getTask(
                    null,
                    emptyFileManager,
                    Javadocs::onDiagnostic,
                    Javadocs.class,
                    null,
                    ImmutableList.of(source)
            );

            task.call();
        } catch (IOException e) {
            throw new RuntimeException();
        }

        return getSneakyReturn().orElse(EmptyRootDoc.INSTANCE);
    }

    private Optional<RootDoc> getSneakyReturn() {
        RootDoc result = sneakyReturn.get();
        sneakyReturn.remove();

        if (result == null) {
            LOG.warning("index did not return a RootDoc");

            return Optional.empty();
        }
        else return Optional.of(result);
    }

    /**
     * start(RootDoc) uses this to return its result to doIndex(...)
     */
    private static ThreadLocal<RootDoc> sneakyReturn = new ThreadLocal<>();

    /**
     * Called by the javadoc tool
     *
     * {@link Doclet}
     */
    public static boolean start(RootDoc root) {
        sneakyReturn.set(root);

        return true;
    }

    /**
     * Find the copy of src.zip that comes with the system-installed JDK
     */
    private static Optional<File> findSrcZip() {
        Path path = Paths.get(System.getProperty("java.home"));

        if (path.endsWith("jre"))
            path = path.getParent();

        path = path.resolve("src.zip");

        File file = path.toFile();

        if (file.exists())
            return Optional.of(file);
        else
            return Optional.empty();
    }

    private static void onDiagnostic(Diagnostic<? extends JavaFileObject> diagnostic) {
        Level level = level(diagnostic.getKind());
        String message = diagnostic.getMessage(null);

        LOG.log(level, message);
    }

    private static Level level(Diagnostic.Kind kind) {
        switch (kind) {
            case ERROR:
                return Level.SEVERE;
            case WARNING:
            case MANDATORY_WARNING:
                return Level.WARNING;
            case NOTE:
                return Level.INFO;
            case OTHER:
            default:
                return Level.FINE;
        }
    }

    public void resolveCompletionItem(CompletionItem unresolved) {
        if (unresolved.getData() == null || unresolved.getDocumentation() != null)
            return;
        
        String key = (String) unresolved.getData();

        LOG.info("Resolve javadoc for " + key);

        // my.package.MyClass#<init>()
        if (key.contains("<init>")) {
            constructorDoc(key)
                .ifPresent(doc -> unresolved.setDocumentation(Javadocs.htmlToMarkdown(doc.commentText())));
        }
        // my.package.MyClass#myMethod()
        else if (key.contains("#")) {
            methodDoc(key)
                .ifPresent(doc -> unresolved.setDocumentation(Javadocs.htmlToMarkdown(doc.commentText())));
        }
        // my.package.MyClass
        else {
            classDoc(key)
                .ifPresent(doc -> unresolved.setDocumentation(Javadocs.htmlToMarkdown(doc.commentText())));
        }
    }

    /**
     * Get the first sentence of a doc-comment.
     * 
     * In general, VS Code does a good job of only displaying the beginning of a doc-comment where appropriate.
     * But if VS Code is displaying too much and you want to only show the first sentence, use this.
     */
    public static String firstSentence(String doc) {
        BreakIterator breaks = BreakIterator.getSentenceInstance();

        breaks.setText(doc.replace('\n', ' '));

        int start = breaks.first(), end = breaks.next();

        return doc.substring(start, end).trim();
    }
    
    private static final Logger LOG = Logger.getLogger("main");

}
