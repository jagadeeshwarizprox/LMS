import com.sun.source.util.JavacTask;
import javax.tools.*;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Parses every source file with the real javac front end, without a classpath.
 * That gives exact syntax errors: unbalanced braces, bad generics, malformed
 * lambdas, switch arrows, records. It cannot check symbols, because the Spring
 * and Lombok jars are unreachable here, but syntax is what a structural script
 * can never see.
 */
public class ParseCheck {
    public static void main(String[] args) throws Exception {
        List<File> files = Files.walk(Paths.get(args[0]))
                .filter(p -> p.toString().endsWith(".java"))
                .map(Path::toFile).collect(Collectors.toList());

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        StandardJavaFileManager fm = compiler.getStandardFileManager(diags, null, null);

        JavacTask task = (JavacTask) compiler.getTask(
                null, fm, diags,
                List.of("-proc:none", "-Xmaxerrs", "10000"),
                null, fm.getJavaFileObjectsFromFiles(files));
        task.parse();          // parse only: no symbol resolution
        fm.close();

        int errors = 0;
        for (Diagnostic<? extends JavaFileObject> d : diags.getDiagnostics()) {
            if (d.getKind() != Diagnostic.Kind.ERROR) continue;
            errors++;
            String name = d.getSource() == null ? "?" : new File(d.getSource().getName()).getName();
            System.out.println(name + ":" + d.getLineNumber() + "  " + d.getMessage(null));
        }
        System.out.println("---");
        System.out.println("files parsed: " + files.size());
        System.out.println("syntax errors: " + errors);
    }
}
