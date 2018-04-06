package io.github.sac;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.neovisionaries.ws.client.*;
import io.github.sac.codec.SocketClusterCodec;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * Created by sachin on 13/11/16.
 */

public class Socket extends Emitter {

    private final static Logger LOGGER = Logger.getLogger(Socket.class.getName());

    private AtomicInteger counter;
    private String URL;
    private WebSocketFactory factory;
    private ReconnectStrategy strategy;
    private WebSocket ws;
    private BasicListener listener;
    private String AuthToken;
    private HashMap<Long, Object[]> acks;
    private List<Channel> channels;
    private WebSocketAdapter adapter;
    private Map<String, String> headers;
    private SocketClusterCodec codec;
    private int connectionTimeout = 5000;

    private static final ObjectMapper mapper = new ObjectMapper();

    public Socket(String URL) {
        this.URL = URL;
        factory = new WebSocketFactory();
        counter = new AtomicInteger(1);
        acks = new HashMap<>();
        channels = new ArrayList<>();
        adapter = getAdapter();
        headers = new HashMap<>();
        putDefaultHeaders();
    }

    private void putDefaultHeaders() {
        headers.put("Accept-Encoding", "gzip, deflate, sdch");
        headers.put("Accept-Language", "en-US,en;q=0.8");
        headers.put("Pragma", "no-cache");
        headers.put("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/49.0.2623.87 Safari/537.36");
    }

    public Channel createChannel(String name) {
        Channel channel = new Channel(name);
        channels.add(channel);
        return channel;
    }

    public List<Channel> getChannels() {
        return channels;
    }

    public Channel getChannelByName(String name) {
        for (Channel channel : channels) {
            if (channel.getChannelName().equals(name))
                return channel;
        }
        return null;
    }

    public void seturl(String url) {
        this.URL = url;
    }

    public void setReconnection(ReconnectStrategy strategy) {
        this.strategy = strategy;
    }

    public void setListener(BasicListener listener) {
        this.listener = listener;
    }

    public void setCodec(SocketClusterCodec codec) {
        this.codec = codec;
    }

    public void setConnectionTimeout(int timeout) {
        connectionTimeout = timeout;
    }

    /**
     * used to set up TLS/SSL connection to server for more details visit neovisionaries websocket client
     */

    public WebSocketFactory getFactorySettings() {
        return factory;
    }

    public void setAuthToken(String token) {
        AuthToken = token;
    }

    private void send(WebSocket webSocket, String data) {
        send(webSocket, new TextNode(data));
    }

    private void send(JsonNode data) {
        send(ws, data);
    }

    private void send(WebSocket webSocket, JsonNode data) {
        if (codec == null) {
            webSocket.sendText(data.toString());
        } else {
            webSocket.sendBinary(codec.encode(data));
        }
    }

    public WebSocketAdapter getAdapter() {
        return new WebSocketAdapter() {

            @Override
            public void onConnected(WebSocket websocket, Map<String, List<String>> headers) {

                /**
                 * Code for sending handshake
                 */

                counter.set(1);
                if (strategy != null) {
                    strategy.setAttemptsMade(0);
                }

                ObjectNode handshakeObject = mapper.createObjectNode();
                handshakeObject.put("event", "#handshake");

                ObjectNode object = mapper.createObjectNode();
                object.put("authToken", AuthToken);

                handshakeObject.set("data", object);
                handshakeObject.put("cid", counter.getAndIncrement());

                send(websocket, handshakeObject);

                listener.onConnected(Socket.this, headers);
            }

            @Override
            public void onDisconnected(WebSocket websocket, WebSocketFrame serverCloseFrame, WebSocketFrame clientCloseFrame, boolean closedByServer) throws Exception {
                listener.onDisconnected(Socket.this, serverCloseFrame, clientCloseFrame, closedByServer);
                reconnect();
            }

            @Override
            public void onConnectError(WebSocket websocket, WebSocketException exception) throws Exception {
                listener.onConnectError(Socket.this, exception);
                reconnect();
            }


            @Override
            public void onFrame(WebSocket websocket, WebSocketFrame frame) throws Exception {
                JsonNode payload;

                if (codec == null) {
                    payload = getTextPayload(frame.getPayloadText());
                } else {
                    payload = codec.decode(frame.getPayload());
                }

                if (payload.isTextual() && payload.asText().equalsIgnoreCase("#1")) {
                    send(websocket, "#2"); // PONG
                    return;
                }

                LOGGER.info("Message: " + payload.toString());

                JsonNode dataobject = payload.get("data");
                Integer rid = payload.get("rid").asInt();
                Integer cid = payload.get("cid").asInt();
                String event = payload.get("event").asText();

                switch (Parser.parse(dataobject, event)) {
                    case ISAUTHENTICATED:
                        listener.onAuthentication(Socket.this, dataobject.get("isAuthenticated").asBoolean());
                        subscribeChannels();
                        break;
                    case PUBLISH:
                        Socket.this.handlePublish(dataobject.get("channel").asText(), dataobject.get("data"));
                        break;
                    case REMOVETOKEN:
                        setAuthToken(null);
                        break;
                    case SETTOKEN:
                        String token = dataobject.get("token").asText();
                        setAuthToken(token);
                        listener.onSetAuthToken(token, Socket.this);
                        break;
                    case EVENT:
                        if (hasEventAck(event)) {
                            handleEmitAck(event, dataobject, ack(Long.valueOf(cid)));
                        } else {
                            Socket.this.handleEmit(event, dataobject);
                        }
                        break;
                    case ACKRECEIVE:
                        if (acks.containsKey((long) rid)) {
                            Object[] objects = acks.remove((long) rid);
                            if (objects != null) {
                                Ack fn = (Ack) objects[1];
                                if (fn != null) {
                                    fn.call((String) objects[0], payload.get("error"), dataobject);
                                } else {
                                    LOGGER.info("ack function is null with rid " + rid);
                                }
                            }
                        }
                        break;
                }
            }

            private JsonNode getTextPayload(String payloadText) throws IOException {
                try {
                    return mapper.readTree(payloadText);
                } catch (JsonParseException e) {
                    return mapper.valueToTree(payloadText);
                }
            }

            @Override
            public void onCloseFrame(WebSocket websocket, WebSocketFrame frame) throws Exception {
                LOGGER.info("On close frame got called");
            }

            @Override
            public void onSendError(WebSocket websocket, WebSocketException cause, WebSocketFrame frame) throws Exception {
                LOGGER.info("Got send error");
            }

        };

    }

