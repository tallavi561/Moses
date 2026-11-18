package com.moses.monitor.process;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * What a service is: its name and how it is described. One document per service per
 * environment of a project - each environment has its own architecture, like a namespace in
 * OpenShift. How the service is doing there is its {@link ProcessState}.
 */
@Document(collection = "services")
public class ServiceDefinition {

    @Id
    private String id;

    @Field("project_id")
    private String projectId;

    @Field("environment")
    private String environment;

    /** Unique within the environment (see {@link ScopeIndexInitializer}). */
    @Field("name")
    private String name;

    @Field("process_name")
    private String processName;

    @Field("description")
    private String description;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getProcessName() { return processName; }
    public void setProcessName(String processName) { this.processName = processName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
