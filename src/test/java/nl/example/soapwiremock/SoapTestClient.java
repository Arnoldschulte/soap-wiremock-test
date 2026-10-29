package nl.example.soapwiremock;

import nl.example.soapwiremock.soap.SoapMessages;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/** Minimale SOAP-client voor de tests, gebaseerd op java.net.http. */
public final class SoapTestClient {

    private final HttpClient http = HttpClient.newHttpClient();
    private final String endpointUrl;

    public SoapTestClient(String endpointUrl) {
        this.endpointUrl = endpointUrl;
    }

    public HttpResponse<String> call(String soapAction, String envelope) throws IOException, InterruptedException {
        return call(soapAction, envelope, Map.of());
    }

    public HttpResponse<String> call(String soapAction, String envelope, Map<String, String> extraHeaders)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpointUrl))
                .header("Content-Type", SoapMessages.CONTENT_TYPE)
                .header("SOAPAction", soapAction)
                .POST(HttpRequest.BodyPublishers.ofString(envelope));
        extraHeaders.forEach(builder::header);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
