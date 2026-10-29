package nl.example.soapwiremock.generator;

import nl.example.soapwiremock.generator.Wsdl.Endpoint;
import nl.example.soapwiremock.generator.Wsdl.Fault;
import nl.example.soapwiremock.generator.Wsdl.Operation;
import nl.example.soapwiremock.generator.Wsdl.SoapVersion;
import org.w3c.dom.Element;

import javax.xml.namespace.QName;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static nl.example.soapwiremock.generator.Xml.child;
import static nl.example.soapwiremock.generator.Xml.children;
import static nl.example.soapwiremock.generator.Xml.qname;

/**
 * Leest een WSDL 1.1 (document/literal) in: services, ports, endpoints,
 * operaties, soapActions en de request-, response- en fault-elementen.
 */
public final class WsdlReader {

    static final String WSDL_NS = "http://schemas.xmlsoap.org/wsdl/";
    static final String SOAP11_BINDING_NS = "http://schemas.xmlsoap.org/wsdl/soap/";
    static final String SOAP12_BINDING_NS = "http://schemas.xmlsoap.org/wsdl/soap12/";

    private record PortTypeOperation(String name, QName inputMessage, QName outputMessage,
                                     Map<String, QName> faultMessages) {
    }

    private record Binding(String name, QName portType, SoapVersion soapVersion, List<Element> operations) {
    }

    private final List<String> warnings = new ArrayList<>();
    private final Map<QName, QName> messageElements = new HashMap<>();
    private final Map<QName, Map<String, PortTypeOperation>> portTypes = new HashMap<>();
    private final Map<QName, Binding> bindings = new LinkedHashMap<>();

    private WsdlReader() {
    }

    public static Wsdl read(Path wsdlFile) {
        return new WsdlReader().parse(wsdlFile);
    }

    private Wsdl parse(Path wsdlFile) {
        Element definitions = Xml.parse(wsdlFile).getDocumentElement();
        if (!WSDL_NS.equals(definitions.getNamespaceURI()) || !"definitions".equals(definitions.getLocalName())) {
            throw new GeneratorException("Geen WSDL 1.1-bestand (verwacht <wsdl:definitions>): " + wsdlFile);
        }
        String tns = definitions.getAttribute("targetNamespace");

        if (!children(definitions, WSDL_NS, "import").isEmpty()) {
            warnings.add("<wsdl:import> wordt niet gevolgd. Voeg geïmporteerde WSDL's samen tot één bestand.");
        }

        readMessages(definitions, tns);
        readPortTypes(definitions, tns);
        readBindings(definitions, tns);
        List<Endpoint> endpoints = readServices(definitions);

        if (endpoints.isEmpty()) {
            throw new GeneratorException("Geen SOAP-bindings gevonden in " + wsdlFile);
        }
        return new Wsdl(endpoints, List.copyOf(warnings));
    }

    private void readMessages(Element definitions, String tns) {
        for (Element message : children(definitions, WSDL_NS, "message")) {
            QName name = new QName(tns, message.getAttribute("name"));
            for (Element part : children(message, WSDL_NS, "part")) {
                QName element = qname(part, "element");
                if (element != null) {
                    messageElements.putIfAbsent(name, element);
                }
            }
        }
    }

    private void readPortTypes(Element definitions, String tns) {
        for (Element portType : children(definitions, WSDL_NS, "portType")) {
            Map<String, PortTypeOperation> operations = new LinkedHashMap<>();
            for (Element op : children(portType, WSDL_NS, "operation")) {
                Element input = child(op, WSDL_NS, "input");
                Element output = child(op, WSDL_NS, "output");
                Map<String, QName> faults = new LinkedHashMap<>();
                for (Element fault : children(op, WSDL_NS, "fault")) {
                    faults.put(fault.getAttribute("name"), qname(fault, "message"));
                }
                String name = op.getAttribute("name");
                operations.put(name, new PortTypeOperation(name,
                        input == null ? null : qname(input, "message"),
                        output == null ? null : qname(output, "message"),
                        faults));
            }
            portTypes.put(new QName(tns, portType.getAttribute("name")), operations);
        }
    }

