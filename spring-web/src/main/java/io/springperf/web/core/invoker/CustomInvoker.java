package io.springperf.web.core.invoker;

import io.springperf.web.core.mapping.match.Matcher;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

public interface CustomInvoker extends Invoker{

    Method getHandleMethod();

    default List<Matcher> getMatchers(){
        return Collections.emptyList();
    }

    String getType();
}
