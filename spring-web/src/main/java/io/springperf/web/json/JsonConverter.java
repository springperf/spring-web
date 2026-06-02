package io.springperf.web.json;

import io.springperf.web.context.WebComponent;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;

/**
 * Abstraction for JSON serialization and deserialization.
 * <p>
 * Plugs into the web framework to convert Java objects to JSON and vice versa.
 */
public interface JsonConverter extends WebComponent {

    /**
     * Serializes the given object to a JSON string.
     */
    String toJson(Object obj);

    /**
     * Serializes the given object and writes the JSON to the specified output stream.
     */
    void toJson(OutputStream outputStream, Object obj);

    /**
     * Deserializes the given JSON string to an object of the specified type.
     */
    Object fromJson(String json, Type type);

    /**
     * Deserializes the given JSON byte array to an object of the specified type.
     */
    Object fromJson(byte[] json, Type type);

    /**
     * Deserialises the JSON from the given input stream to an object of the specified type.
     */
    Object fromJson(InputStream json, Type type);
}
