package io.floci.az.services.queue;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

public class QueueModels {

    @RegisterForReflection
    @JacksonXmlRootElement(localName = "EnumerationResults")
    public record QueueListResponse(
        @JacksonXmlProperty(localName = "ServiceEndpoint", isAttribute = true) String ServiceEndpoint,
        @JacksonXmlProperty(localName = "Prefix") String Prefix,
        @JacksonXmlProperty(localName = "Marker") String Marker,
        @JacksonXmlProperty(localName = "MaxResults") Integer MaxResults,
        @JacksonXmlElementWrapper(localName = "Queues")
        @JacksonXmlProperty(localName = "Queue")
        List<QueueItem> Queues,
        @JacksonXmlProperty(localName = "NextMarker") String NextMarker
    ) {}

    @RegisterForReflection
    public record QueueItem(
        @JacksonXmlProperty(localName = "Name") String Name,
        @JacksonXmlElementWrapper(localName = "Metadata")
        @JacksonXmlProperty(localName = "Metadata")
        java.util.Map<String, String> Metadata
    ) {}

    @RegisterForReflection
    @JacksonXmlRootElement(localName = "QueueMessagesList")
    public record QueueMessageResponse(
        @JacksonXmlElementWrapper(useWrapping = false)
        @JacksonXmlProperty(localName = "QueueMessage")
        List<QueueMessageItem> Messages
    ) {}

    @RegisterForReflection
    public record QueueMessageItem(
        @JacksonXmlProperty(localName = "MessageId") String MessageId,
        @JacksonXmlProperty(localName = "InsertionTime") String InsertionTime,
        @JacksonXmlProperty(localName = "ExpirationTime") String ExpirationTime,
        @JacksonXmlProperty(localName = "PopReceipt") String PopReceipt,
        @JacksonXmlProperty(localName = "TimeNextVisible") String TimeNextVisible,
        @JacksonXmlProperty(localName = "DequeueCount") Integer DequeueCount,
        @JacksonXmlProperty(localName = "MessageText") String MessageText
    ) {}

    @RegisterForReflection
    @JacksonXmlRootElement(localName = "QueueMessage")
    public record QueueMessageRequest(
        @JacksonXmlProperty(localName = "MessageText") String MessageText
    ) {}
}
