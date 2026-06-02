package io.springperf.web.json;

import com.alibaba.fastjson2.JSON;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;

public class FastjsonConverter implements JsonConverter {
    @Override
    public String toJson(Object obj) {
        return JSON.toJSONString(obj);
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
