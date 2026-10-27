package com.moses.monitor.process;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * A single edge in the flow diagram: which two services it connects, which
 * way the flow goes, and what travels over it. Created automatically (with
 * default direction + no description) whenever a node lists another node in
 * its "connections" array, and editable afterwards on its own.
 */
@Document(collection = "connections")
public class Connection {

    @Id
    private String id;

    @Field("source_service")
    private String sourceService;

    @Field("target_service")
    private String targetService;

    @Field("direction")
    private ConnectionDirection direction;

    @Field("communication_type")
    private String communicationType;

    @Field("description")
    private String description;

    public Connection() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSourceService() {
        return sourceService;
    }

    public void setSourceService(String sourceService) {
        this.sourceService = sourceService;
    }

    public String getTargetService() {
        return targetService;
    }

    public void setTargetService(String targetService) {
        this.targetService = targetService;
    }

    public ConnectionDirection getDirection() {
        return direction;
    }

    public void setDirection(ConnectionDirection direction) {
        this.direction = direction;
    }

    public String getCommunicationType() {
        return communicationType;
    }

    public void setCommunicationType(String communicationType) {
        this.communicationType = communicationType;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
