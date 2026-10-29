package nl.example.soapwiremock.generator;

import nl.example.soapwiremock.generator.Wsdl.Endpoint;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Genereert WireMock-mappings en SOAP-responses ({@code mappings/} + {@code __files/})
 * plus een Dockerfile uit een WSDL en de XSD's die daarbij horen.
 * <p>
 * Alles draait lokaal: de WSDL en XSD's worden alleen gelezen vanaf de opgegeven paden
 * en er wordt niets gedownload of verstuurd.
 * <p>
 * Via Maven:
 * <pre>
 * mvn compile exec:java@generate-stubs -Dwsdl=local/MijnService.wsdl -Dout=../mijn-stubs
 * </pre>
 * Opties: {@code -Doverwrite=true} overschrijft bestaande bestanden,
 * {@code -Dimage=...} bepaalt de standaard base image in de Dockerfile.
 */
public final class StubGenerator {

    private static final String USAGE = """
            Gebruik:
              mvn compile exec:java@generate-stubs -Dwsdl=<pad naar WSDL> [-Dout=<map>] [-Doverwrite=true] [-Dimage=<docker image>]

              wsdl       WSDL 1.1-bestand; geïmporteerde XSD's worden relatief vanaf de WSDL gezocht
              out        doelmap, bijv. je stub-repo (standaard: target/stubs)
              overwrite  bestaande bestanden overschrijven (standaard: false)
              image      base image in de gegenereerde Dockerfile (standaard: wiremock/wiremock:3.13.1)
            """;

    private StubGenerator() {
    }

    public static void main(String[] args) {
        Map<String, String> options = parseArgs(args);
        String wsdl = options.getOrDefault("wsdl", "");
        if (wsdl.isBlank()) {
            System.err.println(USAGE);
            throw new GeneratorException("Geen WSDL opgegeven (-Dwsdl=...)");
        }
        Report report = generate(
                Path.of(wsdl),
                Path.of(options.getOrDefault("out", "target/stubs")),
                Boolean.parseBoolean(options.getOrDefault("overwrite", "false")),
                options.getOrDefault("image", "wiremock/wiremock:3.13.1"));
        report.print(System.out);
    }

    public static Report generate(Path wsdlFile, Path outputDir, boolean overwrite, String image) {
        if (!Files.isRegularFile(wsdlFile)) {
            throw new GeneratorException("WSDL niet gevonden: " + wsdlFile.toAbsolutePath());
        }
        Report report = new Report(outputDir);

        Wsdl wsdl = WsdlReader.read(wsdlFile);
        wsdl.warnings().forEach(report::warn);

        List<String> schemaWarnings = new ArrayList<>();
        SchemaSet schemas = SchemaSet.fromWsdl(wsdlFile, schemaWarnings);
        schemaWarnings.forEach(report::warn);

        StubWriter writer = new StubWriter(outputDir, overwrite, report);
        Map<Endpoint, String> folders = StubWriter.folders(wsdl.endpoints());
        folders.forEach((endpoint, folder) -> writer.writeEndpoint(endpoint, folder, schemas));
        writer.writeDockerfile(image);
        writer.writeReadme(wsdlFile);

        return report;
    }

    /** Accepteert {@code --key=value} en {@code --key value}. */
    static Map<String, String> parseArgs(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                throw new GeneratorException("Onbekend argument: " + arg + "\n" + USAGE);
            }
            int eq = arg.indexOf('=');
            if (eq > 0) {
                options.put(arg.substring(2, eq), arg.substring(eq + 1));
            } else if (i + 1 < args.length) {
                options.put(arg.substring(2), args[++i]);
            } else {
                throw new GeneratorException("Geen waarde voor " + arg + "\n" + USAGE);
            }
        }
        return options;
    }
}
