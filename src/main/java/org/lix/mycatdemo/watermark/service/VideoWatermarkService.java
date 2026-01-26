package org.lix.mycatdemo.watermark.service;

import groovy.io.FileType;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.servlet.http.HttpServletResponse;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;

/**
 * 主要还是起到一个工具类的作用
 * 所以异常尽可能向上抛即可
 */
@Slf4j
@Service
public class VideoWatermarkService {

    public void addTextWatermark(MultipartFile inputVideo, MultipartFile watermark, HttpServletResponse response) throws Exception {
        // 1. 入参校验
        if (inputVideo == null || watermark == null || inputVideo.getSize() == 0 || watermark.getSize() == 0) {
            throw new IllegalArgumentException("上传的视频文件不能为空");
        }
        String originalFilename = inputVideo.getOriginalFilename();
        if (originalFilename == null || !originalFilename.contains(".")) {
            throw new IllegalArgumentException("视频文件名无效，无法识别格式");
        }
        String ext = originalFilename.substring(originalFilename.lastIndexOf("."));
        FileType fileType = null;
        for(FileType type : FileType.values()){
            if(ext.equalsIgnoreCase(type.name())){
                fileType = type;
                break;
            }
        }
        addVideoWatermark(inputVideo.getInputStream(), response.getOutputStream(), ImageIO.read(watermark.getInputStream()), fileType);
    }

    public void addVideoWatermark(InputStream inputStream, OutputStream outputStream, BufferedImage watermark, FileType fileType) throws IOException {
        // TODO 判断这里的文件类型， 或者将这里的判断向上抽取，保证来到这里的都是符合类型的文件类型
        log.info("Adding video watermark");
        String extension = fileType.name();
        File tempOutputVideo = File.createTempFile("video_watermark_", extension);
        tempOutputVideo.deleteOnExit();

        FFmpegFrameGrabber grabber = null;
        FFmpegFrameRecorder recorder = null;
        try{
            grabber = new FFmpegFrameGrabber(inputStream);
            // 关键：禁用抓取器的像素格式自动检测，避免传递bgr24参数
            grabber.setImageWidth(grabber.getImageWidth());
            grabber.start();

            // TODO 获得视频帧数，便于后续加上指定时长水印
            long totalFrames = grabber.getLengthInFrames();
            long videoFrames = (long) (grabber.getFrameRate() * grabber.getLengthInTime() / 1000000.0);

            recorder = new FFmpegFrameRecorder(
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
                    if(count < 10){
                        BufferedImage image = converter.getBufferedImage(frame);
                        // 绘制图片水印
                        BufferedImage targetImage = drawImageWatermark(image, watermark);
                        Frame watermarkedFrame = converter.convert(targetImage);
                        recorder.record(watermarkedFrame);
                    } else{
                        recorder.record(frame);
                    }
                } else if (frame.samples != null) {
                    // 音频帧直接写入
                    recorder.record(frame);
                }
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
            Files.deleteIfExists(tempOutputVideo.toPath());
        }
        log.info("Video watermark added");
    }

    private BufferedImage drawImageWatermark(BufferedImage sourceImage, BufferedImage watermarkImage) {
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
        // TODO 设置水印透明度
        g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));

        // 计算水印位置
        Point watermarkPos = calculateWatermarkPosition(sourceImage, watermarkImage);

        // 绘制水印图片
        g2d.drawImage(watermarkImage, watermarkPos.x, watermarkPos.y, null);

        // 释放资源
        g2d.dispose();

        return targetImage;
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
}
