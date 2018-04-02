package io.github.sac.codec;

import com.fasterxml.jackson.databind.JsonNode;

public class MinBinCodec implements CodecEngine {

    @Override
    public byte[] encode(JsonNode data) {
        return new byte[0];
    }

    @Override
    public JsonNode decode(byte[] data) {
        return null;
    }
}
