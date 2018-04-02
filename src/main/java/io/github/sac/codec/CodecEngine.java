package io.github.sac.codec;

import com.fasterxml.jackson.databind.JsonNode;

public interface CodecEngine {
    byte[] encode(JsonNode data);

    JsonNode decode(byte[] data);
}
