package com.moses.monitor.ai.llm;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

/**
 * The {@link LlmClient} the rest of ai-service is given: on every call it uses the model of the
 * project and environment the current request is about (the scope headers api-gateway or
 * chat-service set), so each project pays with its own key.
 */
@Primary
@Component
public class ScopedLlmClient implements LlmClient {

    private final ProjectLlmResolver resolver;

    public ScopedLlmClient(ProjectLlmResolver resolver) {
        this.resolver = resolver;
    }

    private LlmClient current() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return new DisabledLlmClient();
        }
        var request = attributes.getRequest();
        return resolver.forScope(request.getHeader("X-Moses-Project-Id"), request.getHeader("X-Moses-Environment"));
    }

    @Override
    public String complete(String system, String user) {
        return current().complete(system, user);
    }

    @Override
    public AgentResult runAgent(String system, String userMessage, List<ToolSpec> tools, ToolExecutor executor,
                                int maxSteps) {
        return current().runAgent(system, userMessage, tools, executor, maxSteps);
    }

    @Override
    public void verify() {
        current().verify();
    }

    @Override
    public boolean isEnabled() {
        return current().isEnabled();
    }

    @Override
    public String describe() {
        return current().describe();
    }
}
