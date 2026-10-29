package nl.example.soapwiremock.generator;

import nl.example.soapwiremock.generator.Wsdl.Endpoint;
import nl.example.soapwiremock.generator.Wsdl.Fault;
import nl.example.soapwiremock.generator.Wsdl.Operation;
import nl.example.soapwiremock.generator.Wsdl.SoapVersion;

import javax.xml.namespace.QName;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Schrijft per operatie een WireMock-mapping en een SOAP-response in de standaardindeling:
 * <pre>
 * out/
 * ├── Dockerfile
 * ├── README.md
 * ├── mappings/&lt;Service&gt;/&lt;Operatie&gt;.json
 * ├── mappings/&lt;Service&gt;/&lt;Operatie&gt;_&lt;Fault&gt;.json
 * └── __files/&lt;Service&gt;/&lt;Operatie&gt;Response.xml, &lt;Operatie&gt;_&lt;Fault&gt;.xml
 * </pre>
 * Bestaande bestanden worden alleen overschreven als {@code overwrite} aan staat.
 */
final class StubWriter {

    /** Header waarmee een client een fault-response kan afdwingen, bijv. {@code X-Mock-Fault: CustomerNotFound}. */
    static final String FAULT_HEADER = "X-Mock-Fault";

    private static final int PRIORITY_DEFAULT = 10;
    private static final int PRIORITY_FAULT = 1;

    private final Path out;
    private final boolean overwrite;
    private final Report report;

    StubWriter(Path out, boolean overwrite, Report report) {
        this.out = out;
        this.overwrite = overwrite;
        this.report = report;
    }

    void writeEndpoint(Endpoint endpoint, String folder, SchemaSet schemas) {
        for (Operation operation : endpoint.operations()) {
            writeOperation(endpoint, folder, operation, schemas);
        }
    }

    void writeDockerfile(String image) {
        write("Dockerfile", """
                # Gegenereerd door soap-wiremock-test (StubGenerator).
                #
                # Bouwen:  docker build -t soap-stubs .
                # Draaien: docker run --rm -p 8080:8080 soap-stubs
                #
                # Andere base image (bijv. uit een interne registry):
                #   docker build --build-arg WIREMOCK_IMAGE=registry.example.nl/wiremock/wiremock:2.32.0 -t soap-stubs .
                ARG WIREMOCK_IMAGE=%s
                FROM ${WIREMOCK_IMAGE}

                COPY mappings /home/wiremock/mappings
                COPY __files /home/wiremock/__files

                # Werkt in WireMock 2.x en 3.x; nodig zodra je {{...}}-templates in responses gebruikt.
                CMD ["--global-response-templating", "--verbose"]
                """.formatted(image));
    }

    void writeReadme(Path wsdl) {
        write("README.md", """
                # SOAP-stubs

                WireMock-stubs gegenereerd uit `%s` met de StubGenerator uit *soap-wiremock-test*.

                ## Draaien

                ```bash
                docker build -t soap-stubs .
                docker run --rm -p 8080:8080 soap-stubs
                ```

                ## Wat er per operatie is gegenereerd

                - `mappings/<Service>/<Operatie>.json`: matcht op endpoint, SOAPAction en request-element (priority %d).
                - `__files/<Service>/<Operatie>Response.xml`: voorbeeldresponse op basis van de XSD's.
                - Per fault: `<Operatie>_<Fault>.json` + `.xml`. Deze mapping (priority %d) reageert alleen
                  als het request de header `%s: <Fault>` meestuurt.

                ## Aanpassen

                - Vervang de voorbeeldwaarden in `__files` door realistische testdata.
                - Extra scenario's: kopieer een mapping, geef hem een lagere priority (bijv. 5) en voeg een
                  `bodyPatterns`-regel toe die op een waarde in het request matcht, bijvoorbeeld:
                  `{ "matchesXPath": "//*[local-name()='customerId'][text()='12345']" }`
                - Opnieuw genereren overschrijft bestaande bestanden niet, tenzij met `-Doverwrite=true`.
                """.formatted(wsdl.getFileName(), PRIORITY_DEFAULT, PRIORITY_FAULT, FAULT_HEADER));
    }

    // ------------------------------------------------------------------ per operatie

