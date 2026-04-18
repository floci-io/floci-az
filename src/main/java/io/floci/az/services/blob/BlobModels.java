package io.floci.az.services.blob;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

public class BlobModels {

    @RegisterForReflection
    @JacksonXmlRootElement(localName = "EnumerationResults")
    public record ContainerListResponse(
        @JacksonXmlProperty(localName = "ServiceEndpoint", isAttribute = true) String ServiceEndpoint,
        @JacksonXmlProperty(localName = "Prefix") String Prefix,
        @JacksonXmlProperty(localName = "Marker") String Marker,
        @JacksonXmlProperty(localName = "MaxResults") Integer MaxResults,
        @JacksonXmlElementWrapper(localName = "Containers")
        @JacksonXmlProperty(localName = "Container")
        List<ContainerItem> Containers,
        @JacksonXmlProperty(localName = "NextMarker") String NextMarker
    ) {}

    @RegisterForReflection
    public record ContainerItem(
        @JacksonXmlProperty(localName = "Name") String Name,
        @JacksonXmlProperty(localName = "Properties") ContainerProperties Properties
    ) {}

    @RegisterForReflection
    public record ContainerProperties(
        @JacksonXmlProperty(localName = "Last-Modified") String LastModified,
        @JacksonXmlProperty(localName = "Etag") String Etag
    ) {}

    @RegisterForReflection
    @JacksonXmlRootElement(localName = "EnumerationResults")
    public record BlobListResponse(
        @JacksonXmlProperty(localName = "ServiceEndpoint", isAttribute = true) String ServiceEndpoint,
        @JacksonXmlProperty(localName = "ContainerName", isAttribute = true) String ContainerName,
        @JacksonXmlProperty(localName = "Prefix") String Prefix,
        @JacksonXmlProperty(localName = "Marker") String Marker,
        @JacksonXmlProperty(localName = "MaxResults") Integer MaxResults,
        @JacksonXmlElementWrapper(localName = "Blobs")
        @JacksonXmlProperty(localName = "Blob")
        List<BlobItem> Blobs,
        @JacksonXmlProperty(localName = "NextMarker") String NextMarker
    ) {}

    @RegisterForReflection
    public record BlobItem(
        @JacksonXmlProperty(localName = "Name") String Name,
        @JacksonXmlProperty(localName = "Properties") BlobProperties Properties
    ) {}

    @RegisterForReflection
    public record BlobProperties(
        @JacksonXmlProperty(localName = "Last-Modified") String LastModified,
        @JacksonXmlProperty(localName = "Etag") String Etag,
        @JacksonXmlProperty(localName = "Content-Length") Long ContentLength,
        @JacksonXmlProperty(localName = "Content-Type") String ContentType,
        @JacksonXmlProperty(localName = "BlobType") String BlobType
    ) {}
}
