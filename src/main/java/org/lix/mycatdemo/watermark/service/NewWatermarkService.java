package org.lix.mycatdemo.watermark.service;

import com.alibaba.nacos.api.utils.StringUtils;
import javafx.util.Pair;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import net.coobird.thumbnailator.geometry.Position;
import net.coobird.thumbnailator.geometry.Positions;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

@Slf4j
@Service
public class NewWatermarkService implements InitializingBean {

//    @Resource
//    private StorageService storageService;
//
//    @Resource
//    private StorageExecutor storageExecutor;

    /**
     * 水印图片缩放比例 (10%)
     */
    private static final float WATERMARK_SCALE_RATIO = 0.1f;

    /**
     * 默认透明度
     */
    private static final float DEFAULT_ALPHA = 1f;

    /**
     * 默认图片水印位置
     */
    private static final Position WATERMARK_POSITION = Positions.TOP_LEFT;

    /**
     * 默认水印图片路径（硬编码，可根据实际路径修改）
     */
    private static final String DEFAULT_WATERMARK_IMAGE_PATH = "static/default_watermark.png";

    /**
     * 缓存的默认水印图片（类初始化时加载，避免重复读取）
     */
    private static BufferedImage DEFAULT_WATERMARK_IMAGE;

    /**
     * javacv以及ffmpeg支持的类型
     */
    private List<String> validImageType = Arrays.asList("jpg", "jpeg", "png", "gif", "webp");
    private List<String> validVideoType = Arrays.asList("mp4");

    // -------------------------------------- 隐水印相关内容 --------------------------------------------------------------
    /**
     * watermarkMessage水印生成方相关信息
     */
    private static final String watermarkMessage = "tiangong";

    /**
     * 生成方签名
     */
    private static final byte[] sinkey = new byte[32];

    /**
     * 业务方业务编码
     */
    private static final String bizCode = "3100";


    @Override
    public void afterPropertiesSet() throws Exception {
        InputStream inputStream = WatermarkService.class.getClassLoader().getResourceAsStream(DEFAULT_WATERMARK_IMAGE_PATH);
        if (inputStream == null) {
            log.error("默认水印图片不存在，路径：{}", DEFAULT_WATERMARK_IMAGE_PATH);
            throw new RuntimeException("水印图片不存在");
        }
        DEFAULT_WATERMARK_IMAGE = ImageIO.read(inputStream);
        log.info("默认水印图片加载成功，路径：{}", DEFAULT_WATERMARK_IMAGE_PATH);

//        MessageDigest digest = null;
//        try {
//            digest = MessageDigest.getInstance("SHA-256");
//            digest.update(watermarkMessage.getBytes(StandardCharsets.UTF_8));
//            byte[] ans = digest.digest();
//            System.arraycopy(ans, 0, sinkey, 0, Math.min(ans.length, 32));
//        } catch (NoSuchAlgorithmException e) {
//            log.error("SHA-256算法初始化失败，执行降级策略", e);
//            byte[] bytes = watermarkMessage.getBytes(StandardCharsets.UTF_8);
//            System.arraycopy(bytes, 0, sinkey, 0, Math.min(bytes.length, 32));
//        }
    }

    /**
     * 执行加水印的实际逻辑
     * @param originalUrl 本地的源地址"https://" + publicEndpoint + "/" + bucket + "/" + key
     *                    key = storageId + "/" + type.name() + "/" + filename;
     */
    public String watermarkAppend(String originalUrl) throws Exception {
        if(StringUtils.isBlank(originalUrl)) {
            log.error("源地址为空");
            throw new RuntimeException("源地址为空");
        }
        String fileExtension = originalUrl.substring(originalUrl.lastIndexOf(".") + 1);
        log.info("fileName:{},fileExtension:{}", originalUrl, fileExtension);
        File watermarkFile = new File("F:/" + UUID.randomUUID().toString().replace("-", "") + "." + fileExtension);
        try(InputStream is = Files.newInputStream(Paths.get(originalUrl));
            OutputStream os = Files.newOutputStream(watermarkFile.getAbsoluteFile().toPath())){
            if(validImageType.contains(fileExtension)) {
                addImageWatermark2Image(is, os, fileExtension);
            } else if(validVideoType.contains(fileExtension)) {
                // 暂不支持
                log.info("暂不支持图片水印");
                throw new RuntimeException("暂不支持图片水印");
                //addImageWatermark2Video(is, os, fileExtension);
            }
        }
        return watermarkFile.getAbsolutePath();
    }


