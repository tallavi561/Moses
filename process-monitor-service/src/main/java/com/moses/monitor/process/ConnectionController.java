package com.moses.monitor.process;

import jakarta.validation.Valid;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * CRUD for edges between nodes. A connection is normally created implicitly
 * via a node's "connections" list (see ProcessStateController), but can also
 * be created and edited directly here - this is how direction/communication
 * type/description get set after the fact.
 */
@RestController
@RequestMapping("/api/process/connections")
public class ConnectionController {

    private final MongoTemplate mongoTemplate;

    public ConnectionController(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @GetMapping
    public List<Connection> listConnections() {
        return mongoTemplate.findAll(Connection.class);
    }

    @PostMapping
    public ResponseEntity<Connection> createConnection(@Valid @RequestBody ConnectionRequest request) {
        Connection connection = new Connection();
        connection.setId(UUID.randomUUID().toString());
        apply(connection, request);
        mongoTemplate.save(connection);
        return ResponseEntity.ok(connection);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Connection> updateConnection(@PathVariable String id,
                                                         @Valid @RequestBody ConnectionRequest request) {
        Connection existing = mongoTemplate.findById(id, Connection.class);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        apply(existing, request);
        mongoTemplate.save(existing);
        return ResponseEntity.ok(existing);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteConnection(@PathVariable String id) {
        Connection existing = mongoTemplate.findById(id, Connection.class);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        mongoTemplate.remove(existing);
        return ResponseEntity.noContent().build();
    }

    private void apply(Connection connection, ConnectionRequest request) {
        connection.setSourceService(request.getSourceService());
        connection.setTargetService(request.getTargetService());
        connection.setDirection(request.getDirection());
        connection.setCommunicationType(request.getCommunicationType());
        connection.setDescription(request.getDescription());
    }
}