    private void setDataField(ObjectNode object, Object data) {
        if (data instanceof JsonNode) {
            object.set("data", (JsonNode) data);
        } else {
            object.putPOJO("data", data);
        }
    }

    public Socket emit(final String event, final Object data) {
        EventThread.exec(new Runnable() {
            public void run() {
                ObjectNode eventObject = mapper.createObjectNode();
                eventObject.put("event", event);
                setDataField(eventObject, data);
                send(eventObject);
            }
        });
        return this;
    }

    public Socket emit(final String event, final Object data, final Ack ack) {
        EventThread.exec(new Runnable() {
            public void run() {
                ObjectNode eventObject = mapper.createObjectNode();
                acks.put(counter.longValue(), getAckObject(event, ack));
                eventObject.put("event", event);
                setDataField(eventObject, data);
                eventObject.put("cid", counter.getAndIncrement());
                send(eventObject);
            }
        });
        return this;
    }

    private Socket subscribe(final String channel) {
        EventThread.exec(new Runnable() {
            public void run() {
                ObjectNode subscribeObject = mapper.createObjectNode();
                subscribeObject.put("event", "#subscribe");
                subscribeObject.set("data", mapper.createObjectNode().put("channel", channel));

                subscribeObject.put("cid", counter.getAndIncrement());
                send(subscribeObject);
            }
        });
        return this;
    }

    private Object[] getAckObject(String event, Ack ack) {
        Object object[] = {event, ack};
        return object;
    }

    private Socket subscribe(final String channel, final Ack ack) {
        EventThread.exec(new Runnable() {
            public void run() {
                ObjectNode subscribeObject = mapper.createObjectNode();
                subscribeObject.put("event", "#subscribe");
                acks.put(counter.longValue(), getAckObject(channel, ack));
                subscribeObject.set("data", mapper.createObjectNode().put("channel", channel));
                subscribeObject.put("cid", counter.getAndIncrement());
                send(subscribeObject);
            }
        });
        return this;
    }

    private Socket unsubscribe(final String channel) {
        EventThread.exec(new Runnable() {
            public void run() {
                ObjectNode subscribeObject = mapper.createObjectNode();
                subscribeObject.put("event", "#unsubscribe");
                subscribeObject.put("data", channel);
                subscribeObject.put("cid", counter.getAndIncrement());
                send(subscribeObject);
            }
        });
        return this;
    }

    private Socket unsubscribe(final String channel, final Ack ack) {
        EventThread.exec(new Runnable() {
            public void run() {
                ObjectNode subscribeObject = mapper.createObjectNode();
                subscribeObject.put("event", "#unsubscribe");
                subscribeObject.put("data", channel);

                acks.put(counter.longValue(), getAckObject(channel, ack));
                subscribeObject.put("cid", counter.getAndIncrement());
                send(subscribeObject);
            }
        });
        return this;
    }

    public Socket publish(final String channel, final Object data) {
        EventThread.exec(new Runnable() {
            public void run() {
                ObjectNode publishObject = mapper.createObjectNode();
                publishObject.put("event", "#publish");

                ObjectNode dataObject = mapper.createObjectNode();
                dataObject.put("channel", channel);
                setDataField(dataObject, data);
                publishObject.set("data", dataObject);

                publishObject.put("cid", counter.getAndIncrement());
                send(publishObject);
            }
        });

        return this;
    }

