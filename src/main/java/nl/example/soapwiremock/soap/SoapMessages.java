package nl.example.soapwiremock.soap;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.dom.DOMSource;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URL;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Zet JAXB-objecten om naar SOAP 1.1-berichten en terug.
 * <p>
 * Alle payloads worden bij het (un)marshallen gevalideerd tegen de opgegeven XSD's.
 * Zo weet je zeker dat de mock-responses die WireMock teruggeeft schema-conform zijn.
 */
public final class SoapMessages {

    public static final String SOAP_11_NS = "http://schemas.xmlsoap.org/soap/envelope/";
    public static final String CONTENT_TYPE = "text/xml; charset=UTF-8";

    private static final XMLInputFactory INPUT_FACTORY = createSecureInputFactory();

    private final Schema schema;
    private final Map<Class<?>, JAXBContext> contexts = new ConcurrentHashMap<>();

    private SoapMessages(Schema schema) {
        this.schema = schema;
    }

    /**
     * Maakt een instantie die valideert tegen de opgegeven XSD's op het classpath.
     * Geef alleen de "top-level" XSD's op; geïmporteerde XSD's (zoals common.xsd)
     * worden via hun schemaLocation automatisch meegeladen.
     *
     * @param classpathXsds bijv. {@code "xsd/customer.xsd"}
     */
    public static SoapMessages validatingAgainst(String... classpathXsds) {
        try {
            SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "file,jar");
            Source[] sources = Arrays.stream(classpathXsds)
                    .map(SoapMessages::classpathResource)
                    .map(url -> new StreamSource(url.toExternalForm()))
                    .toArray(Source[]::new);
            return new SoapMessages(factory.newSchema(sources));
        } catch (SAXException e) {
            throw new IllegalStateException("Kan XSD's niet laden: " + Arrays.toString(classpathXsds), e);
        }
    }

    /** Maakt een instantie zonder XSD-validatie (handig om bewust ongeldige berichten te testen). */
    public static SoapMessages withoutValidation() {
        return new SoapMessages(null);
    }

    /** Verpakt een JAXB-payload (bijv. {@code GetCustomerResponse}) in een SOAP-envelope. */
    public String envelope(Object payload) {
        return """
                <soapenv:Envelope xmlns:soapenv="%s">
                  <soapenv:Header/>
                  <soapenv:Body>
                %s
                  </soapenv:Body>
                </soapenv:Envelope>
                """.formatted(SOAP_11_NS, marshal(payload));
    }

    /**
     * Bouwt een SOAP 1.1 Fault.
     *
     * @param faultCode   bijv. {@code "soapenv:Client"} of {@code "soapenv:Server"}
     * @param faultString leesbare foutmelding
     * @param detail      optionele JAXB-payload voor het {@code <detail>}-blok (mag null zijn)
     */
    public String fault(String faultCode, String faultString, Object detail) {
        String detailXml = detail == null ? "" : "<detail>\n" + marshal(detail) + "\n</detail>";
        return """
                <soapenv:Envelope xmlns:soapenv="%s">
                  <soapenv:Body>
                    <soapenv:Fault>
                      <faultcode>%s</faultcode>
                      <faultstring>%s</faultstring>
                      %s
                    </soapenv:Fault>
                  </soapenv:Body>
                </soapenv:Envelope>
                """.formatted(SOAP_11_NS, escape(faultCode), escape(faultString), detailXml);
    }

    /** Leest de payload uit de {@code <soapenv:Body>} van een SOAP-bericht. */
    public <T> T bodyPayload(String soapXml, Class<T> type) {
        return firstChildOf(soapXml, "Body", type);
    }

    /** Leest de payload uit het {@code <detail>}-blok van een SOAP Fault. */
    public <T> T faultDetail(String soapXml, Class<T> type) {
        return firstChildOf(soapXml, "detail", type);
    }

    /**
     * Valideert een kant-en-klaar SOAP-bericht (bijv. een bestand uit {@code __files}) tegen de XSD's.
     * Gevalideerd wordt de payload in de {@code Body}, of bij een Fault de payload in {@code detail}.
     * De envelope zelf valt buiten de XSD's en wordt alleen op welgevormdheid gecontroleerd.
     *
     * @throws SoapMessageException als het bericht niet welgevormd of niet schema-conform is
     */
    public void validate(String soapXml) {
        if (schema == null) {
            throw new IllegalStateException("Geen XSD's geconfigureerd; gebruik validatingAgainst(...)");
        }
        Element payload = payloadElement(parse(soapXml));
        if (payload == null) {
            return; // Fault zonder detail: niets om te valideren
        }
        try {
            schema.newValidator().validate(new DOMSource(payload));
        } catch (SAXException | IOException e) {
            throw new SoapMessageException(
                    "<" + payload.getLocalName() + "> is niet geldig volgens de XSD: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ intern

    private static Document parse(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new SoapMessageException("Bericht is geen welgevormde XML: " + e.getMessage(), e);
        }
    }

    private static Element payloadElement(Document document) {
        Element body = firstElementByLocalName(document.getDocumentElement(), "Body");
        if (body == null) {
            throw new SoapMessageException("Geen <Body> gevonden in bericht");
        }
        Element payload = firstChildElement(body);
        if (payload == null) {
            throw new SoapMessageException("<Body> is leeg");
        }
        if ("Fault".equals(payload.getLocalName())) {
            // SOAP 1.1: <detail>, SOAP 1.2: <Detail>
            Element detail = firstElementByLocalName(payload, "detail");
            if (detail == null) {
                detail = firstElementByLocalName(payload, "Detail");
            }
            return detail == null ? null : firstChildElement(detail);
        }
        return payload;
    }

    private static Element firstElementByLocalName(Element parent, String localName) {
        NodeList nodes = parent.getElementsByTagNameNS("*", localName);
        return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
    }

    private static Element firstChildElement(Element parent) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                return (Element) child;
            }
        }
        return null;
    }

    private String marshal(Object payload) {
        try {
            // Een JAXBElement (uit ObjectFactory) bevat het eigenlijke type als declaredType
            Class<?> type = payload instanceof JAXBElement<?> element ? element.getDeclaredType() : payload.getClass();
            Marshaller marshaller = context(type).createMarshaller();
            marshaller.setProperty(Marshaller.JAXB_FRAGMENT, true);
            marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
            marshaller.setSchema(schema);
            StringWriter writer = new StringWriter();
            marshaller.marshal(payload, writer);
            return writer.toString();
        } catch (JAXBException e) {
            throw new SoapMessageException(
                    "Payload " + payload.getClass().getSimpleName() + " is niet geldig volgens de XSD", e);
        }
    }

    private <T> T firstChildOf(String soapXml, String parentLocalName, Class<T> type) {
        XMLStreamReader reader = null;
        try {
            reader = INPUT_FACTORY.createXMLStreamReader(new StringReader(soapXml));
            while (reader.hasNext()) {
                if (reader.next() == XMLStreamConstants.START_ELEMENT
                        && parentLocalName.equals(reader.getLocalName())) {
                    if (reader.nextTag() != XMLStreamConstants.START_ELEMENT) {
                        throw new SoapMessageException("<" + parentLocalName + "> is leeg");
                    }
                    Unmarshaller unmarshaller = context(type).createUnmarshaller();
                    unmarshaller.setSchema(schema);
                    return unmarshaller.unmarshal(reader, type).getValue();
                }
            }
            throw new SoapMessageException("Geen <" + parentLocalName + "> gevonden in bericht");
        } catch (XMLStreamException | JAXBException e) {
            throw new SoapMessageException("Kan " + type.getSimpleName() + " niet lezen uit SOAP-bericht", e);
        } finally {
            closeQuietly(reader);
        }
    }

    private JAXBContext context(Class<?> type) {
        return contexts.computeIfAbsent(type, t -> {
            try {
                return JAXBContext.newInstance(t);
            } catch (JAXBException e) {
                throw new SoapMessageException("Kan JAXBContext niet maken voor " + t.getName(), e);
            }
        });
    }

    private static URL classpathResource(String path) {
        URL url = SoapMessages.class.getClassLoader().getResource(path);
        if (url == null) {
            throw new IllegalArgumentException("XSD niet gevonden op classpath: " + path);
        }
        return url;
    }

    private static XMLInputFactory createSecureInputFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return factory;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void closeQuietly(XMLStreamReader reader) {
        if (reader != null) {
            try {
                reader.close();
            } catch (XMLStreamException ignored) {
                // niets aan te doen
            }
        }
    }
}
