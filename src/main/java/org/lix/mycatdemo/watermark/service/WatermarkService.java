package org.lix.mycatdemo.watermark.service;

import com.alibaba.nacos.common.executor.NameThreadFactory;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import org.apache.tomcat.util.http.fileupload.IOUtils;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.lix.mycatdemo.watermark.util.HttpClientUtil;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

@Slf4j
@Service
public class WatermarkService implements InitializingBean, DisposableBean {

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
    private static final String WATERMARK_POSITION = "LEFT_TOP";

    /**
     * 默认水印图片路径（硬编码，可根据实际路径修改）
     */
    private static final String DEFAULT_WATERMARK_IMAGE_PATH = "static/default_watermark.png";

    /**
     * 缓存的默认水印图片（类初始化时加载，避免重复读取）
     */
    private static BufferedImage DEFAULT_WATERMARK_IMAGE;

    /**
     * 本地的水印图片绝对路径
     */
    private static String DEFAULT_WATERMARK_LOCAL_PATH;

    /**
     * 是否配置FFmpeg程序
     */
    private static boolean hasFFmpeg = false;

    @Resource
    private FFmpegService ffmpegService;

    /**
     * 使用线程池处理任务
     */
    private ExecutorService executorService;

    /**
     * 线程池核心线程数
     */
    private static final int CORE_POOL_SIZE = 5;

    /**
     * 线程池最大线程数
     */
    private static final int MAX_POOL_SIZE = 10;

    /**
     * 线程池空闲线程存活时间（秒）
     */
    private static final long KEEP_ALIVE_TIME = 60L;

    /**
     * 线程池队列容量
     */
    private static final int QUEUE_CAPACITY = 100;

    /**
     * javacv以及ffmpeg支持的类型
     */
    private List<String> validImageType = Arrays.asList("jpg", "jpeg", "png", "gif");
    private List<String> validVideoType = Arrays.asList("mp4", "avi", "flv", "mpeg");

    @Override
    public void afterPropertiesSet() throws Exception {
        InputStream inputStream = WatermarkService.class.getClassLoader().getResourceAsStream(DEFAULT_WATERMARK_IMAGE_PATH);
        if (inputStream == null) {
            log.error("默认水印图片不存在，路径：{}", DEFAULT_WATERMARK_IMAGE_PATH);
            // 水印图片不存在，直接失败
            throw new RuntimeException("水印图片不存在");
        }
        DEFAULT_WATERMARK_IMAGE = ImageIO.read(inputStream);
        log.info("默认水印图片加载成功，路径：{}", DEFAULT_WATERMARK_IMAGE_PATH);
        hasFFmpeg = ffmpegService.checkFFmpegInstalled();
        DEFAULT_WATERMARK_LOCAL_PATH =  getClasspathResourceToLocalPath(DEFAULT_WATERMARK_IMAGE_PATH);

        // 初始化线程池
        initThreadPool();
    }