    private void writeOperation(Endpoint endpoint, String folder, Operation operation, SchemaSet schemas) {
        SoapVersion soap = endpoint.soapVersion();
        String base = safe(operation.name());

        // Default-response
        Map<String, Object> response = new LinkedHashMap<>();
        if (operation.isOneWay()) {
            response.put("status", 202);
        } else {
            String bodyFile = folder + "/" + base + "Response.xml";
            String payload = sampleAndValidate(schemas, operation.outputElement(), "__files/" + bodyFile);
            write("__files/" + bodyFile, SoapEnvelopes.envelope(soap, payload));
            response.put("status", 200);
            response.put("headers", Map.of("Content-Type", soap.contentType()));
            response.put("bodyFileName", bodyFile);
        }
        Map<String, Object> mapping = mapping(endpoint.serviceName() + " - " + operation.name(),
                PRIORITY_DEFAULT, request(endpoint, operation, null), response);
        write("mappings/" + folder + "/" + base + ".json", Json.write(mapping));

        // Faults: alleen actief met de X-Mock-Fault-header
        for (Fault fault : operation.faults()) {
            String faultBase = base + "_" + safe(fault.name());
            String bodyFile = folder + "/" + faultBase + ".xml";
            String detail = sampleAndValidate(schemas, fault.element(), "__files/" + bodyFile);
            write("__files/" + bodyFile, SoapEnvelopes.fault(soap, fault.name(), detail));

            Map<String, Object> faultResponse = new LinkedHashMap<>();
            faultResponse.put("status", 500);
            faultResponse.put("headers", Map.of("Content-Type", soap.contentType()));
            faultResponse.put("bodyFileName", bodyFile);
            Map<String, Object> faultMapping = mapping(
                    endpoint.serviceName() + " - " + operation.name() + " - fault " + fault.name(),
                    PRIORITY_FAULT, request(endpoint, operation, fault.name()), faultResponse);
            write("mappings/" + folder + "/" + faultBase + ".json", Json.write(faultMapping));
        }
    }

    private static Map<String, Object> mapping(String name, int priority,
                                               Map<String, Object> request, Map<String, Object> response) {
        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("name", name);
        mapping.put("priority", priority);
        mapping.put("request", request);
        mapping.put("response", response);
        return mapping;
    }

    private static Map<String, Object> request(Endpoint endpoint, Operation operation, String faultName) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("method", "POST");
        if (endpoint.path() != null) {
            request.put("urlPath", endpoint.path());
        }

        Map<String, Object> headers = new LinkedHashMap<>();
        String action = operation.soapAction();
        if (action != null && !action.isBlank()) {
            if (endpoint.soapVersion() == SoapVersion.SOAP_11) {
                // Clients sturen de action soms met en soms zonder aanhalingstekens
                headers.put("SOAPAction", Map.of("matches", "^\"?" + escapeRegex(action) + "\"?$"));
            } else {
                headers.put("Content-Type", Map.of("matches", ".*action=\"?" + escapeRegex(action) + "\"?.*"));
            }
        }
        if (faultName != null) {
            headers.put(FAULT_HEADER, Map.of("equalTo", faultName));
        }
        if (!headers.isEmpty()) {
            request.put("headers", headers);
        }

        QName input = operation.inputElement();
        Map<String, Object> bodyPattern = new LinkedHashMap<>();
        if (input.getNamespaceURI().isEmpty()) {
            bodyPattern.put("matchesXPath", "//" + input.getLocalPart());
        } else {
            bodyPattern.put("matchesXPath", "//req:" + input.getLocalPart());
            bodyPattern.put("xPathNamespaces", Map.of("req", input.getNamespaceURI()));
        }
        request.put("bodyPatterns", List.of(bodyPattern));
        return request;
    }

    private String sampleAndValidate(SchemaSet schemas, QName element, String file) {
        String sample = schemas.sample(element);
        List<String> errors = schemas.validate(sample);
        for (String error : errors) {
            report.warn(file + ": " + error + " (pas de voorbeeldwaarde aan)");
        }
        return sample;
    }

    private void write(String relativePath, String content) {
        Path target = out.resolve(relativePath);
        try {
            if (Files.exists(target) && !overwrite) {
                report.skipped(relativePath);
                return;
            }
            Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8);
            report.written(relativePath);
        } catch (IOException e) {
            throw new GeneratorException("Kan niet schrijven naar " + target, e);
        }
    }

    static String safe(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String escapeRegex(String text) {
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if ("\\.[]{}()<>*+-=!?^$|".indexOf(c) >= 0) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** Groepeert endpoints: één map per service, of service/port als een service meerdere ports heeft. */
    static Map<Endpoint, String> folders(List<Endpoint> endpoints) {
        Map<String, List<Endpoint>> byService = new LinkedHashMap<>();
        for (Endpoint e : endpoints) {
            byService.computeIfAbsent(e.serviceName(), k -> new ArrayList<>()).add(e);
        }
        Map<Endpoint, String> folders = new LinkedHashMap<>();
        byService.forEach((service, list) -> {
            for (Endpoint e : list) {
                folders.put(e, list.size() == 1 ? safe(service) : safe(service) + "/" + safe(e.portName()));
            }
        });
        return folders;
    }
}
