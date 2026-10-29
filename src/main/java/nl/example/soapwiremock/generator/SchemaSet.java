package nl.example.soapwiremock.generator;

import org.apache.xmlbeans.SchemaType;
import org.apache.xmlbeans.SchemaTypeLoader;
import org.apache.xmlbeans.SchemaTypeSystem;
import org.apache.xmlbeans.XmlBeans;
import org.apache.xmlbeans.XmlError;
import org.apache.xmlbeans.XmlException;
import org.apache.xmlbeans.XmlObject;
import org.apache.xmlbeans.XmlOptions;
import org.apache.xmlbeans.impl.xsd2inst.SampleXmlUtil;
import org.w3c.dom.Attr;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Alle XSD's achter een WSDL: de inline schema's uit {@code <wsdl:types>} plus alles wat
 * die (transitief) importeren of includen. Wordt met XMLBeans gecompileerd om
 * voorbeeldberichten te maken en te valideren.
 * <p>
 * Alleen lokale bestanden worden gelezen; er gaat niets over het netwerk.
 */
public final class SchemaSet {

    private static final String XSD_NS = XMLConstants.W3C_XML_SCHEMA_NS_URI;

    private final SchemaTypeLoader loader;
    private final int schemaCount;

    private SchemaSet(SchemaTypeLoader loader, int schemaCount) {
        this.loader = loader;
        this.schemaCount = schemaCount;
    }

    public static SchemaSet fromWsdl(Path wsdlFile, List<String> warnings) {
        Path wsdl = wsdlFile.toAbsolutePath().normalize();
        Element definitions = Xml.parse(wsdl).getDocumentElement();

        Map<String, String> schemaTexts = new LinkedHashMap<>(); // bron-URI -> schema-XML
        Deque<Path> todo = new ArrayDeque<>();

        Element types = Xml.child(definitions, WsdlReader.WSDL_NS, "types");
        if (types != null) {
            int index = 0;
            for (Element schema : Xml.children(types, XSD_NS, "schema")) {
                Element copy = (Element) schema.cloneNode(true);
                copyInScopeNamespaces(schema, copy);
                resolveSchemaLocations(copy, wsdl.getParent(), todo, warnings);
                schemaTexts.put(uri(wsdl) + "#types-schema-" + index++, Xml.toString(copy));
            }
        }

        while (!todo.isEmpty()) {
            Path xsd = todo.poll();
            String uri = uri(xsd);
            if (schemaTexts.containsKey(uri)) {
                continue;
            }
            if (!Files.isRegularFile(xsd)) {
                throw new GeneratorException("Geïmporteerde XSD niet gevonden: " + xsd);
            }
            Element schema = Xml.parse(xsd).getDocumentElement();
            resolveSchemaLocations(schema, xsd.getParent(), todo, warnings);
            schemaTexts.put(uri, Xml.toString(schema));
        }

        if (schemaTexts.isEmpty()) {
            throw new GeneratorException("Geen schema's gevonden in <wsdl:types> van " + wsdlFile);
        }
        return compile(schemaTexts);
    }

    /** Maakt een voorbeeldbericht voor een globaal element, zoals {@code xsd2inst} dat doet. */
    public String sample(QName element) {
        SchemaType documentType = loader.findDocumentType(element);
        if (documentType == null) {
            throw new GeneratorException("Element " + element + " niet gevonden in de XSD's");
        }
        String xml = SampleXmlUtil.createSampleForType(documentType);
        return xml.replaceFirst("^\\s*<\\?xml[^>]*\\?>", "").strip();
    }

    /** Valideert een bericht (zonder SOAP-envelope) en geeft de gevonden fouten terug. */
    public List<String> validate(String xml) {
        List<XmlError> errors = new ArrayList<>();
        try {
            XmlObject object = loader.parse(xml, null, new XmlOptions().setLoadLineNumbers());
            object.validate(new XmlOptions().setErrorListener(errors));
        } catch (XmlException e) {
            return List.of(e.getMessage());
        }
        return errors.stream().map(XmlError::getMessage).toList();
    }

    public int schemaCount() {
        return schemaCount;
    }

    // ------------------------------------------------------------------ intern

    private static SchemaSet compile(Map<String, String> schemaTexts) {
        List<XmlObject> schemas = new ArrayList<>();
        List<XmlError> errors = new ArrayList<>();
        try {
            for (Map.Entry<String, String> entry : schemaTexts.entrySet()) {
                schemas.add(XmlObject.Factory.parse(entry.getValue(), new XmlOptions()
                        .setLoadLineNumbers()
                        .setLoadMessageDigest()
                        .setDocumentSourceName(entry.getKey())));
            }
            XmlOptions options = new XmlOptions()
                    .setCompileNoUpaRule()
                    .setCompileNoPvrRule()
                    .setErrorListener(errors);
            SchemaTypeSystem system = XmlBeans.compileXsd(
                    schemas.toArray(new XmlObject[0]), XmlBeans.getBuiltinTypeSystem(), options);
            SchemaTypeLoader loader = XmlBeans.typeLoaderUnion(system, XmlBeans.getBuiltinTypeSystem());
            return new SchemaSet(loader, schemas.size());
        } catch (XmlException e) {
            String details = errors.isEmpty() ? e.getMessage()
                    : errors.stream().map(XmlError::toString).collect(Collectors.joining("\n  "));
            throw new GeneratorException("XSD's kunnen niet worden gecompileerd:\n  " + details, e);
        }
    }

    /**
     * Maakt relatieve schemaLocations absoluut (zodat ze los van de WSDL kloppen)
     * en zet de bestanden op de werklijst.
     */
    private static void resolveSchemaLocations(Element schema, Path baseDir, Deque<Path> todo, List<String> warnings) {
        for (String kind : List.of("import", "include", "redefine")) {
            for (Element ref : Xml.children(schema, XSD_NS, kind)) {
                String location = ref.getAttribute("schemaLocation").trim();
                if (location.isEmpty()) {
                    continue; // import zonder locatie: namespace moet elders in de set zitten
                }
                if (location.startsWith("http://") || location.startsWith("https://")) {
                    warnings.add("Externe schemaLocation wordt niet gedownload: " + location
                            + ". Sla de XSD lokaal op en verwijs er relatief naar.");
                    continue;
                }
                Path file = location.startsWith("file:")
                        ? Path.of(java.net.URI.create(location))
                        : baseDir.resolve(location);
                file = file.toAbsolutePath().normalize();
                ref.setAttribute("schemaLocation", uri(file));
                todo.add(file);
            }
        }
    }

    /** Neemt namespace-declaraties over van de voorouders (bijv. {@code <wsdl:definitions>}). */
    private static void copyInScopeNamespaces(Element original, Element copy) {
        for (Node n = original.getParentNode(); n instanceof Element ancestor; n = ancestor.getParentNode()) {
            NamedNodeMap attributes = ancestor.getAttributes();
            for (int i = 0; i < attributes.getLength(); i++) {
                Attr attr = (Attr) attributes.item(i);
                if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attr.getNamespaceURI())
                        && !copy.hasAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, attr.getLocalName())) {
                    copy.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, attr.getName(), attr.getValue());
                }
            }
        }
    }

    private static String uri(Path path) {
        return path.toAbsolutePath().normalize().toUri().toString();
    }
}
