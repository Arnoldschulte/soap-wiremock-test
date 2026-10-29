package nl.example.soapwiremock.soap;

/** Fout bij het opbouwen, valideren of lezen van een SOAP-bericht. */
public class SoapMessageException extends RuntimeException {

    public SoapMessageException(String message) {
        super(message);
    }

    public SoapMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