    /**
     * 参数为输入输出流，其关闭操作由上层进行处理
     * @param fileExt 文件后缀名，用于写入输出流
     */
    private void addImageWatermark2Image(InputStream inputStream, OutputStream outputStream, String fileExt) throws Exception {
        log.info("开始执行加水印");
        try(ImageInputStream iis = ImageIO.createImageInputStream(inputStream)) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);

            if (readers.hasNext()) {
                ImageReader reader = readers.next();
                reader.setInput(iis);

                // 读取元数据
                IIOMetadata metadata = reader.getImageMetadata(0);

                // 读取源图
                BufferedImage sourceImage = reader.read(0);
                if (sourceImage == null) {
                    log.error("源图读取失败");
                    throw new RuntimeException("源图读取失败");
                }

                // 缩放水印图片
                BufferedImage watermarkImage =  scaleWatermarkImage(DEFAULT_WATERMARK_IMAGE, sourceImage, WATERMARK_SCALE_RATIO);
                if (watermarkImage == null) {
                    log.error("水印图片损坏，无法读取或缩放");
                    throw new RuntimeException("水印图片损坏，无法读取或缩放");
                }
                ImageWriter writer = ImageIO.getImageWritersByFormatName(fileExt).next();

                BufferedImage processedImage = addImageWatermark(sourceImage, outputStream, watermarkImage, fileExt);

                try (ImageOutputStream ios = ImageIO.createImageOutputStream(outputStream)) {
                    writer.setOutput(ios);

                    // 如果有元数据，尝试写入
                    if (metadata != null) {
                        writer.write(null, new IIOImage(processedImage, null, metadata), null);
                    } else {
                        writer.write(processedImage);
                    }
                }

                writer.dispose();
                reader.dispose();
            }
        }
    }


    /**
     * 缩放水印图片（传入缓存的BufferedImage，默认水印专用）
     */
    private BufferedImage scaleWatermarkImage(BufferedImage originalWatermark, BufferedImage sourceImage, Float ratio) throws IOException {
        // 计算目标尺寸：源图片的10%（等比例）
        Pair<Integer, Integer> targetWidthAndHeight = getTargetWidthAndHeight(sourceImage.getWidth(),
                sourceImage.getHeight(),
                originalWatermark.getWidth(),
                originalWatermark.getHeight(), ratio);
        int targetWatermarkWidth = targetWidthAndHeight.getKey();
        int targetWatermarkHeight = targetWidthAndHeight.getValue();

        // 使用Thumbnails缩放
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            Thumbnails.of(originalWatermark)
                    .size(targetWatermarkWidth, targetWatermarkHeight)
                    .keepAspectRatio(true)
                    .outputQuality(1.0f)
                    .outputFormat("png")
                    .toOutputStream(bos);
            return ImageIO.read(new ByteArrayInputStream(bos.toByteArray()));
        }
    }

    /**
     * 基于Thumbnails添加图片水印
     */
    private BufferedImage addImageWatermark(BufferedImage sourceImage,
                                            OutputStream outputStream,
                                            BufferedImage watermarkImage,
                                            String fileExt) throws IOException {
        int sourceWidth = sourceImage.getWidth();
        int sourceHeight = sourceImage.getHeight();

        int marginX = Math.min((int) Math.ceil(sourceWidth * 0.01f), 20);
        int marginY = Math.min((int) Math.ceil(sourceHeight * 0.01f), 20);
        int margin = Math.min(marginY, marginX);
        return Thumbnails.of(sourceImage)
                .size(sourceImage.getWidth(), sourceImage.getHeight())
                .watermark(WATERMARK_POSITION, watermarkImage, DEFAULT_ALPHA, margin)
                .outputQuality(1.0f)
                .outputFormat(fileExt)
                .asBufferedImage();
    }

    /**
     * 计算缩放后的水印宽高
     */
    private Pair<Integer, Integer> getTargetWidthAndHeight(int sourceWidth,
                                                           int sourceHeight,
                                                           int originalWatermarkWidth,
                                                           int originalWatermarkHeight,
                                                           float ratio){
        if(sourceWidth < sourceHeight){
            int targetWatermarkWidth = (int) Math.ceil(sourceWidth * ratio);
            double tagetRatio = (double) originalWatermarkHeight / originalWatermarkWidth;
            int targetWatermarkHeight = (int) Math.ceil(targetWatermarkWidth * tagetRatio);
            return new Pair<>(targetWatermarkWidth, targetWatermarkHeight);
        }
        int targetWatermarkHeight = (int) Math.ceil(sourceHeight * ratio);
        double tagetRatio = (double) originalWatermarkWidth / originalWatermarkHeight;
        int targetWatermarkWidth = (int) Math.ceil(targetWatermarkHeight * tagetRatio);
        return new Pair<>(targetWatermarkWidth, targetWatermarkHeight);
    }
}