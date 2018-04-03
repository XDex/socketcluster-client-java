package io.github.sac.codec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.msgpack.jackson.dataformat.MessagePackFactory;

import java.io.IOException;
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
                ObjectNode encodeObject = (ObjectNode) data;
                ObjectNode compressed = mapper.createObjectNode();

                compressPublish(encodeObject, compressed);
                compressEmit(encodeObject, compressed);
                compressResponse(encodeObject, compressed);

                return mapper.writeValueAsBytes(compressed);
            }
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
        LOGGER.info("Unable to encode data");
        return null;
    }

    private void compressResponse(ObjectNode object, ObjectNode compressed) {
        if (!object.has("rid") || object.get("rid").isNull()) {
            return;
        }

        ArrayNode array = mapper.createArrayNode()
                .add(object.get("rid"))
                .add(object.get("error"))
                .add(object.get("data"));

        compressed.set("r", array);

        object.remove("rid");
        object.remove("error");
        object.remove("data");
    }

    private void compressPublish(ObjectNode object, ObjectNode compressed) {
        if (!object.has("event") || !object.get("event").asText().equals("#publish")
                || !object.has("data") || object.get("data").isNull()) {
            return;
        }

        ObjectNode dataObject = (ObjectNode) object.get("data");

        ArrayNode array = mapper.createArrayNode();
        array.add(dataObject.get("channel"));
        array.add(dataObject.get("data"));

        if (object.has("cid")) {
            array.add(object.get("cid"));
            object.remove("cid");
        }

        compressed.set("p", array);

        object.remove("event");
        object.remove("data");
    }

    private void compressEmit(ObjectNode object, ObjectNode compressed) {
        if (!object.has("event") || object.get("event").isNull()) {
            return;
        }

        ArrayNode array = mapper.createArrayNode();
        array.add(object.get("event"));
        array.add(object.get("data"));

        if (object.has("cid")) {
            array.add(object.get("cid"));
            object.remove("cid");
        }

        compressed.set("e", array);

        object.remove("event");
        object.remove("data");
    }


    @Override
    public JsonNode decode(byte[] data) {
        try {
            JsonNode decoded = mapper.readTree(data);

            if (decoded.isValueNode()) {
                return decoded;
            }

            if (decoded.isObject()) {
                ObjectNode decodeObject = (ObjectNode) decoded;
                decompressEmit(decodeObject);
                decompressPublish(decodeObject);
                decompressResponse(decodeObject);

                return decodeObject;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        LOGGER.info("Unable to decode data");
        return null;
    }

    private void decompressResponse(ObjectNode object) {
        if (!object.has("r") || object.get("r").isNull()) {
            return;
        }

        ArrayNode array = (ArrayNode) object.get("r");

        object.set("rid", array.get(0));
        object.set("error", array.get(1));
        object.set("data", array.get(2));

        object.remove("r");
    }

    private void decompressPublish(ObjectNode object) {
        if (!object.has("p") || object.get("p").isNull()) {
            return;
        }

        ArrayNode array = (ArrayNode) object.get("p");

        ObjectNode dataObject = mapper.createObjectNode();
        dataObject.set("channel", array.get(0));
        dataObject.set("data", array.get(1));

        object.put("event", "#publish");
        object.set("data", dataObject);

        if (array.has(2)) {
            object.set("cid", array.get(2));
        }

        object.remove("p");
    }

    private void decompressEmit(ObjectNode object) {
        if (!object.has("e") || object.get("e").isNull()) {
            return;
        }

        ArrayNode array = (ArrayNode) object.get("e");

        object.set("event", array.get(0));
        object.set("data", array.get(1));

        if (array.has(2)) {
            object.set("cid", array.get(2));
        }

        object.remove("e");
    }
}
