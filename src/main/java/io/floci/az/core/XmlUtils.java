package io.floci.az.core;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.ser.ToXmlGenerator;

import java.io.IOException;

public class XmlUtils {
    private static final XmlMapper mapper = new XmlMapper();
    
    static {
        mapper.configure(ToXmlGenerator.Feature.WRITE_XML_DECLARATION, true);
    }

    public static String toXml(Object obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize to XML", e);
        }
    }
}