    private void readBindings(Element definitions, String tns) {
        for (Element binding : children(definitions, WSDL_NS, "binding")) {
            String name = binding.getAttribute("name");
            SoapVersion version;
            Element soapBinding;
            if ((soapBinding = child(binding, SOAP11_BINDING_NS, "binding")) != null) {
                version = SoapVersion.SOAP_11;
            } else if ((soapBinding = child(binding, SOAP12_BINDING_NS, "binding")) != null) {
                version = SoapVersion.SOAP_12;
            } else {
                warnings.add("Binding '" + name + "' is geen SOAP-binding en wordt overgeslagen.");
                continue;
            }
            if ("rpc".equals(soapBinding.getAttribute("style"))) {
                warnings.add("Binding '" + name + "' gebruikt style=\"rpc\"; alleen document/literal wordt ondersteund.");
            }
            bindings.put(new QName(tns, name),
                    new Binding(name, qname(binding, "type"), version, children(binding, WSDL_NS, "operation")));
        }
    }

    private List<Endpoint> readServices(Element definitions) {
        List<Endpoint> endpoints = new ArrayList<>();
        List<Element> services = children(definitions, WSDL_NS, "service");

        for (Element service : services) {
            for (Element port : children(service, WSDL_NS, "port")) {
                Binding binding = bindings.get(qname(port, "binding"));
                if (binding == null) {
                    continue; // geen SOAP-binding (al gemeld) of onbekend
                }
                Element address = child(port, binding.soapVersion() == SoapVersion.SOAP_11
                        ? SOAP11_BINDING_NS : SOAP12_BINDING_NS, "address");
                String path = address == null ? null : pathOf(address.getAttribute("location"), port.getAttribute("name"));
                endpoints.add(endpoint(service.getAttribute("name"), port.getAttribute("name"), path, binding));
            }
        }

        if (services.isEmpty()) {
            warnings.add("Geen <wsdl:service> gevonden: endpoint-URL onbekend, mappings matchen op elke URL.");
            for (Binding binding : bindings.values()) {
                endpoints.add(endpoint(binding.name(), binding.name(), null, binding));
            }
        }
        return endpoints;
    }

    private Endpoint endpoint(String serviceName, String portName, String path, Binding binding) {
        Map<String, PortTypeOperation> portType = portTypes.get(binding.portType());
        if (portType == null) {
            throw new GeneratorException("PortType " + binding.portType() + " van binding '" + binding.name() + "' niet gevonden");
        }
        String soapNs = binding.soapVersion() == SoapVersion.SOAP_11 ? SOAP11_BINDING_NS : SOAP12_BINDING_NS;

        List<Operation> operations = new ArrayList<>();
        for (Element bindingOp : binding.operations()) {
            String name = bindingOp.getAttribute("name");
            PortTypeOperation op = portType.get(name);
            if (op == null) {
                warnings.add("Operatie '" + name + "' staat in binding '" + binding.name() + "' maar niet in het portType.");
                continue;
            }
            Element soapOperation = child(bindingOp, soapNs, "operation");
            String soapAction = soapOperation == null ? "" : soapOperation.getAttribute("soapAction");

            QName input = messageElements.get(op.inputMessage());
            if (input == null) {
                warnings.add("Operatie '" + name + "' heeft geen request-element (RPC-stijl of type-parts?); overgeslagen.");
                continue;
            }
            QName output = op.outputMessage() == null ? null : messageElements.get(op.outputMessage());
            if (op.outputMessage() != null && output == null) {
                warnings.add("Operatie '" + name + "' heeft geen response-element (RPC-stijl of type-parts?); overgeslagen.");
                continue;
            }

            List<Fault> faults = new ArrayList<>();
            op.faultMessages().forEach((faultName, message) -> {
                QName element = messageElements.get(message);
                if (element == null) {
                    warnings.add("Fault '" + faultName + "' van operatie '" + name + "' heeft geen element; overgeslagen.");
                } else {
                    faults.add(new Fault(faultName, element));
                }
            });

            operations.add(new Operation(name, soapAction, input, output, faults));
        }
        return new Endpoint(serviceName, portName, path, binding.soapVersion(), operations);
    }

    private String pathOf(String location, String portName) {
        try {
            String path = URI.create(location.trim()).getPath();
            if (path == null || path.isEmpty() || "/".equals(path)) {
                warnings.add("Port '" + portName + "' heeft geen pad in location=\"" + location + "\"; mappings matchen op elke URL.");
                return null;
            }
            return path;
        } catch (IllegalArgumentException e) {
            warnings.add("Port '" + portName + "' heeft een ongeldige location=\"" + location + "\"; mappings matchen op elke URL.");
            return null;
        }
    }
}
