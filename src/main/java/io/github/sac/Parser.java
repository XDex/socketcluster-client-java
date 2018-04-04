package io.github.sac;


import com.fasterxml.jackson.databind.JsonNode;

/**
 * Created by sachin on 15/11/16.
 */
public class Parser {

    public enum ParseResult {
        ISAUTHENTICATED,
        PUBLISH,
        REMOVETOKEN,
        SETTOKEN,
        EVENT,
        ACKRECEIVE
    }


    public static ParseResult parse(JsonNode dataobject, String event) {
        if (dataobject.has("isAuthenticated")) {
            return ParseResult.ISAUTHENTICATED;
        }

        if (event != null) {
            switch (event) {
                case "#publish":
                    return ParseResult.PUBLISH;
                case "#removeAuthToken":
                    return ParseResult.REMOVETOKEN;
                case "#setAuthToken":
                    return ParseResult.SETTOKEN;
                default:
                    return ParseResult.EVENT;
            }
        }

        return ParseResult.ACKRECEIVE;
    }

}
