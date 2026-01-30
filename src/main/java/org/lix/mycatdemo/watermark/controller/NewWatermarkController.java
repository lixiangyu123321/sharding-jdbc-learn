package org.lix.mycatdemo.watermark.controller;

import lombok.extern.slf4j.Slf4j;
import org.lix.mycatdemo.watermark.service.NewWatermarkService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

@Slf4j
@RestController
@RequestMapping("/api/newWatermark")
public class NewWatermarkController {


    @Resource
    private NewWatermarkService newWatermarkService;

    @PostMapping("/append")
    public String addWatermark2Image(@RequestParam("file") String filePath) throws Exception {
        log.info("开始为{}添加水印", filePath);

        return newWatermarkService.watermarkAppend(filePath);
    }

    @ExceptionHandler
    public String exceptionHandler(Exception e) {
        log.error(e.getMessage());
        return e.toString();
    }
}