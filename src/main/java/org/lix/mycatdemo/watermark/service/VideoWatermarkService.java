package org.lix.mycatdemo.watermark.service;

import com.alibaba.cloud.commons.io.IOUtils;
import com.alibaba.nacos.api.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
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
import java.awt.font.FontRenderContext;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * 主要还是起到一个工具类的作用
 * 所以异常尽可能向上抛即可
 */
@Slf4j
@Service
public class VideoWatermarkService {


    /**
     * 最大文件大小 (20MB)
     */
    private static final long MAX_FILE_SIZE = 20 * 1024 * 1024;

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
            errorMsg.put("file", "上传的视频文件不能为空");
            return ResponseEntity.badRequest().body(buildResult(false, null, errorMsg));
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            // XXX 这里对图片内容限制一下，但是没有太大必要
            errorMsg.put("file", "视频大小不能超过20MB");
            return ResponseEntity.badRequest().body(buildResult(false, null, errorMsg));
        }
        String contentType = file.getContentType();
        // TODO 这里对文件类型进行一次筛选
//        if (!isAllowedType(contentType)) {
//            errorMsg.put("file", "仅支持mp4格式的图片");
//            return ResponseEntity.badRequest().body(buildResult(false, null, errorMsg));
//        }
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
            // XXX 这里的文件后缀需要指定临时文件的类型
            String originalFilename = file.getOriginalFilename();
            String ext = originalFilename.substring(originalFilename.lastIndexOf("."));

            // 调用改造后的核心方法（使用可重置的ByteArrayInputStream）
            try (OutputStream outputStream = response.getOutputStream()) {
                addTextWatermark(inputStream, outputStream, watermarkText, alpha, position, ext);

                // 设置响应头
                // QUESTION 视频类型的响应应该怎么设置
                // XXX 暂时设置这样的文件类型
                response.setContentType(getMimeType(ext));
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


    public void addTextWatermark(InputStream inputStream, OutputStream outputStream, String watermarkText, Float alpha, String position, String extension) throws IOException {
        // QUESTION 这里使用临时文件进行相关操作
        // XXX 这里必须使用临时文件进行相关操作，因为recorder需要推断目标输出流类型
        // 修复：确保扩展名包含点号，FFmpeg 需要从文件名推断格式
        File tempOutputVideo = File.createTempFile("video_watermark_", extension);
        tempOutputVideo.deleteOnExit();

        FFmpegFrameGrabber grabber = null;
        FFmpegFrameRecorder recorder = null;
        try{
            grabber = new FFmpegFrameGrabber(inputStream);
            // 关键：禁用抓取器的像素格式自动检测，避免传递bgr24参数
            grabber.setImageWidth(grabber.getImageWidth());
            grabber.start();

            long totalFrames = grabber.getLengthInFrames();
            // 可选：获取仅视频帧数量（总帧数 - 音频帧数，或通过帧率+时长计算）
            long videoFrames = (long) (grabber.getFrameRate() * grabber.getLengthInTime() / 1000000.0);
            log.info("视频元数据读取完成 - 总帧数：{}，视频帧数量：{}", totalFrames, videoFrames);

            recorder = new FFmpegFrameRecorder(
                    // QUESTION 这里可不可以直接使用输出流的OutputStream
                    // XXX 这里必须使用临时文件作为输出流
                    tempOutputVideo,
                    grabber.getImageWidth(),
                    grabber.getImageHeight(),
                    grabber.getAudioChannels()
            );
            // 1. 编码器：H.264（必选，兼容所有播放器）
            recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
            // 2. 封装格式：和原视频一致（避免格式不兼容）
            recorder.setFormat(grabber.getFormat());
            // 3. 帧率/音频采样率：继承原视频（保证音视频同步）
            recorder.setFrameRate(grabber.getFrameRate());
            recorder.setSampleRate(grabber.getSampleRate());
            // 4. 视频码率：继承原视频（JavaCV自动适配像素格式和CRF）
            recorder.setVideoBitrate(grabber.getVideoBitrate());
            recorder.start();

            Java2DFrameConverter converter = new Java2DFrameConverter();
            org.bytedeco.javacv.Frame frame;
            int count = 0;
            while ((frame = grabber.grabFrame()) != null) {
                count++;
                if (frame.image != null) {
                    // 帧转图片 → 绘制水印 → 转回帧
                    // TODO 这里可以指定水印时长
                    // XXX 先将整个视频加上水印
                    if(count < totalFrames){
                        BufferedImage image = converter.getBufferedImage(frame);
                        drawTextWatermark(image, watermarkText, alpha, position);
                        Frame watermarkedFrame = converter.convert(image);
                        recorder.record(watermarkedFrame);
                    } else{
                        // 直接记录原始帧（不加水印的帧）
                        // 原始帧已经是正确的像素格式，直接记录即可
                        recorder.record(frame);
                    }
                } else if (frame.samples != null) {
                    // 音频帧直接写入
                    recorder.record(frame);
                }
            }
            // XXX 用于大文件上传
            // XXX 关闭录制器，保证数据正常写出
            recorder.stop();
            grabber.stop();
            try(InputStream is = Files.newInputStream(tempOutputVideo.toPath())){
                IOUtils.copyLarge(is, outputStream);
            }
            outputStream.flush();
        }finally {
            if (recorder != null) {
                try {
                    recorder.release();
                } catch (Exception e) {
                    log.warn("录制器资源释放失败", e);
                }
            }
            if (grabber != null) {
                try {
                    grabber.release();
                } catch (Exception e) {
                    log.warn("抓取器资源释放失败", e);
                }
            }
            // 手动删除临时文件（立即释放磁盘）
            // QUESTION 图片相关操作
            // XXX 手动删除临时文件
            Files.deleteIfExists(tempOutputVideo.toPath());
        }
        log.info("Video watermark added");
    }















//    public void addTextWatermark(MultipartFile inputVideo, MultipartFile watermark, HttpServletResponse response) throws Exception {
//        // 1. 入参校验
//        if (inputVideo == null || watermark == null || inputVideo.getSize() == 0 || watermark.getSize() == 0) {
//            throw new IllegalArgumentException("上传的视频文件不能为空");
//        }
//        String originalFilename = inputVideo.getOriginalFilename();
//        if (originalFilename == null || !originalFilename.contains(".")) {
//            throw new IllegalArgumentException("视频文件名无效，无法识别格式");
//        }
//        String ext = originalFilename.substring(originalFilename.lastIndexOf("."));
//        FileType fileType = null;
//        for(FileType type : FileType.values()){
//            if(ext.equalsIgnoreCase(type.name())){
//                fileType = type;
//                break;
//            }
//        }
//        addVideoWatermark(inputVideo.getInputStream(), response.getOutputStream(), ImageIO.read(watermark.getInputStream()), fileType);
//    }

//    public void addVideoWatermark(InputStream inputStream, OutputStream outputStream, BufferedImage watermark, FileType fileType) throws IOException {
//        // TODO 判断这里的文件类型， 或者将这里的判断向上抽取，保证来到这里的都是符合类型的文件类型
//        log.info("Adding video watermark");
//        String extension = fileType.name();
//        File tempOutputVideo = File.createTempFile("video_watermark_", extension);
//        tempOutputVideo.deleteOnExit();
//
//        FFmpegFrameGrabber grabber = null;
//        FFmpegFrameRecorder recorder = null;
//        try{
//            grabber = new FFmpegFrameGrabber(inputStream);
//            // 关键：禁用抓取器的像素格式自动检测，避免传递bgr24参数
//            grabber.setImageWidth(grabber.getImageWidth());
//            grabber.start();
//
//            // TODO 获得视频帧数，便于后续加上指定时长水印
//            long totalFrames = grabber.getLengthInFrames();
//            long videoFrames = (long) (grabber.getFrameRate() * grabber.getLengthInTime() / 1000000.0);
//
//            recorder = new FFmpegFrameRecorder(
//                    tempOutputVideo,
//                    grabber.getImageWidth(),
//                    grabber.getImageHeight(),
//                    grabber.getAudioChannels()
//            );
//            // 1. 编码器：H.264（必选，兼容所有播放器）
//            recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
//            // 2. 封装格式：和原视频一致（避免格式不兼容）
//            recorder.setFormat(grabber.getFormat());
//            // 3. 帧率/音频采样率：继承原视频（保证音视频同步）
//            recorder.setFrameRate(grabber.getFrameRate());
//            recorder.setSampleRate(grabber.getSampleRate());
//            // 4. 视频码率：继承原视频（JavaCV自动适配像素格式和CRF）
//            recorder.setVideoBitrate(grabber.getVideoBitrate());
//            recorder.start();
//
//            Java2DFrameConverter converter = new Java2DFrameConverter();
//            org.bytedeco.javacv.Frame frame;
//            int count = 0;
//            while ((frame = grabber.grabFrame()) != null) {
//                count++;
//                if (frame.image != null) {
//                    // 帧转图片 → 绘制水印 → 转回帧
//                    // TODO 这里可以指定水印时长
//                    if(count < 10){
//                        BufferedImage image = converter.getBufferedImage(frame);
//                        // 绘制图片水印
//                        BufferedImage targetImage = drawTextWatermark(image, watermark);
//                        Frame watermarkedFrame = converter.convert(targetImage);
//                        recorder.record(watermarkedFrame);
//                    } else{
//                        recorder.record(frame);
//                    }
//                } else if (frame.samples != null) {
//                    // 音频帧直接写入
//                    recorder.record(frame);
//                }
//            }
//        }finally {
//            if (recorder != null) {
//                try {
//                    recorder.release();
//                } catch (Exception e) {
//                    log.warn("录制器资源释放失败", e);
//                }
//            }
//            if (grabber != null) {
//                try {
//                    grabber.release();
//                } catch (Exception e) {
//                    log.warn("抓取器资源释放失败", e);
//                }
//            }
//            // 手动删除临时文件（立即释放磁盘）
//            Files.deleteIfExists(tempOutputVideo.toPath());
//        }
//        log.info("Video watermark added");
//    }

    private void drawTextWatermark(BufferedImage image,
                                             String text, float alpha, String position) throws IOException {
        // 读取源图片
        if (image == null) {
            throw new IOException("源图片文件损坏，无法读取");
        }
        // 这里的文本水印与字体大小强相关
        Font font = new Font("微软雅黑", Font.PLAIN, 100);

        Graphics2D g = image.createGraphics();
        // 抗锯齿：让文字更清晰
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.BLACK);
        g.setFont(font);
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));

        // 计算文字位置（适配不同角落/居中）
        FontRenderContext frc = g.getFontRenderContext();
        Rectangle2D textBounds = font.getStringBounds(text, frc);
        int textWidth = (int) textBounds.getWidth();
        int textHeight = (int) textBounds.getHeight();
        int imgWidth = image.getWidth();
        int imgHeight = image.getHeight();

        // QUESTION 将位置信息的操作抽取
        int x = 0, y = 0, margin = 20;
        switch (position) {
            case "LEFT_TOP": // 左上角
                x = margin;
                y = margin + textHeight;
                break;
            case "RIGHT_TOP": // 右上角
                x = imgWidth - textWidth - margin;
                y = margin + textHeight;
                break;
            case "LEFT_BOTTOM": // 左下角
                x = margin;
                y = imgHeight - margin;
                break;
            case "RIGHT_BOTTOM": // 右下角（默认）
                x = imgWidth - textWidth - margin;
                y = imgHeight - margin;
                break;
            case "CENTER": // 居中
                x = (imgWidth - textWidth) / 2;
                y = (imgHeight + textHeight) / 2;
                break;
            default:
                x = imgWidth - textWidth - margin;
                y = imgHeight - margin;
        }

        // 绘制水印文字
        g.drawString(text, x, y);
        g.dispose();
    }

    private void drawImageWatermark(BufferedImage sourceImage, BufferedImage watermarkImage, Float alpha, String position) {
        // 创建可编辑的图片副本
//        int imageType = (sourceImage.getTransparency() == Transparency.OPAQUE)
//                ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB;
//        BufferedImage targetImage = new BufferedImage(sourceImage.getWidth(), sourceImage.getHeight(), imageType);

        Graphics2D g2d = sourceImage.createGraphics();

        // 开启抗锯齿
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        // 绘制原始图片
        g2d.drawImage(sourceImage, 0, 0, null);

        // 设置水印透明度
        // TODO 设置水印透明度
        g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));

        // 计算水印位置
        Point watermarkPos = calculateWatermarkPosition(sourceImage, watermarkImage);

        // 绘制水印图片
        g2d.drawImage(watermarkImage, watermarkPos.x, watermarkPos.y, null);

        // 释放资源
        g2d.dispose();
    }

    /**
     * 计算水印图片的位置
     */
    private Point calculateWatermarkPosition(BufferedImage sourceImage, BufferedImage watermarkImage) {
        int sourceWidth = sourceImage.getWidth();
        int sourceHeight = sourceImage.getHeight();
        int watermarkWidth = watermarkImage.getWidth();
        int watermarkHeight = watermarkImage.getHeight();

        int x = Math.max(20, sourceWidth - watermarkWidth - 20);
        int y = Math.max(20, sourceHeight - watermarkHeight - 20);

        return new Point(x, y);
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
        for (ImageTypeEnum imageTypeEnum : ImageTypeEnum.values()) {
            if (contentType.equalsIgnoreCase(imageTypeEnum.getMediaTypeString())) {
                return true;
            }
        }
        return false;
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

    public ResponseEntity<?> addImageWatermark(MultipartFile file,
                                               MultipartFile watermarkFile,
                                               Float alpha,
                                               String position,
                                               Float radio,
                                               HttpServletResponse response) {
        Map<String, String> errorMsg = new HashMap<>();
        // 参数校验
        if (file == null || file.isEmpty()) {
            errorMsg.put("file", "上传的视频文件不能为空");
            return ResponseEntity.badRequest().body(buildResult(false, null, errorMsg));
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            // XXX 这里对图片内容限制一下，但是没有太大必要
            errorMsg.put("file", "图片大小不能超过20MB");
            return ResponseEntity.badRequest().body(buildResult(false, null, errorMsg));
        }
//        String contentType = file.getContentType();
//        if (!isAllowedType(contentType)) {
//            errorMsg.put("file", "仅支持JPG/PNG/JPEG格式的图片");
//            return ResponseEntity.badRequest().body(buildResult(false, null, errorMsg));
//        }
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
            String originalFilename = file.getOriginalFilename();
            String ext = originalFilename.substring(originalFilename.lastIndexOf(".")).toLowerCase();

            //===============================================================
            // TODO 这里完成视频解析
            File tempOutputVideo = File.createTempFile("video_watermark_", ext);
            tempOutputVideo.deleteOnExit();

            FFmpegFrameGrabber grabber = null;
            FFmpegFrameRecorder recorder = null;
            BufferedImage watermarkImage = null;
            try{
                grabber = new FFmpegFrameGrabber(inputStream);
                // 关键：禁用抓取器的像素格式自动检测，避免传递bgr24参数
                grabber.setImageWidth(grabber.getImageWidth());
                grabber.start();

                long totalFrames = grabber.getLengthInFrames();
                // 可选：获取仅视频帧数量（总帧数 - 音频帧数，或通过帧率+时长计算）
                long videoFrames = (long) (grabber.getFrameRate() * grabber.getLengthInTime() / 1000000.0);
                log.info("视频元数据读取完成 - 总帧数：{}，视频帧数量：{}", totalFrames, videoFrames);

                recorder = new FFmpegFrameRecorder(
                        // QUESTION 这里可不可以直接使用输出流的OutputStream
                        // XXX 这里必须使用临时文件作为输出流
                        tempOutputVideo,
                        grabber.getImageWidth(),
                        grabber.getImageHeight(),
                        grabber.getAudioChannels()
                );
                // 1. 编码器：H.264（必选，兼容所有播放器）
                recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
                // 2. 封装格式：和原视频一致（避免格式不兼容）
                recorder.setFormat(grabber.getFormat());
                // 3. 帧率/音频采样率：继承原视频（保证音视频同步）
                recorder.setFrameRate(grabber.getFrameRate());
                recorder.setSampleRate(grabber.getSampleRate());
                // 4. 视频码率：继承原视频（JavaCV自动适配像素格式和CRF）
                recorder.setVideoBitrate(grabber.getVideoBitrate());
                recorder.start();

                Java2DFrameConverter converter = new Java2DFrameConverter();
                org.bytedeco.javacv.Frame frame;
                int count = 0;
                boolean isFirstFram = true;
                while ((frame = grabber.grabFrame()) != null) {
                    count++;
                    if (frame.image != null) {
                        // 帧转图片 → 绘制水印 → 转回帧
                        // TODO 这里可以指定水印时长
                        // XXX 先将整个视频加上水印
                        BufferedImage image = converter.getBufferedImage(frame);
                        if (isFirstFram) {
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
                                    // QUESTION 拿到视频文件的长度和宽度
                                    // QUESTION 这里单独抓一帧
                                    watermarkImage = scaleWatermarkImage(watermarkIs, image, radio);
                                }
                            } else {
                                // 使用默认水印图片
                                if (DEFAULT_WATERMARK_IMAGE == null) {
                                    errorMsg.put("watermark", "默认水印图片未配置或加载失败");
                                    return ResponseEntity.badRequest().body(buildResult(false, null, errorMsg));
                                }
                                watermarkImage = scaleWatermarkImage(DEFAULT_WATERMARK_IMAGE, image, radio);
                            }
                            if (watermarkImage == null) {
                                errorMsg.put("watermark", "水印图片损坏，无法读取或缩放");
                                return ResponseEntity.badRequest().body(buildResult(false, null, errorMsg));
                            }
                            isFirstFram = false;
                        }
                        if(count < totalFrames){
                            // TODO 这里将图片水印插入
                            drawImageWatermark(image, watermarkImage, alpha, position);
                            Frame watermarkedFrame = converter.convert(image);
                            recorder.record(watermarkedFrame);
                        } else{
                            // 直接记录原始帧（不加水印的帧）
                            // 原始帧已经是正确的像素格式，直接记录即可
                            recorder.record(frame);
                        }
                    } else if (frame.samples != null) {
                        // 音频帧直接写入
                        recorder.record(frame);
                    }
                }
                // XXX 用于大文件上传
                // XXX 关闭录制器，保证数据正常写出
                recorder.stop();
                grabber.stop();
                try(InputStream is = Files.newInputStream(tempOutputVideo.toPath());
                    OutputStream os = response.getOutputStream()){
                    IOUtils.copyLarge(is, os);
                    os.flush();
                }

            }finally {
                if (recorder != null) {
                    try {
                        recorder.release();
                    } catch (Exception e) {
                        log.warn("录制器资源释放失败", e);
                    }
                }
                if (grabber != null) {
                    try {
                        grabber.release();
                    } catch (Exception e) {
                        log.warn("抓取器资源释放失败", e);
                    }
                }
                // 手动删除临时文件（立即释放磁盘）
                // QUESTION 图片相关操作
                // XXX 手动删除临时文件
                Files.deleteIfExists(tempOutputVideo.toPath());
            }
            //===============================================================

            // 设置响应头
            response.setContentType(getMimeType(ext));
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=" + URLEncoder.encode("watermark_" + file.getOriginalFilename(), "UTF-8"));

            return ResponseEntity.ok().body(buildResult(true, "水印添加成功", null));
        } catch (IOException e) {
            log.error("添加文字水印失败", e);
            errorMsg.put("system", "图片处理失败：" + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(buildResult(false, null, errorMsg));
        }
    }

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

    public void addImageWatermark(InputStream inputStream, OutputStream outputStream, String watermarkText, Float alpha, String position, String fileExt) throws IOException {

    }

    private String getMimeType(String ext) {
        String lowerExt = ext.toLowerCase();
        switch (lowerExt) {
            case ".mp4":
                return "video/mp4";
            case ".mov":
                return "video/quicktime";
            case ".avi":
                return "video/x-msvideo";
            case ".flv":
                return "video/x-flv";
            case ".mkv":
                return "video/x-matroska";
            default:
                return "application/octet-stream";
        }
    }
}
