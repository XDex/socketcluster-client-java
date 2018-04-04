package io.github.sac; /**
 * Created by sachin on 16/11/16.
 */

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Interface for handling errors
 */

public interface Ack {
    void call(String name, JsonNode error, JsonNode data);
}
