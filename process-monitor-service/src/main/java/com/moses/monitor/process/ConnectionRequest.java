package com.moses.monitor.process;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class ConnectionRequest {

    @NotBlank
    private String sourceService;

    @NotBlank
    private String targetService;

    @NotNull
    private ConnectionDirection direction;

    private String communicationType;
    private String description;

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
