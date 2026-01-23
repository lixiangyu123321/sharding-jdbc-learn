package org.lix.mycatdemo.watermark.service;

import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import org.apache.tomcat.util.http.fileupload.IOUtils;
import org.lix.mycatdemo.watermark.type.ImageTypeEnum;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.servlet.http.HttpServletResponse;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class ImageWatermarkService {

    /**
     * 允许的文件类型
     */
    //private static final String[] ALLOWED_IMAGE_TYPES = {"image/jpeg", "image/png", "image/jpg"};
    /**
     * 最大文件大小
     */
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024;

    /**
     * 水印图片缩放比例
     */
    private static final float WATERMARK_SCALE_RATIO = 0.05f;

    public ResponseEntity<?> addImageWatermark(
            MultipartFile file,
            String watermarkText,
            Float alpha,
            String position,
            HttpServletResponse response
    ){
        Map<String, String> errorMsg = new HashMap<>();
        // 校验文件是否为空
        if (file == null || file.isEmpty()) {
            errorMsg.put("file", "上传的图片文件不能为空");
            return ResponseEntity.badRequest().body(buildResult(false, null, errorMsg));
        }
        // 校验文件大小
        if (file.getSize() > MAX_FILE_SIZE) {
            errorMsg.put("file", "图片大小不能超过5MB");
            return ResponseEntity.badRequest().body(buildResult(false, null, errorMsg));
        }
        // 校验文件类型
        String contentType = file.getContentType();
        if (!isAllowedType(contentType)) {
            errorMsg.put("file", "仅支持JPG/PNG/JPEG格式的图片");
            return ResponseEntity.badRequest().body(buildResult(false, null, errorMsg));
        }
        // 校验透明度
        if (alpha < 0 || alpha > 1) {
            errorMsg.put("alpha", "透明度必须在0.0-1.0之间");
            return ResponseEntity.badRequest().body(buildResult(false, null, errorMsg));
        }

        try(InputStream inputStream = file.getInputStream()) {
            // XXX 第一个工具类ImageIO
            BufferedImage sourceImage = ImageIO.read(inputStream);
            if (sourceImage == null) {
                errorMsg.put("file", "图片文件损坏，无法读取");
                return ResponseEntity.badRequest().body(buildResult(false, null, errorMsg));
            }

            // TODO 添加图片水印
            BufferedImage watermarkedImage = addTextWatermark(sourceImage, watermarkText, alpha, position);

            // XXX 关于内存字节输出流
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            String fileExt = file.getOriginalFilename().substring(file.getOriginalFilename().lastIndexOf(".") + 1);
            if(ImageTypeEnum.JPG.getExtension().equals(fileExt)){
                fileExt = "jpeg";
            }
            // XXX 将水印图片写入内存流
            ImageIO.write(watermarkedImage, fileExt, bos);
            byte[] imageBytes = bos.toByteArray();

            response.setContentType(ImageTypeEnum.of(fileExt).getMediaTypeString());
            // XXX 告诉浏览器如何处理返回的文件（图片）—— 强制触发文件下载，而非在浏览器中直接预览，并指定下载文件的名称。
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + URLEncoder.encode("watermark_" + file.getOriginalFilename(), "UTF-8"));
            response.setContentLength(imageBytes.length);

            response.getOutputStream().write(imageBytes);
            response.getOutputStream().flush();

            // XXX 响应体的输入都是map类型，就是将map类型转换为json
            return ResponseEntity.ok().body(buildResult(true, "水印添加成功", null));

        } catch (IOException e) {
            // 异常捕获
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            errorMsg.put("system", "图片处理失败：" + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(buildResult(false, null, errorMsg));
        }
    }

//    /**
//     * 添加图片水印
//     */
//    public ResponseEntity<?> addImageWatermark(
//            MultipartFile file,
//            MultipartFile watermark,
//            Float alpha,
//            String position,
//            HttpServletResponse response
//    ) throws IOException {
//        // TODO 等比例缩小水印图片至加水印图片的2.5%
//        // TODO 流程与添加文本水印类似
//        String ext = watermark.getOriginalFilename().substring(watermark.getOriginalFilename().lastIndexOf(".") + 1);
//        File watermarkFile = File.createTempFile("tmp_", ext);
//        try (InputStream is = watermark.getInputStream();
//            OutputStream os = Files.newOutputStream(watermarkFile.toPath())) {
//            IOUtils.copyLarge(is, os);
//        } catch (Exception e) {
//            log.error("下载图片失败, path:{}", watermarkFile, e);
//            return null;
//        }
//        Thumbnails.of(watermarkFile)
//                .size(width, height)
//                .keepAspectRatio(false)
//                .outputQuality(1)
//                .toOutputStream(outputStream);
//        return null;
//    }

    public ResponseEntity<?> addImageWatermark(
            MultipartFile file,          // 源图片
            MultipartFile watermarkFile, // 水印图片
            Float alpha,                 // 透明度
            String position,
            Float radio,
            HttpServletResponse response
    ) {
        Map<String, String> errorMsg = new HashMap<>();
        // 1. 基础参数校验
        // 校验源图片
        if (file == null || file.isEmpty()) {
            errorMsg.put("file", "上传的图片文件不能为空");
            return ResponseEntity.badRequest().body(buildResult(false, null, errorMsg));
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            errorMsg.put("file", "源图片大小不能超过5MB");
            return ResponseEntity.badRequest().body(buildResult(false, null, errorMsg));
        }
        String sourceContentType = file.getContentType();
        if (!isAllowedType(sourceContentType)) {
            errorMsg.put("file", "源图片仅支持JPG/PNG/JPEG格式");
            return ResponseEntity.badRequest().body(buildResult(false, null, errorMsg));
        }

        // 校验水印图片
        if (watermarkFile == null || watermarkFile.isEmpty()) {
            errorMsg.put("watermark", "水印图片不能为空");
            return ResponseEntity.badRequest().body(buildResult(false, null, errorMsg));
        }
        if (watermarkFile.getSize() > MAX_FILE_SIZE) {
            errorMsg.put("watermark", "水印图片大小不能超过5MB");
            return ResponseEntity.badRequest().body(buildResult(false, null, errorMsg));
        }
        String watermarkContentType = watermarkFile.getContentType();
        if (!isAllowedType(watermarkContentType)) {
            errorMsg.put("watermark", "水印图片仅支持JPG/PNG/JPEG格式");
            return ResponseEntity.badRequest().body(buildResult(false, null, errorMsg));
        }

        // 校验透明度
        if (alpha == null || alpha < 0 || alpha > 1) {
            errorMsg.put("alpha", "透明度必须在0.0-1.0之间");
            return ResponseEntity.badRequest().body(buildResult(false, null, errorMsg));
        }

        try(InputStream inputStream = file.getInputStream()) {
            // 2. 读取源图片
            BufferedImage sourceImage = ImageIO.read(inputStream);
            if (sourceImage == null) {
                errorMsg.put("file", "源图片文件损坏，无法读取");
                return ResponseEntity.badRequest().body(buildResult(false, null, errorMsg));
            }

            // 3. 处理水印图片：等比例缩放到源图片的2.5%
            BufferedImage watermarkImage = scaleWatermarkImage(watermarkFile, sourceImage, radio);
            if (watermarkImage == null) {
                errorMsg.put("watermark", "水印图片损坏，无法读取或缩放");
                return ResponseEntity.badRequest().body(buildResult(false, null, errorMsg));
            }

            // 4. 添加图片水印
            BufferedImage watermarkedImage = addImageWatermarkToSource(sourceImage, watermarkImage, alpha, position);

            // 5. 写入内存流并返回
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            String fileExt = getFileExtension(file.getOriginalFilename());
            ImageIO.write(watermarkedImage, fileExt, bos);
            byte[] imageBytes = bos.toByteArray();

            // 设置响应头
            response.setContentType(ImageTypeEnum.of(fileExt).getMediaTypeString());
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=" + URLEncoder.encode("watermark_" + file.getOriginalFilename(), "UTF-8"));
            response.setContentLength(imageBytes.length);

            // 输出图片
            response.getOutputStream().write(imageBytes);
            response.getOutputStream().flush();

            return ResponseEntity.ok().body(buildResult(true, "图片水印添加成功", null));

        } catch (IOException e) {
            log.error("添加图片水印失败", e);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            errorMsg.put("system", "图片水印处理失败：" + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(buildResult(false, null, errorMsg));
        }
    }

    /**
     * 缩放水印图片：等比例缩放到源图片的2.5%大小
     */
    private BufferedImage scaleWatermarkImage(MultipartFile watermarkFile, BufferedImage sourceImage, Float radio) throws IOException {
        // 读取水印图片
        try(InputStream inputStream = watermarkFile.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            BufferedImage originalWatermark = ImageIO.read(inputStream);
            if (originalWatermark == null) {
                return null;
            }

            // 计算目标尺寸：源图片的2.5%（等比例）
            int sourceWidth = sourceImage.getWidth();
            int sourceHeight = sourceImage.getHeight();
            int targetWatermarkWidth = (int) (sourceWidth * radio);
            // 等比例计算高度
            double ratio = (double) originalWatermark.getHeight() / originalWatermark.getWidth();
            int targetWatermarkHeight = (int) (targetWatermarkWidth * ratio);

            // 使用Thumbnails缩放水印图片（保持比例，高质量）
            Thumbnails.of(originalWatermark)
                    .size(targetWatermarkWidth, targetWatermarkHeight)
                .keepAspectRatio(true)
                .outputQuality(1.0f)
                .outputFormat("jpg")
                .toOutputStream(bos);

            // 转换为BufferedImage返回
            return ImageIO.read(new ByteArrayInputStream(bos.toByteArray()));
        }

    }

    /**
     * 给源图片添加图片水印
     */
    private BufferedImage addImageWatermarkToSource(BufferedImage sourceImage, BufferedImage watermarkImage,
                                                    float alpha, String position) {
        // 创建可编辑的图片副本
        BufferedImage targetImage = new BufferedImage(
                sourceImage.getWidth(),
                sourceImage.getHeight(),
                // 兼容PNG透明水印：使用TYPE_INT_ARGB
                sourceImage.getType() == BufferedImage.TYPE_INT_RGB ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB
        );
        Graphics2D g2d = targetImage.createGraphics();

        // 绘制原始图片
        g2d.drawImage(sourceImage, 0, 0, null);

        // 设置水印透明度
        g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));

        // 计算水印位置
        Point watermarkPos = calculateWatermarkPosition(sourceImage, watermarkImage, position);

        // 绘制水印图片
        g2d.drawImage(watermarkImage, watermarkPos.x, watermarkPos.y, null);

        // 释放资源
        g2d.dispose();

        return targetImage;
    }

    /**
     * 计算水印图片的位置
     */
    private Point calculateWatermarkPosition(BufferedImage sourceImage, BufferedImage watermarkImage, String position) {
        int sourceWidth = sourceImage.getWidth();
        int sourceHeight = sourceImage.getHeight();
        int watermarkWidth = watermarkImage.getWidth();
        int watermarkHeight = watermarkImage.getHeight();

        int x = 0, y = 0;
        String pos = position == null ? "RIGHT_BOTTOM" : position.toUpperCase();

        switch (pos) {
            case "LEFT_TOP":
                // 左上角（偏移20px）
                x = 20;
                y = 20;
                break;
            case "LEFT_BOTTOM":
                // 左下角
                x = 20;
                y = sourceHeight - watermarkHeight - 20;
                break;
            case "RIGHT_TOP":
                // 右上角
                x = sourceWidth - watermarkWidth - 20;
                y = 20;
                break;
            case "CENTER":
                // 正中间
                x = (sourceWidth - watermarkWidth) / 2;
                y = (sourceHeight - watermarkHeight) / 2;
                break;
            case "RIGHT_BOTTOM":
            default:
                // 右下角（默认）
                x = sourceWidth - watermarkWidth - 20;
                y = sourceHeight - watermarkHeight - 20;
                break;
        }
        return new Point(x, y);
    }

    /**
     * 获取文件扩展名（统一处理JPG/JPEG）
     */
    private String getFileExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "jpeg";
        }
        String ext = fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
        return "jpg".equals(ext) ? "jpeg" : ext;
    }

    /**
     * 判断文件类型是否合法
     */
    private boolean isAllowedType(String contentType) {
        for(ImageTypeEnum imageTypeEnum : ImageTypeEnum.values()) {
            if(contentType.equalsIgnoreCase(imageTypeEnum.getMediaTypeString())) {
                return true;
            }
        }
        return false;
    }


    /**
     * 添加文字水印的核心方法
     */
    private BufferedImage addTextWatermark(BufferedImage sourceImage, String text, float alpha, String position) {
        // 创建可编辑的图片副本
        BufferedImage targetImage = new BufferedImage(
                sourceImage.getWidth(),
                sourceImage.getHeight(),
                BufferedImage.TYPE_INT_RGB
        );
        Graphics2D g2d = targetImage.createGraphics();

        // 绘制原始图片
        g2d.drawImage(sourceImage, 0, 0, null);

        // 设置水印样式
        g2d.setColor(Color.RED);
        g2d.setFont(new Font("微软雅黑", Font.PLAIN, 30));
        g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));

        // 计算水印位置
        FontMetrics metrics = g2d.getFontMetrics();
        int textWidth = metrics.stringWidth(text);
        int textHeight = metrics.getHeight();
        int x = 0, y = 0;

        switch (position.toUpperCase()) {
            case "LEFT_TOP":
                x = 20;
                y = textHeight + 20;
                break;
            case "CENTER":
                x = (sourceImage.getWidth() - textWidth) / 2;
                y = (sourceImage.getHeight() + textHeight) / 2;
                break;
            case "RIGHT_BOTTOM":
            default:
                x = sourceImage.getWidth() - textWidth - 20;
                y = sourceImage.getHeight() - 20;
                break;
        }

        // 绘制水印文字
        g2d.drawString(text, x, y);
        g2d.dispose();

        return targetImage;
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
