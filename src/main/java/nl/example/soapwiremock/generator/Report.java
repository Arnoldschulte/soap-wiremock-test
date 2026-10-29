package nl.example.soapwiremock.generator;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Wat de generator heeft gedaan: geschreven en overgeslagen bestanden en waarschuwingen. */
public final class Report {

    private final Path outputDir;
    private final List<String> written = new ArrayList<>();
    private final List<String> skipped = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    Report(Path outputDir) {
        this.outputDir = outputDir;
    }

    void written(String relativePath) {
        written.add(relativePath);
    }

    void skipped(String relativePath) {
        skipped.add(relativePath);
    }

    void warn(String warning) {
        warnings.add(warning);
    }

    public List<String> written() {
        return List.copyOf(written);
    }

    public List<String> skipped() {
        return List.copyOf(skipped);
    }

    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    public void print(PrintStream out) {
        out.println();
        out.println("Stubs gegenereerd in " + outputDir.toAbsolutePath().normalize());
        written.forEach(f -> out.println("  + " + f));
        out.printf("  geschreven:   %d%n", written.size());
        if (!skipped.isEmpty()) {
            out.printf("  overgeslagen: %d (bestaan al; gebruik -Doverwrite=true om te overschrijven)%n", skipped.size());
            skipped.forEach(f -> out.println("    = " + f));
        }
        if (!warnings.isEmpty()) {
            out.println();
            out.println("Waarschuwingen (" + warnings.size() + "):");
            warnings.forEach(w -> out.println("  ! " + w));
        }
        out.println();

        // In GitHub Actions: waarschuwingen ook als annotatie tonen
        if ("true".equals(System.getenv("GITHUB_ACTIONS"))) {
            warnings.forEach(w -> out.println("::warning title=StubGenerator::" + w.replace("\n", " ")));
            out.println("::notice title=StubGenerator::" + written.size() + " bestanden geschreven, "
                    + skipped.size() + " overgeslagen, " + warnings.size() + " waarschuwingen");
        }
    }
}
