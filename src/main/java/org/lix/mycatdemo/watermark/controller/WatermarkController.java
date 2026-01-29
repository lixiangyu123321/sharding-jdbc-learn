package org.lix.mycatdemo.watermark.controller;

import lombok.extern.slf4j.Slf4j;
import org.lix.mycatdemo.watermark.service.WatermarkService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/watermark/test")
public class WatermarkController {

    @Resource
    private WatermarkService watermarkService;

    @RequestMapping("/append")
    public String append(@RequestParam("filePath") String filePath,
                         @RequestParam("useFFmpeg") boolean useFFmpeg,
                         @RequestParam("isScale") boolean isScale,
                         @RequestParam("useFFprobe") boolean useFFprobe) throws Exception {
        log.info("开始进行添加水印");
        return watermarkService.executeWatermarkAppend(filePath, useFFmpeg, isScale, useFFprobe);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGlobalException(Exception e) {
        Map<String, String> errorMsg = new HashMap<>();
        errorMsg.put("system", "服务器内部错误：" + e.getMessage());
        log.error("{}", errorMsg, e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(buildResult(false, null, errorMsg));
    }

    private Map<String, Object> buildResult(boolean success, String msg, Map<String, String> errors) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", success);
        result.put("message", msg);
        result.put("errors", errors);
        return result;
    }
}
