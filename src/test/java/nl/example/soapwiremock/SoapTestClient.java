package nl.example.soapwiremock;

import nl.example.soapwiremock.soap.SoapMessages;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Minimale SOAP-client voor de tests, gebaseerd op java.net.http. */
final class SoapTestClient {

    private final HttpClient http = HttpClient.newHttpClient();
    private final String endpointUrl;

    SoapTestClient(String endpointUrl) {
        this.endpointUrl = endpointUrl;
    }

    HttpResponse<String> call(String soapAction, String envelope) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpointUrl))
                .header("Content-Type", SoapMessages.CONTENT_TYPE)
                .header("SOAPAction", soapAction)
                .POST(HttpRequest.BodyPublishers.ofString(envelope))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
