package io.springperf.web.json;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONReader;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;

/**
 * Fastjson2 实现，默认启用反序列化硬化：
 * 关闭 {@code SupportAutoType}（禁止 {@code @type} 指定任意类，阻断 gadget 链反序列化攻击），
 * 并开启 {@code ErrorOnNotSupportAutoType}（遇到 {@code @type} 时抛异常而非静默忽略）。
 */
public class FastjsonConverter implements JsonConverter {

    static {
        JSON.config(JSONReader.Feature.SupportAutoType, false);
        JSON.config(JSONReader.Feature.ErrorOnNotSupportAutoType, true);
    }

    @Override
    public String toJson(Object obj) {
        return JSON.toJSONString(obj);
    }

    @Override
    public byte[] toJsonBytes(Object obj) {
        return JSON.toJSONBytes(obj);
    }

    @Override
    public void toJson(OutputStream outputStream, Object obj) {
        JSON.writeTo(outputStream, obj);
    }

    @Override
    public Object fromJson(String json, Type type) {
        return JSON.parseObject(json, type);
    }

    @Override
    public Object fromJson(byte[] json, Type type) {
        return JSON.parseObject(json, type);
    }

    @Override
    public Object fromJson(InputStream json, Type type) {
        return JSON.parseObject(json, type);
    }
}