    /**
     * 初始化线程池
     */
    private void initThreadPool() {
        executorService = new ThreadPoolExecutor(
                CORE_POOL_SIZE,
                MAX_POOL_SIZE,
                KEEP_ALIVE_TIME,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                new NameThreadFactory("watermark-thread"),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        log.info("水印服务线程池初始化成功 - 核心线程数: {}, 最大线程数: {}, 队列容量: {}", 
                CORE_POOL_SIZE, MAX_POOL_SIZE, QUEUE_CAPACITY);
    }

    /**
     * 删除临时文件
     * @throws Exception
     */
    @Override
    public void destroy() throws Exception {
        // 关闭线程池
        shutdownThreadPool();

        // 删除临时水印文件
        if (DEFAULT_WATERMARK_LOCAL_PATH != null && !DEFAULT_WATERMARK_LOCAL_PATH.isEmpty()) {
            try {
                File tempWatermarkFile = new File(DEFAULT_WATERMARK_LOCAL_PATH);
                if (tempWatermarkFile.exists() && tempWatermarkFile.isFile()) {
                    boolean deleted = tempWatermarkFile.delete();
                    if (deleted) {
                        log.info("临时水印文件已删除：{}", DEFAULT_WATERMARK_LOCAL_PATH);
                    } else {
                        log.warn("临时水印文件删除失败：{}", DEFAULT_WATERMARK_LOCAL_PATH);
                    }
                } else {
                    log.debug("临时水印文件不存在，无需删除：{}", DEFAULT_WATERMARK_LOCAL_PATH);
                }
            } catch (Exception e) {
                log.error("删除临时水印文件时发生异常：{}", DEFAULT_WATERMARK_LOCAL_PATH, e);
            }
        }
    }

    /**
     * 关闭线程池
     */
    private void shutdownThreadPool() {
        if (executorService != null && !executorService.isShutdown()) {
            log.info("开始关闭水印服务线程池...");
            executorService.shutdown();
            try {
                // 等待已提交的任务完成，最多等待30秒
                if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                    log.warn("线程池在30秒内未能正常关闭，强制关闭...");
                    executorService.shutdownNow();
                    // 再等待5秒
                    if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                        log.error("线程池强制关闭后仍有任务未完成");
                    }
                }
                log.info("水印服务线程池已成功关闭");
            } catch (InterruptedException e) {
                log.warn("等待线程池关闭时被中断，强制关闭线程池", e);
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 基于文件系统做一下吧
     * @param path 绝对路径
     * @return
     * @throws Exception
     */
    public String watermarkAppend(String path, boolean useFFmpeg, boolean isScale) throws Exception {
        // 将加水印任务提交到线程池执行
        Future<String> future = executorService.submit(() -> {
            try {
                return executeWatermarkAppend(path, useFFmpeg, isScale);
            } catch (Exception e) {
                log.error("执行加水印任务时发生异常，文件路径: {}", path, e);
                throw new RuntimeException("加水印任务执行失败: " + e.getMessage(), e);
            }
        });

        try {
            // 等待任务完成并返回结果（保持原有同步行为）
            return future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            } else if (cause instanceof Exception) {
                throw (Exception) cause;
            } else {
                throw new Exception("加水印任务执行失败", cause);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("等待加水印任务完成时被中断，文件路径: {}", path, e);
            throw new Exception("加水印任务被中断", e);
        }
    }

    /**
     * 执行加水印的实际逻辑（原有代码逻辑）
     * @param path 绝对路径
     * @return 加水印后的文件路径
     * @throws Exception
     */
    private String executeWatermarkAppend(String path, boolean useFFmpeg, boolean isScale) throws Exception {
        String fileExt = path.substring(path.lastIndexOf(".") + 1);
        String fileName = UUID.randomUUID().toString().replace("-", "") + "." + fileExt;

        // TODO 这里的临时文件需要被安全删除
        if(validVideoType.contains(fileExt)){
            if(hasFFmpeg && useFFmpeg){
                String outputVideoPath = "F:/" + fileName;
                File outputFile = new File(outputVideoPath);
                if (!outputFile.getParentFile().exists()) {
                    outputFile.getParentFile().mkdirs();
                }
                if (outputFile.exists()) {
                    outputFile.delete();
                }
                outputFile.createNewFile();

                // TODO 获得缩放后的水印图片
                // QUESTION 对于使用命令行的方式流程
                // XXX 从轩辕中下载文件到本地临时文件
                // XXX 基于Grabber读取视频获得元数据
                // XXX 基于视频一帧BufferedImage与水印BufferedImage获得缩放后的BufferedImage，并存于临时文件中
                // XXX 创建临时目标文件，基于临时目标文件使用命令行进行加水印
                // XXX 水印完成后，基于输出流，或者文件写到轩辕
                // XXX 最后删除三个临时文件
                // XXX 此时磁盘中出现三个临时文件，内存中最多出现三个BufferedImage

                // QUESTION 对于使用javacv方式
                // XXX 直接获得源文件的输入流
                // XXX 同样基于Grabber读取视频获得元数据
                // XXX 基于视频一帧BufferedImage与水印BufferedImage获得缩放后的BufferedImage
                // XXX 基于缩放后的BufferedImage加水印
                // XXX 加上水印后的视频以内存流/文件输出流形式存在
                // XXX 内存中最多出现三个BufferedImage，磁盘中不出现临时文件
                // XXX 最好以文件流的形式存放加上水印的视频，不然内存消耗太大

                // XXX 基于上面的描述进行代码调整

                // TODO 统一处理临时文件
                // XXX 下面是进行缩放的操作
                if(isScale){
                     Path scaledWatermark = Files.createTempFile("scaled_watermark_", ".png");
                     scaleWatermark(DEFAULT_WATERMARK_IMAGE, new File(path), scaledWatermark.toFile(), WATERMARK_SCALE_RATIO);
                     ffmpegService.watermarkappend(path, outputVideoPath, scaledWatermark.toAbsolutePath().toString(), null);
                }
                // Path scaledWatermark = Files.createTempFile("scaled_watermark_", ".png");
                // scaleWatermark(DEFAULT_WATERMARK_IMAGE, new File(path), scaledWatermark.toFile(), WATERMARK_SCALE_RATIO);
                // ffmpegService.watermarkappend(path, outputVideoPath, scaledWatermark.toAbsolutePath().toString(), null);
                else{
                    ffmpegService.watermarkappend(path, outputVideoPath, DEFAULT_WATERMARK_LOCAL_PATH, null);
                }

                return "F:/" + fileName;
            } else {
                try(InputStream inputStream = new FileInputStream(path);
                    OutputStream outputStream = Files.newOutputStream(Paths.get("F:/", fileName))){
                    addImageWatermark2Video(inputStream, outputStream, fileExt);
                    return "F:/" + fileName;
                }
            }
        } else if(validImageType.contains(fileExt)){
            try(InputStream inputStream = new FileInputStream(path);
                OutputStream outputStream = Files.newOutputStream(Paths.get("F:/", fileName))){
                addImageWatermark2Image(inputStream, outputStream, fileExt);
                return "F:/" + fileName;
            }
        } else{
            log.error("不支持的文件类型");
            throw new RuntimeException("不支持的文件类型");
        }
    }

    /**
     * 辅助方法：将类路径下的资源复制到本地临时路径，返回本地绝对路径
     * @param classpathResource 类路径资源（如 static/default_watermark.png）
     * @return 本地临时文件的绝对路径
     */
    private String getClasspathResourceToLocalPath(String classpathResource) throws Exception {
        InputStream resourceStream = getClass().getClassLoader().getResourceAsStream(classpathResource);
        if (resourceStream == null) {
            log.error("类路径下未找到水印图片：{}", classpathResource);
            throw new FileNotFoundException("类路径下未找到水印图片：" + classpathResource);
        }

        Path tempWatermarkPath = Files.createTempFile("temp_watermark_", ".png");

        Files.copy(resourceStream, tempWatermarkPath, StandardCopyOption.REPLACE_EXISTING);
        resourceStream.close();

        String absolutePath = tempWatermarkPath.toAbsolutePath().toString();
        return absolutePath;
    }

//    /**
//     * 基于轩辕做功能存储
//     * @param originUrl 源文件地址
//     * @return 加了水印的文件地址
//     */
//    public String watermarkAppend(String originUrl) throws Exception {
//        String fileExt = originUrl.substring(originUrl.lastIndexOf(".") + 1);
//        try(InputStream inputStream = HttpClientUtil.getAndGetInputStream(originUrl);
//            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();){
//            if (validVideoType.contains(fileExt)) {
//                addImageWatermark2Image(inputStream, outputStream, fileExt);
//            } else if (validImageType.contains(fileExt)) {
//                addImageWatermark2Video(inputStream, outputStream, fileExt);
//            } else {
//                log.error("不支持的文件类型");
//                throw new RuntimeException("不支持的文件类型");
//            }
//            // TODO 解析originUrl获得key？？？
//            // storageExecutor.putObjectBytes("bucket", "key", outputStream.toByteArray());
//            // return storageExecutor.generateNWUrl("key");
//        }
//    }

    /**
     * 缩放水印图片，直接到文件中
     */
    private void scaleWatermark(BufferedImage originalWatermark, File file, File targetFile, Float ratio) throws IOException{
        int targetWatermarkWidth = originalWatermark.getWidth();
        int targetWatermarkHeight = originalWatermark.getHeight();
        try(FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(file);
            OutputStream bos = Files.newOutputStream(targetFile.toPath());){
            grabber.start();
            int sourceWidth = grabber.getImageWidth();
            targetWatermarkWidth = (int) (sourceWidth * ratio);
            double tagetRatio = (double) originalWatermark.getHeight() / originalWatermark.getWidth();
            targetWatermarkHeight = (int) (targetWatermarkWidth * tagetRatio);
            Thumbnails.of(originalWatermark)
                    .size(targetWatermarkWidth, targetWatermarkHeight)
                    .keepAspectRatio(true)
                    .outputQuality(1.0f)
                    .outputFormat("png")
                    .toOutputStream(bos);
        }
    }

    /**
     * 参数为输入输出流，其关闭操作由上层进行处理
     * @param fileExt 文件后缀名，用于写入输出流
     */
    public void addImageWatermark2Image(InputStream inputStream, OutputStream outputStream, String fileExt) throws Exception {
        log.info("开始执行加水印");
        BufferedImage sourceImage = ImageIO.read(inputStream);
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
        addImageWatermark(sourceImage, outputStream, watermarkImage, fileExt);
    }

    /**
     * 为视频添加水印
     */
    public void addImageWatermark2Video(InputStream inputStream, OutputStream outputStream, String fileExt) throws Exception {
        log.info("开始执行加水印");
        File tempOutputVideo = File.createTempFile("video_watermark_", "." + fileExt);
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
            log.debug("视频元数据读取完成 - 总帧数：{}，视频帧数量：{}", totalFrames, videoFrames);

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
            Frame frame = null;

            int count = 0;
            boolean isFirstFram = true;
            while((frame = grabber.grab()) != null){
                count++;
                if (frame.image != null) {
                    // 帧转图片 → 绘制水印 → 转回帧
                    BufferedImage image = converter.getBufferedImage(frame);
                    if (isFirstFram) {
                        watermarkImage = scaleWatermarkImage(DEFAULT_WATERMARK_IMAGE, image, WATERMARK_SCALE_RATIO);
                        if(watermarkImage == null){
                            log.error("水印图片损坏，无法读取或缩放");
                            throw new RuntimeException("水印图片损坏，无法读取或缩放");
                        }
                        isFirstFram = false;
                    }
                    if(count < totalFrames){
                        drawImageWatermark(image, watermarkImage);
                        Frame watermarkedFrame = converter.convert(image);
                        recorder.record(watermarkedFrame);
                    } else{
                        recorder.record(frame);
                    }
                } else if (frame.samples != null) {
                    // 音频帧直接写入
                    recorder.record(frame);
                }
            }
            recorder.stop();
            grabber.stop();
            try(InputStream is = Files.newInputStream(tempOutputVideo.toPath())){
                IOUtils.copyLarge(is, outputStream);
                outputStream.flush();
            }
        } finally {
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
            Files.deleteIfExists(tempOutputVideo.toPath());
        }
    }

    /**
     * 缩放水印图片（传入缓存的BufferedImage，默认水印专用）
     */
    private BufferedImage scaleWatermarkImage(BufferedImage originalWatermark, BufferedImage sourceImage, Float ratio) throws IOException {
        // 计算目标尺寸：源图片的10%（等比例）
        int sourceWidth = sourceImage.getWidth();
        int targetWatermarkWidth = (int) (sourceWidth * ratio);
        double tagetRatio = (double) originalWatermark.getHeight() / originalWatermark.getWidth();
        int targetWatermarkHeight = (int) (targetWatermarkWidth * tagetRatio);

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
     * 完成添加水印，并写入输出流
     * @param sourceImage 源图Image
     * @param outputStream 输出流
     * @param watermarkImage 水印Image
     * @param fileExt 文件后缀，用于写入输出流
     */
    public void addImageWatermark(BufferedImage sourceImage,
                                  OutputStream outputStream,
                                  BufferedImage watermarkImage,
                                  String fileExt) throws IOException {
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
        g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, DEFAULT_ALPHA));

        // 计算水印位置
        Point watermarkPos = calculateWatermarkPosition(sourceImage, watermarkImage, WATERMARK_POSITION);

        // 绘制水印图片
        g2d.drawImage(watermarkImage, watermarkPos.x, watermarkPos.y, null);

        // 释放资源
        g2d.dispose();

        // 写入输出流
        ImageIO.write(targetImage, fileExt, outputStream);
        outputStream.flush();
    }

