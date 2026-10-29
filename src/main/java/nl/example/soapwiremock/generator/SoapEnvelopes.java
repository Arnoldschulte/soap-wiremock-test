package nl.example.soapwiremock.generator;

import nl.example.soapwiremock.generator.Wsdl.SoapVersion;

/** SOAP 1.1- en 1.2-envelopes en -faults rond een payload. */
final class SoapEnvelopes {

    private SoapEnvelopes() {
    }

    static String envelope(SoapVersion version, String payload) {
        return """
                <soapenv:Envelope xmlns:soapenv="%s">
                  <soapenv:Header/>
                  <soapenv:Body>
                %s
                  </soapenv:Body>
                </soapenv:Envelope>
                """.formatted(version.envelopeNamespace(), indent(payload, 4));
    }

    static String fault(SoapVersion version, String faultName, String detailPayload) {
        if (version == SoapVersion.SOAP_11) {
            return """
                    <soapenv:Envelope xmlns:soapenv="%s">
                      <soapenv:Body>
                        <soapenv:Fault>
                          <faultcode>soapenv:Server</faultcode>
                          <faultstring>%s</faultstring>
                          <detail>
                    %s
                          </detail>
                        </soapenv:Fault>
                      </soapenv:Body>
                    </soapenv:Envelope>
                    """.formatted(version.envelopeNamespace(), escape(faultName), indent(detailPayload, 8));
        }
        return """
                <soapenv:Envelope xmlns:soapenv="%s">
                  <soapenv:Body>
                    <soapenv:Fault>
                      <soapenv:Code>
                        <soapenv:Value>soapenv:Receiver</soapenv:Value>
                      </soapenv:Code>
                      <soapenv:Reason>
                        <soapenv:Text xml:lang="en">%s</soapenv:Text>
                      </soapenv:Reason>
                      <soapenv:Detail>
                %s
                      </soapenv:Detail>
                    </soapenv:Fault>
                  </soapenv:Body>
                </soapenv:Envelope>
                """.formatted(version.envelopeNamespace(), escape(faultName), indent(detailPayload, 8));
    }

    private static String indent(String xml, int spaces) {
        String prefix = " ".repeat(spaces);
        return xml.lines().map(line -> prefix + line).reduce((a, b) -> a + "\n" + b).orElse("");
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
