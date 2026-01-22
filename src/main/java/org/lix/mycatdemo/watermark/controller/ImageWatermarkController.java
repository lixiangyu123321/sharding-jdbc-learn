package org.lix.mycatdemo.watermark.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.lix.mycatdemo.watermark.service.ImageWatermarkService;

import java.util.HashMap;
import java.util.Map;

/**
 * 图片水印接口控制器（生产级优化版）
 * @CrossOrigin 运行跨域
 */
@Slf4j
@RestController
@RequestMapping("/api/image/watermark")
@CrossOrigin
public class ImageWatermarkController {

    @Resource
    private ImageWatermarkService imageWatermarkService;




    /**
     * 添加图片水印接口
     * @param file 上传的图片文件
     * @param watermarkText 水印文字（可选，默认"版权所有"）
     * @param alpha 水印透明度（可选，默认0.5）
     * @param position 水印位置（可选，默认右下角：RIGHT_BOTTOM，可选值：LEFT_TOP/CENTER/RIGHT_BOTTOM）
     * @param response 响应对象（用于返回带水印的图片文件）
     * @return 统一响应结果（失败时返回JSON，成功时返回图片文件）
     */
    @PostMapping("/text")
    public ResponseEntity<?> addImageWatermark(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "watermarkText", defaultValue = "版权所有") String watermarkText,
            @RequestParam(value = "alpha", defaultValue = "0.5") Float alpha,
            @RequestParam(value = "position", defaultValue = "RIGHT_BOTTOM") String position,
            HttpServletResponse response) {
        log.info("开始添加文本水印");
        ResponseEntity<?> ans =  imageWatermarkService.addImageWatermark(file, watermarkText, alpha, position, response);
        if(ans.getStatusCode().equals(HttpStatus.OK)) {
            // 成功表示写入响应，返回类型设置为了jpg
            return null;
        }
        return ans;
    }

    @PostMapping
    public ResponseEntity<?> addImageWatermark(
            @RequestParam("file") MultipartFile file,
            @RequestParam("watermark") MultipartFile watermark,
            @RequestParam(value = "alpha", defaultValue = "0.5") Float alpha,
            @RequestParam(value = "position", defaultValue = "RIGHT_BOTTOM") String position,
            HttpServletResponse response
    ){
        log.info("开始添加图片水印");
        ResponseEntity<?> ans =  imageWatermarkService.addImageWatermark(file, watermark, alpha, position, response);
        if(ans.getStatusCode().equals(HttpStatus.OK)) {
            return null;
        }
        return null;
    }


    /**
     * 全局异常处理器（兜底）
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGlobalException(Exception e) {
        Map<String, String> errorMsg = new HashMap<>();
        errorMsg.put("system", "服务器内部错误：" + e.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(buildResult(false, null, errorMsg));
    }

    /**
     * 构建统一响应结果
     */
    private Map<String, Object> buildResult(boolean success, String msg, Map<String, String> errors) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", success);
        result.put("message", msg);
        result.put("errors", errors);
        return result;
    }
}