    /**
     * 基于视频帧添加水印
     * @param sourceImage 视频对应帧的Image
     * @param watermarkImage 水印Image
     */
    public void drawImageWatermark(BufferedImage sourceImage, BufferedImage watermarkImage){
        Graphics2D g2d = sourceImage.createGraphics();

        // 开启抗锯齿
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        // 绘制原始图片
        g2d.drawImage(sourceImage, 0, 0, null);

        // 设置水印透明度
        g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, DEFAULT_ALPHA));

        // 计算水印位置
        Point watermarkPos = calculateWatermarkPosition(sourceImage, watermarkImage, WATERMARK_POSITION);

        // 绘制水印图片
        g2d.drawImage(watermarkImage, watermarkPos.x, watermarkPos.y, null);

        // 释放资源
        g2d.dispose();
    }

    /**
     * 计算水印位置
     * 提供多种位置信息
     */
    private Point calculateWatermarkPosition(BufferedImage sourceImage, BufferedImage watermarkImage, String position) {
        int sourceWidth = sourceImage.getWidth();
        int sourceHeight = sourceImage.getHeight();
        int watermarkWidth = watermarkImage.getWidth();
        int watermarkHeight = watermarkImage.getHeight();

        int x = 0, y = 0;
        int marginX = Math.min((int) Math.ceil(watermarkWidth * 0.01f), 20);
        int marginY = Math.min((int) Math.ceil(watermarkHeight * 0.01f), 20);
        String pos = position == null ? "RIGHT_BOTTOM" : position.toUpperCase();

        switch (pos) {
            case "LEFT_TOP":
                x = marginX;
                y = marginY;
                break;
            case "LEFT_BOTTOM":
                x = marginX;
                y = Math.max(marginY, sourceHeight - watermarkHeight - marginY);
                break;
            case "RIGHT_TOP":
                x = Math.max(marginX, sourceWidth - watermarkWidth - marginX);
                y = marginY;
                break;
            case "CENTER":
                x = (sourceWidth - watermarkWidth) / 2;
                y = (sourceHeight - watermarkHeight) / 2;
                break;
            case "RIGHT_BOTTOM":
            default:
                x = Math.max(marginX, sourceWidth - watermarkWidth - marginX);
                y = Math.max(marginY, sourceHeight - watermarkHeight - marginY);
                break;
        }
        return new Point(x, y);
    }
}
