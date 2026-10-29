package nl.example.soapwiremock.generator;

import javax.xml.namespace.QName;
import java.util.List;

/** Het deel van een WSDL dat nodig is om WireMock-stubs te maken. */
public record Wsdl(List<Endpoint> endpoints, List<String> warnings) {

    /** Een SOAP-port uit {@code <wsdl:service>}: één endpoint met de operaties van zijn binding. */
    public record Endpoint(String serviceName,
                           String portName,
                           String path,            // null = matcht op elke URL
                           SoapVersion soapVersion,
                           List<Operation> operations) {
    }

    public record Operation(String name,
                            String soapAction,     // leeg = geen action
                            QName inputElement,
                            QName outputElement,   // null = one-way operatie
                            List<Fault> faults) {

        public boolean isOneWay() {
            return outputElement == null;
        }
    }

    public record Fault(String name, QName element) {
    }

    public enum SoapVersion {
        SOAP_11("http://schemas.xmlsoap.org/soap/envelope/", "text/xml; charset=UTF-8"),
        SOAP_12("http://www.w3.org/2003/05/soap-envelope", "application/soap+xml; charset=UTF-8");

        private final String envelopeNamespace;
        private final String contentType;

        SoapVersion(String envelopeNamespace, String contentType) {
            this.envelopeNamespace = envelopeNamespace;
            this.contentType = contentType;
        }

        public String envelopeNamespace() {
            return envelopeNamespace;
        }

        public String contentType() {
            return contentType;
        }
    }
}