    public Socket publish(final String channel, final Object data, final Ack ack) {
        EventThread.exec(new Runnable() {
            public void run() {
                ObjectNode publishObject = mapper.createObjectNode();
                publishObject.put("event", "#publish");

                ObjectNode dataObject = mapper.createObjectNode();
                acks.put(counter.longValue(), getAckObject(channel, ack));
                dataObject.put("channel", channel);
                setDataField(dataObject, data);
                publishObject.set("data", dataObject);

                publishObject.put("cid", counter.getAndIncrement());
                send(publishObject);
            }
        });

        return this;
    }

    private Ack ack(final Long cid) {
        return new Ack() {
            public void call(final String channel, final JsonNode error, final JsonNode data) {
                EventThread.exec(new Runnable() {
                    public void run() {
                        ObjectNode object = mapper.createObjectNode();
                        object.set("error", error);
                        object.set("data", data);
                        object.put("rid", cid);
                        send(object);
                    }
                });
            }
        };
    }


    private void subscribeChannels() {
        for (Channel channel : channels) {
            channel.subscribe();
        }
    }

    public void setExtraHeaders(Map<String, String> extraHeaders, boolean overrideDefaultHeaders) {
        if (overrideDefaultHeaders) {
            headers.clear();
        }

        headers.putAll(extraHeaders);
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    private void setupConnection() {
        try {
            factory.setConnectionTimeout(connectionTimeout);
            ws = factory.createSocket(URL);
        } catch (IOException e) {
            e.printStackTrace();
        }
        ws.addExtension("permessage-deflate; client_max_window_bits");
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            ws.addHeader(entry.getKey(), entry.getValue());
        }

        ws.addListener(adapter);
    }

    public void connect() {
        setupConnection();
        try {
            ws.connect();
        } catch (OpeningHandshakeException e) {
            // A violation against the WebSocket protocol was detected
            // during the opening handshake.

            // Status line.
            StatusLine sl = e.getStatusLine();
            LOGGER.info("=== Status Line ===");
            LOGGER.info("HTTP Version  = \n" + sl.getHttpVersion());
            LOGGER.info("Status Code   = \n" + sl.getStatusCode());
            LOGGER.info("Reason Phrase = \n" + sl.getReasonPhrase());

            // HTTP headers.
            Map<String, List<String>> headers = e.getHeaders();
            LOGGER.info("=== HTTP Headers ===");
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                // Header name.
                String name = entry.getKey();

                // Values of the header.
                List<String> values = entry.getValue();

                if (values == null || values.size() == 0) {
                    // Print the name only.
                    LOGGER.info(name);
                    continue;
                }

                for (String value : values) {
                    // Print the name and the value.
                    LOGGER.info(name + value + "\n");
                }
            }
        } catch (WebSocketException e) {
            // Failed to establish a WebSocket connection.
            listener.onConnectError(Socket.this, e);
            reconnect();
        }

    }

    public void connectAsync() {
        setupConnection();
        ws.connectAsynchronously();
    }

    private void reconnect() {
        if (strategy == null) {
            LOGGER.info("Unable to reconnect: reconnection is null");
            return;
        }

        if (strategy.areAttemptsComplete()) {
            strategy.setAttemptsMade(0);
            LOGGER.info("Unable to reconnect: max reconnection attempts reached");
            return;
        }

        final Timer timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (strategy == null) {
                    LOGGER.info("Unable to reconnect: reconnection is null");
                    return;
                }
                strategy.processValues();
                Socket.this.connect();
                timer.cancel();
                timer.purge();
            }
        }, strategy.getReconnectInterval());
    }

    public void disconnect() {
        ws.disconnect();
        strategy = null;
    }

    /**
     * States can be
     * CLOSED
     * CLOSING
     * CONNECTING
     * CREATED
     * OPEN
     */

    public WebSocketState getCurrentState() {
        return ws.getState();
    }

    public Boolean isconnected() {
        return ws != null && ws.getState() == WebSocketState.OPEN;
    }

    public void disableLogging() {
        LogManager.getLogManager().reset();
    }

    /**
     * Channels need to be subscribed everytime whenever client is reconnected to server (handled inside)
     * Add only one listener to one channel for whole lifetime of process
     */

    public class Channel {

        String channelName;

        public String getChannelName() {
            return channelName;
        }

        public Channel(String channelName) {
            this.channelName = channelName;
        }

        public void subscribe() {
            Socket.this.subscribe(channelName);
        }

        public void subscribe(Ack ack) {
            Socket.this.subscribe(channelName, ack);
        }

        public void onMessage(Listener listener) {
            Socket.this.onSubscribe(channelName, listener);
        }

        public void publish(Object data) {
            Socket.this.publish(channelName, data);
        }

        public void publish(Object data, Ack ack) {
            Socket.this.publish(channelName, data, ack);
        }

        public void unsubscribe() {
            Socket.this.unsubscribe(channelName);
            channels.remove(this);
        }

        public void unsubscribe(Ack ack) {
            Socket.this.unsubscribe(channelName, ack);
            channels.remove(this);
        }
    }
}
