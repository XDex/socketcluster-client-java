package io.github.sac.codec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.msgpack.jackson.dataformat.MessagePackFactory;

import java.util.logging.Logger;

public class MinBinCodec implements SocketClusterCodec {
    private final static Logger LOGGER = Logger.getLogger(SocketClusterCodec.class.getName());
    private final static ObjectMapper mapper = new ObjectMapper(new MessagePackFactory());

    @Override
    public byte[] encode(JsonNode data) {
        try {
            if (data.isValueNode()) {
                return mapper.writeValueAsBytes(data);
            }

            if (data.isObject()) {
                ObjectNode dataObject = (ObjectNode) data;
                ObjectNode compressed = mapper.createObjectNode();

                compressPublish(dataObject, compressed);
                compressEmit(dataObject, compressed);
                compressResponse(dataObject, compressed);

                return mapper.writeValueAsBytes(compressed);
            }
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
        LOGGER.info("Unable to encode data");
        return null;
    }

    private void compressResponse(ObjectNode data, ObjectNode compressed) {
        if (!data.has("rid") || data.get("rid").isNull()) {
            return;
        }

        ArrayNode array = mapper.createArrayNode()
                .add(data.get("rid"))
                .add(data.get("error"))
                .add(data.get("data"));

        compressed.set("r", array);

        data.remove("rid");
        data.remove("error");
        data.remove("data");
    }

    private void compressPublish(ObjectNode data, ObjectNode compressed) {
        if (!data.has("event") || !data.get("event").asText().equals("#publish")
                || !data.has("data") || data.get("data").isNull()) {
            return;
        }

        ObjectNode dataObject = (ObjectNode) data.get("data");

        ArrayNode array = mapper.createArrayNode();
        array.add(dataObject.get("channel"));
        array.add(dataObject.get("data"));

        if (data.has("cid")) {
            array.add(data.get("cid"));
            data.remove("cid");
        }

        compressed.set("p", array);

        data.remove("event");
        data.remove("data");
    }

    private void compressEmit(ObjectNode data, ObjectNode compressed) {
        if (!data.has("event") || data.get("event").isNull()) {
            return;
        }

        ArrayNode array = mapper.createArrayNode();
        array.add(data.get("event"));
        array.add(data.get("data"));

        if (data.has("cid")) {
            array.add(data.get("cid"));
            data.remove("cid");
        }

        compressed.set("e", array);

        data.remove("event");
        data.remove("data");
    }


    @Override
    public JsonNode decode(byte[] data) {
        return null;
    }
}
