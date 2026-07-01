package io.springperf.webtest.batch;

import io.springperf.web.batch.annotation.BatchMapping;
import io.springperf.web.batch.common.BatchRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class BatchEchoController {

    @RequestMapping("/batch/echo")
    public BatchRequest<String> echo(@RequestParam String msg) {
        return null;
    }

    @BatchMapping(method = "echo")
    public void batchEcho(List<EchoBatchRequest> requests) {
        for (EchoBatchRequest req : requests) {
            String injectedMsg = req.msg != null ? req.msg : "null";
            req.setResult("batched:" + requests.size() + ":msg=" + injectedMsg);
        }
    }

    @RequestMapping("/batch/echo/{pathVal}")
    public BatchRequest<String> echoMulti(@RequestParam String msg,
                                           @RequestBody UserBody body,
                                           @PathVariable String pathVal) {
        return null;
    }

    @BatchMapping(method = "echoMulti")
    public void batchEchoMulti(List<EchoBatchRequest> requests) {
        for (EchoBatchRequest req : requests) {
            String injectedMsg = req.msg != null ? req.msg : "null";
            String injectedName = req.body != null ? req.body.name : "null";
            int injectedAge = req.body != null ? req.body.age : -1;
            String injectedPathVal = req.pathVal != null ? req.pathVal : "null";
            req.setResult("batched:" + requests.size()
                    + ":msg=" + injectedMsg
                    + ":name=" + injectedName
                    + ":age=" + injectedAge
                    + ":pathVal=" + injectedPathVal);
        }
    }

    @RequestMapping("/batch/echo-batchsize")
    public BatchRequest<String> echoBatchSize(@RequestParam String msg) {
        return null;
    }

    @BatchMapping(method = "echoBatchSize", maxBatchSize = 2)
    public void batchEchoBatchSize(List<EchoBatchRequest> requests) {
        for (EchoBatchRequest req : requests) {
            String injectedMsg = req.msg != null ? req.msg : "null";
            req.setResult("batched:" + requests.size() + ":msg=" + injectedMsg + ":maxBatchSize=2");
        }
    }

    @RequestMapping("/batch/echo-consumers")
    public BatchRequest<String> echoConsumers(@RequestParam String msg) {
        return null;
    }

    @RequestMapping("/batch/echo-error")
    public BatchRequest<String> echoError(@RequestParam String msg) {
        return null;
    }

    @BatchMapping(method = "echoError")
    public void batchEchoError(List<EchoBatchRequest> requests) {
        throw new RuntimeException("intentional batch error for test");
    }

    @BatchMapping(method = "echoConsumers", consumerSize = 2)
    public void batchEchoConsumers(List<EchoBatchRequest> requests) {
        for (EchoBatchRequest req : requests) {
            String injectedMsg = req.msg != null ? req.msg : "null";
            req.setResult("consumed:" + requests.size() + ":msg=" + injectedMsg + ":consumerSize=2");
        }
    }
}
