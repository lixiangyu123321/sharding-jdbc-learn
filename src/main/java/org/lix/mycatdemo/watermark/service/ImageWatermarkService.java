package org.lix.mycatdemo.watermark.service;

import com.alibaba.nacos.api.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import org.lix.mycatdemo.watermark.type.ImageTypeEnum;
import org.lix.mycatdemo.watermark.type.PositionEnum;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
    // ===================== 常量配置 =====================
    /**
     * 最大文件大小 (5MB)
     */
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;

    /**
     * 水印图片缩放比例 (5%)
     */
    private static final float WATERMARK_SCALE_RATIO = 0.05f;

    /**
     * 默认水印图片路径（硬编码，可根据实际路径修改）
     */
    private static final String DEFAULT_WATERMARK_IMAGE_PATH = "E:/default_watermark.jpg";

    /**
     * 缓存的默认水印图片（类初始化时加载，避免重复读取）
     * XXX 将图片预先读取到内存中
     */
    private static BufferedImage DEFAULT_WATERMARK_IMAGE;

    /**
     * 静态初始化，将图片信息加载值BufferedImage
     * XXX 将水印图片预先读取到内存中
     */
    static {
        try {
            File defaultWatermarkFile = new File(DEFAULT_WATERMARK_IMAGE_PATH);
            if (defaultWatermarkFile.exists() && defaultWatermarkFile.isFile()) {
                DEFAULT_WATERMARK_IMAGE = ImageIO.read(defaultWatermarkFile);
                log.info("默认水印图片加载成功，路径：{}", DEFAULT_WATERMARK_IMAGE_PATH);
            } else {
                log.error("默认水印图片不存在，路径：{}", DEFAULT_WATERMARK_IMAGE_PATH);
                DEFAULT_WATERMARK_IMAGE = null;
            }
        } catch (IOException e) {
            log.error("加载默认水印图片失败", e);
            DEFAULT_WATERMARK_IMAGE = null;
        }
    }

    // ===================== 对外接口（兼容原有调用） =====================
    /**
     * 添加文字水印（对外接口）
     */
    public ResponseEntity<?> addTextWatermark(
            MultipartFile file,
            String watermarkText,
            Float alpha,
            String position,
            HttpServletResponse response
    ) {
        Map<String, String> errorMsg = new HashMap<>();
        // 参数校验
        if (file == null || file.isEmpty()) {
            errorMsg.put("file", "上传的图片文件不能为空");
            return ResponseEntity.badRequest().body(buildResult(false, null, errorMsg));
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            // XXX 这里对图片内容限制一下，但是没有太大必要
            errorMsg.put("file", "图片大小不能超过5MB");
            return ResponseEntity.badRequest().body(buildResult(false, null, errorMsg));
        }
        String contentType = file.getContentType();
        if (!isAllowedType(contentType)) {
            errorMsg.put("file", "仅支持JPG/PNG/JPEG格式的图片");
            return ResponseEntity.badRequest().body(buildResult(false, null, errorMsg));
        }
        if (alpha == null || alpha < 0 || alpha > 1) {
            errorMsg.put("alpha", "透明度必须在0.0-1.0之间");
            return ResponseEntity.badRequest().body(buildResult(false, null, errorMsg));
        }
        if(!isAllowedPosition(position)){
            log.info("不合法的位置输入");
            errorMsg.put("position", "不合理的位置输入，位置必须在" + PositionEnum.values());
            return ResponseEntity.badRequest().body(buildResult(false, null, errorMsg));
        }

        try(InputStream inputStream = file.getInputStream()) {
            // 核心修复1：将MultipartFile的流复制到ByteArrayInputStream（可重置）
            // QUESTION 这里为什么不直接用MultipleFile的输入流，待会试一下
            // XXX 这里没有必要使用内存字节流
//            byte[] sourceBytes = file.getBytes();
//            ByteArrayInputStream sourceIs = new ByteArrayInputStream(sourceBytes);
            String fileExt = getFileExtension(file.getOriginalFilename());

            // 调用改造后的核心方法（使用可重置的ByteArrayInputStream）
            try (OutputStream outputStream = response.getOutputStream()) {
                addTextWatermark(inputStream, outputStream, watermarkText, alpha, position, fileExt);

                // 设置响应头
                response.setContentType(ImageTypeEnum.of(fileExt).getMediaTypeString());
                response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=" + URLEncoder.encode("watermark_" + file.getOriginalFilename(), "UTF-8"));
            }

            return ResponseEntity.ok().body(buildResult(true, "水印添加成功", null));
        } catch (IOException e) {
            log.error("添加文字水印失败", e);
            errorMsg.put("system", "图片处理失败：" + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(buildResult(false, null, errorMsg));
        }
    }

    private boolean isAllowedPosition(String position) {
        if(StringUtils.isBlank(position)){
            return false;
        }
        for(PositionEnum positionEnum : PositionEnum.values()){
            if(positionEnum.name().equalsIgnoreCase(position)){
                return true;
            }
        }
        return false;
    }

    /**
     * 添加图片水印（对外接口）
     * 若未传入水印图片，则使用默认水印图片
     */
    public ResponseEntity<?> addImageWatermark(
            MultipartFile file,
            MultipartFile watermarkFile,
            Float alpha,
            String position,
            Float radio,
            HttpServletResponse response
    ) {
        Map<String, String> errorMsg = new HashMap<>();
        // 源图片参数校验
        if (file == null || file.isEmpty()) {
            errorMsg.put("file", "上传的图片文件不能为空");
            return ResponseEntity.badRequest().body(buildResult(false, null, errorMsg));
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            errorMsg.put("file", "源图片大小不能超过10MB");
            return ResponseEntity.badRequest().body(buildResult(false, null, errorMsg));
        }
        String sourceContentType = file.getContentType();
        if (!isAllowedType(sourceContentType)) {
            errorMsg.put("file", "图片仅支持JPG/PNG/JPEG格式");
            return ResponseEntity.badRequest().body(buildResult(false, null, errorMsg));
        }
        // 透明度校验
        if (alpha == null || alpha < 0 || alpha > 1) {
            errorMsg.put("alpha", "透明度必须在0.0-1.0之间");
            return ResponseEntity.badRequest().body(buildResult(false, null, errorMsg));
        }
        if(!isAllowedPosition(position)){
            log.info("不合法的位置输入");
            errorMsg.put("position", "不合理的位置输入，位置必须在" + PositionEnum.values());
            return ResponseEntity.badRequest().body(buildResult(false, null, errorMsg));
        }

        try(InputStream inputStream = file.getInputStream()) {
            // QUESTION 这里也是,为什么直接读取MultipleFile的信息到内存字节数组，直接读取到BufferedImage不行吗？
            // XXX 注意下面的reset操作，因为需要对流操作两次，所以需要支持reset的ByteArrayInputStream
//            byte[] sourceBytes = file.getBytes();
//            ByteArrayInputStream sourceIs = new ByteArrayInputStream(sourceBytes);
            // 第一次读取源图片（用于缩放水印）
            BufferedImage sourceImage = ImageIO.read(inputStream);
            if (sourceImage == null) {
                errorMsg.put("file", "源图片文件损坏，无法读取");
                return ResponseEntity.badRequest().body(buildResult(false, null, errorMsg));
            }

            // 重置字节输入流（ByteArrayInputStream支持reset）
            // sourceIs.reset();

            // 处理水印图片
            BufferedImage watermarkImage;
            if (watermarkFile != null && !watermarkFile.isEmpty()) {
                // 校验传入的水印图片
                if (watermarkFile.getSize() > MAX_FILE_SIZE) {
                    errorMsg.put("watermark", "水印图片大小不能超过10MB");
                    return ResponseEntity.badRequest().body(buildResult(false, null, errorMsg));
                }
                String watermarkContentType = watermarkFile.getContentType();
                if (!isAllowedType(watermarkContentType)) {
                    errorMsg.put("watermark", "水印图片仅支持JPG/PNG/JPEG格式");
                    return ResponseEntity.badRequest().body(buildResult(false, null, errorMsg));
                }
                // 核心修复3：水印图片流使用try-with-resources确保关闭
                try (InputStream watermarkIs = watermarkFile.getInputStream()) {
                    // XXX 拿到InputStream，再转为BufferedImage，进行相应的操作后获得压缩水印图片的BufferedImage
                    watermarkImage = scaleWatermarkImage(watermarkIs, sourceImage, radio);
                }
            } else {
                // 使用默认水印图片
                if (DEFAULT_WATERMARK_IMAGE == null) {
                    errorMsg.put("watermark", "默认水印图片未配置或加载失败");
                    return ResponseEntity.badRequest().body(buildResult(false, null, errorMsg));
                }
                watermarkImage = scaleWatermarkImage(DEFAULT_WATERMARK_IMAGE, sourceImage, radio);
            }

            if (watermarkImage == null) {
                errorMsg.put("watermark", "水印图片损坏，无法读取或缩放");
                return ResponseEntity.badRequest().body(buildResult(false, null, errorMsg));
            }

            // 调用核心方法添加水印
            // XXX 这里的fileExt只有在设置响应体格式的时候以及返回图片类型的时候有用
            String fileExt = getFileExtension(file.getOriginalFilename());
            try (OutputStream outputStream = response.getOutputStream()) {
                addImageWatermarkToSource(sourceImage, outputStream, watermarkImage, alpha, position, fileExt);

                // 设置响应头
                response.setContentType(ImageTypeEnum.of(fileExt).getMediaTypeString());
                response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=" + URLEncoder.encode("watermark_" + file.getOriginalFilename(), "UTF-8"));
            }

            return ResponseEntity.ok().body(buildResult(true, "图片水印添加成功", null));
        } catch (IOException e) {
            log.error("添加图片水印失败", e);
            errorMsg.put("system", "图片水印处理失败：" + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(buildResult(false, null, errorMsg));
        }
    }

    // ===================== 改造后的核心方法（InputStream/OutputStream 参数） =====================
    /**
     * 添加文字水印（核心方法，InputStream输入源图片，OutputStream输出结果）
     * @param sourceIs 源图片输入流（需为可重置的ByteArrayInputStream）
     * @param targetOs 结果输出流
     * @param text 水印文字
     * @param alpha 透明度
     * @param position 水印位置
     * @param format 输出图片格式（jpeg/png）
     */
    public void addTextWatermark(InputStream sourceIs, OutputStream targetOs,
                                 String text, float alpha, String position, String format) throws IOException {
        // 读取源图片
        BufferedImage sourceImage = ImageIO.read(sourceIs);
        if (sourceImage == null) {
            throw new IOException("源图片文件损坏，无法读取");
        }

        // 创建可编辑的图片副本
        int imageType = (sourceImage.getTransparency() == Transparency.OPAQUE)
                ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB;
        BufferedImage targetImage = new BufferedImage(sourceImage.getWidth(), sourceImage.getHeight(), imageType);
        Graphics2D g2d = targetImage.createGraphics();

        // 开启抗锯齿
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

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

        // QUESTION 将位置信息的操作抽取
        String pos = position == null ? "RIGHT_BOTTOM" : position.toUpperCase();
        switch (pos) {
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
                x = Math.max(20, sourceImage.getWidth() - textWidth - 20);
                y = Math.max(textHeight + 20, sourceImage.getHeight() - 20);
                break;
        }

        // 绘制水印文字
        g2d.drawString(text, x, y);
        g2d.dispose();

        // 写入输出流
        ImageIO.write(targetImage, format, targetOs);
        targetOs.flush();
    }

    /**
     * 添加图片水印（核心方法，InputStream输入源图片，OutputStream输出结果）
     * @param sourceImage 源图片输入流（需为可重置的ByteArrayInputStream）
     * @param targetOs 结果输出流
     * @param watermarkImage 水印图片（已缩放）
     * @param alpha 透明度
     * @param position 水印位置
     * @param format 输出图片格式（jpeg/png）
     */
    public void addImageWatermarkToSource(BufferedImage sourceImage, OutputStream targetOs,
                                          BufferedImage watermarkImage, float alpha, String position, String format) throws IOException {
        // 读取源图片
        if (sourceImage == null) {
            throw new IOException("源图片文件损坏，无法读取");
        }

        // 创建可编辑的图片副本
        int imageType = (sourceImage.getTransparency() == Transparency.OPAQUE)
                ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB;
        BufferedImage targetImage = new BufferedImage(sourceImage.getWidth(), sourceImage.getHeight(), imageType);
        Graphics2D g2d = targetImage.createGraphics();

        // 开启抗锯齿
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

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

        // 写入输出流
        ImageIO.write(targetImage, format, targetOs);
        targetOs.flush();
    }

    // ===================== 辅助方法 =====================
    /**
     * 缩放水印图片（传入InputStream）
     */
    private BufferedImage scaleWatermarkImage(InputStream watermarkIs, BufferedImage sourceImage, Float radio) throws IOException {
        BufferedImage originalWatermark = ImageIO.read(watermarkIs);
        if (originalWatermark == null) {
            return null;
        }
        return scaleWatermarkImage(originalWatermark, sourceImage, radio);
    }

    /**
     * 缩放水印图片（传入缓存的BufferedImage，默认水印专用）
     */
    private BufferedImage scaleWatermarkImage(BufferedImage originalWatermark, BufferedImage sourceImage, Float radio) throws IOException {
        // 计算目标尺寸：源图片的5%（等比例）
        int sourceWidth = sourceImage.getWidth();
        int sourceHeight = sourceImage.getHeight();
        int targetWatermarkWidth = (int) (sourceWidth * radio);
        double ratio = (double) originalWatermark.getHeight() / originalWatermark.getWidth();
        int targetWatermarkHeight = (int) (targetWatermarkWidth * ratio);

        // 使用Thumbnails缩放（显式指定格式，解决Output format异常）
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            Thumbnails.of(originalWatermark)
                    .size(targetWatermarkWidth, targetWatermarkHeight)
                    .keepAspectRatio(true)
                    .outputQuality(1.0f)
                    .outputFormat("png") // 默认使用png保证透明性
                    .toOutputStream(bos);
            return ImageIO.read(new ByteArrayInputStream(bos.toByteArray()));
        }
    }

    /**
     * 计算水印位置
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
                x = 20;
                y = 20;
                break;
            case "LEFT_BOTTOM":
                x = 20;
                y = Math.max(20, sourceHeight - watermarkHeight - 20);
                break;
            case "RIGHT_TOP":
                x = Math.max(20, sourceWidth - watermarkWidth - 20);
                y = 20;
                break;
            case "CENTER":
                x = (sourceWidth - watermarkWidth) / 2;
                y = (sourceHeight - watermarkHeight) / 2;
                break;
            case "RIGHT_BOTTOM":
            default:
                x = Math.max(20, sourceWidth - watermarkWidth - 20);
                y = Math.max(20, sourceHeight - watermarkHeight - 20);
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
        if (contentType == null) {
            return false;
        }
        return "MP4".equalsIgnoreCase(contentType);
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

    // ===================== 工具方法：获取默认水印图片（对外暴露） =====================
    public BufferedImage getDefaultWatermarkImage() {
        return DEFAULT_WATERMARK_IMAGE;
    }
}