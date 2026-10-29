package nl.example.soapwiremock.generator;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Kleine DOM-hulpmethoden. Leest alleen lokale bestanden; DTD's en externe entities staan uit. */
final class Xml {

    private Xml() {
    }

    static Document parse(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            InputSource source = new InputSource(in);
            source.setSystemId(file.toUri().toString());
            return factory.newDocumentBuilder().parse(source);
        } catch (IOException e) {
            throw new GeneratorException("Kan bestand niet lezen: " + file, e);
        } catch (ParserConfigurationException | SAXException e) {
            throw new GeneratorException("Geen geldige XML: " + file + " (" + e.getMessage() + ")", e);
        }
    }

    static String toString(Node node) {
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            Transformer transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(node), new StreamResult(writer));
            return writer.toString();
        } catch (TransformerException e) {
            throw new GeneratorException("Kan XML niet serialiseren", e);
        }
    }

    static List<Element> children(Element parent, String namespace, String localName) {
        List<Element> result = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && localName.equals(e.getLocalName()) && namespace.equals(e.getNamespaceURI())) {
                result.add(e);
            }
        }
        return result;
    }

    static Element child(Element parent, String namespace, String localName) {
        List<Element> list = children(parent, namespace, localName);
        return list.isEmpty() ? null : list.get(0);
    }

    /** Leest een QName-attribuut (bijv. {@code element="tns:Foo"}) en lost de prefix op. */
    static QName qname(Element element, String attribute) {
        String value = element.getAttribute(attribute).trim();
        if (value.isEmpty()) {
            return null;
        }
        int colon = value.indexOf(':');
        String prefix = colon < 0 ? null : value.substring(0, colon);
        String localPart = colon < 0 ? value : value.substring(colon + 1);
        String namespace = element.lookupNamespaceURI(prefix);
        if (namespace == null && prefix != null) {
            throw new GeneratorException("Onbekende namespace-prefix '" + prefix + "' in " + attribute + "=\"" + value + "\"");
        }
        return new QName(namespace == null ? "" : namespace, localPart);
    }
}
