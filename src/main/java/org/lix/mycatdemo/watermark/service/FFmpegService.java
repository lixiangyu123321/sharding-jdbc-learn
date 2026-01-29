package org.lix.mycatdemo.watermark.service;

import com.alibaba.nacos.common.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;

@Slf4j
@Service
public class FFmpegService {

    /**
     * ffmpeg对应的程序名
     */
    private static final String FFMPEG_PATH = "ffmpeg";

    /**
     * 当前使用的系统
     */
    private static final boolean isWindows = System.getProperty("os.name").toLowerCase().contains("windows");

    /**
     * 添加图片水印
     * @param inputVideoPath 源视频path
     * @param outputVideoPath 输出视频path
     * @param watermarkPath 水印path
     * @param position 水印位置，默认为左上角
     */
    public void watermarkappend(String inputVideoPath, String outputVideoPath, String watermarkPath, String position) {
        try{
            addImageWatermark(inputVideoPath, outputVideoPath, watermarkPath, position);
        } catch( InterruptedException e){
            Thread.currentThread().interrupt();
            log.warn("添加水印被打断！", e);
        } catch (Exception e){
            try{
                Thread.sleep(2000);
            } catch (InterruptedException e1){
                Thread.currentThread().interrupt();
                log.info("sleep interrupted", e1);
            }

            try{
                addImageWatermark(inputVideoPath, outputVideoPath, watermarkPath, position);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                log.error("重试添加水印被打断！", ex);
            } catch (Exception ex){
                log.error("重试添加水印失败！", ex);
            }
        }
    }

    // TODO 文本水印功能

    /**
     * 添加图片水印
     */
    private void addImageWatermark(String inputVideoPath, String outputVideoPath,
                                         String watermarkImagePath, String position)
            throws IOException, InterruptedException {
        ensureFileExists(inputVideoPath, "输入视频文件");
        ensureFileExists(watermarkImagePath, "水印图片文件");

        String overlayExpr = resolveImagePosition(position);

        // XXX 默认图片大小为原始图片分辨率
        String[] cmd = {
                FFMPEG_PATH,
                "-i", inputVideoPath,
                "-i", watermarkImagePath,
                "-filter_complex", "overlay=" + overlayExpr,
                "-c:v", "libx264",
                "-c:a", "copy",
                "-y",
                outputVideoPath
        };
        executeFFmpegCommand(cmd);
    }

    /**
     * 文字水印添加方法
     */
    private void addTextWatermark(String inputVideoPath, String outputVideoPath,
                                        String text, String position, String fontPath)
            throws IOException, InterruptedException {
        // 基础参数校验
        ensureFileExists(inputVideoPath, "输入视频文件");
        ensureFileExists(fontPath, "字体文件");

        String escapedFontPath = escapeDrawtextPath(fontPath);
        String escapedText = escapeDrawtextText(text);
        String drawtextPosition = resolveTextPosition(position);

        String filter = String.format(
                // XXX 默认字体大小为24
                "drawtext=fontfile='%s':text='%s':fontsize=24:fontcolor=white@0.8:%s",
                escapedFontPath,
                escapedText,
                drawtextPosition
        );

        String[] cmd = {
                FFMPEG_PATH,
                "-i", inputVideoPath,
                "-vf", filter,
                "-c:v", "libx264",
                "-c:a", "copy",
                "-y",
                outputVideoPath
        };

        executeFFmpegCommand(cmd);
    }

    /**
     * drawtext 中的路径转义：
     * - 统一使用 '/' 分隔符（FFmpeg 在 Windows 上也支持 '/'）
     * - Windows 路径：将盘符冒号 'C:' 转义为 'C\:'，避免与 drawtext 参数分隔符 ':' 冲突
     * - Linux 路径：不需要转义冒号（Linux 路径通常不包含盘符冒号）
     */
    private String escapeDrawtextPath(String path) {
        if (path == null) {
            return "";
        }
        String normalizedPath = path.replace("\\", "/");

        if (isWindows && normalizedPath.matches("^[A-Za-z]:/.*")) {
            normalizedPath = normalizedPath.replaceFirst("^([A-Za-z]):", "$1\\\\:");
        }

        return normalizedPath;
    }

    /**
     * 转义
     */
    private String escapeDrawtextText(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replace("\\", "\\\\")
                .replace(":", "\\:")
                .replace("'", "\\'");
    }

    /**
     * 执行ffmpeg命令
     * @param cmd
     * @throws IOException
     * @throws InterruptedException
     */
    private void executeFFmpegCommand(String[] cmd) throws IOException, InterruptedException {
        // 先检查 ffmpeg 是否已安装且可用
        if(!checkFFmpegInstalled()){
            log.info("ffmpeg is not installed or not available.");
            return;
        }

        // 打印执行的命令，方便调试
        log.info("执行的 FFmpeg 命令：{}", String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        // 关键修复：在后台线程中读取输出流，避免缓冲区满导致进程阻塞
        StringBuilder outputBuffer = new StringBuilder();
        Thread outputReader = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    outputBuffer.append(line).append("\n");
//                    // 可选：打印进度信息（FFmpeg 会输出处理进度）
//                    if (line.contains("time=") || line.contains("frame=")) {
//                        log.debug("FFmpeg 进度: {}", line);
//                    }
                }
            } catch (IOException e) {
                log.warn("读取 FFmpeg 输出流失败", e);
            }
        });
        outputReader.setDaemon(true);
        outputReader.start();

        try {
            // 关键修复：使用超时控制，避免无限等待（默认 10 分钟超时）
            boolean finished = process.waitFor(10, java.util.concurrent.TimeUnit.MINUTES);
            
            if (!finished) {
                // 超时：强制终止 FFmpeg 进程
                log.error("FFmpeg 命令执行超时（10分钟），强制终止进程");
                process.destroyForcibly();
                throw new RuntimeException("FFmpeg 命令执行超时，已强制终止");
            }

            int exitCode = process.exitValue();
            if (exitCode == 0) {
                log.info("水印添加成功！输出文件：{}", cmd[cmd.length - 1]);
            } else {
                String errorOutput = outputBuffer.length() > 0
                    ? outputBuffer.toString()
                    : "（无输出信息）";
                log.error("FFmpeg 命令执行失败，退出码：{}，输出：{}", exitCode, errorOutput);
                throw new RuntimeException("FFmpeg 命令执行失败，退出码：" + exitCode +
                    "，错误信息：" + errorOutput);
            }
        } catch (InterruptedException e) {
            // 关键修复：客户端断开时，确保清理 FFmpeg 进程
            log.warn("FFmpeg 命令执行被中断，正在清理进程...");
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            // 确保输出读取线程结束
            try {
                outputReader.join(1000); // 等待最多1秒
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 检查本机是否已安装 ffmpeg（或环境变量是否配置正确）。
     * 做法：执行一次 `ffmpeg -version`，根据退出码/异常判断。
     */
    public boolean checkFFmpegInstalled() {
        Process process = null;
        try {
            process = new ProcessBuilder(FFMPEG_PATH, "-version")
                    .redirectErrorStream(true)
                    .start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                return false;
//                throw new IllegalStateException("未检测到可用的 ffmpeg，可执行文件名称为: " + FFMPEG_PATH +
//                        "。请确认已安装 ffmpeg 并配置到 PATH 环境变量，或修改 FFMPEG_PATH 为绝对路径。");
            }
            return true;
        } catch (Exception e) {
            // 检查 ffmpeg 是否安装时出错，请确认本机已安装 ffmpeg 并已加入 PATH。
            return false;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }


    /**
     * 校验文件是否存在，避免 ffmpeg 因路径错误返回 \"Invalid argument (-22)\" 这类模糊错误
     */
    private static void ensureFileExists(String path, String desc) {
        if (StringUtils.isBlank(path)) {
            throw new IllegalArgumentException(desc + " 路径为空");
        }
        File file = new File(path);
        if (!file.exists()) {
            throw new IllegalArgumentException(desc + " 不存在，路径为: " + path);
        }
        if (!file.isFile()) {
            throw new IllegalArgumentException(desc + " 不是文件，路径为: " + path);
        }
    }

    /**
     * 解析图片水印位置关键字为 overlay 表达式
     * - LEFT_TOP:       左上角
     * - RIGHT_TOP:      右上角
     * - LEFT_BOTTOM:    左下角
     * - RIGHT_BOTTOM:   右下角
     * - CENTER:         居中
     */
    private String resolveImagePosition(String pos) {
        String p = (StringUtils.isBlank(pos))
                ? "LEFT_TOP"
                : pos.trim().toUpperCase();

        switch (p) {
            case "RIGHT_TOP":
                return "main_w-overlay_w-10:10";
            case "LEFT_BOTTOM":
                return "10:main_h-overlay_h-10";
            case "RIGHT_BOTTOM":
                return "main_w-overlay_w-10:main_h-overlay_h-10";
            case "CENTER":
                return "(main_w-overlay_w)/2:(main_h-overlay_h)/2";
            case "LEFT_TOP":
            default:
                return "10:10";
        }
    }

    /**
     * 解析文字水印位置关键字为 drawtext 的 x,y 表达式
     * - LEFT_TOP:       左上角
     * - RIGHT_TOP:      右上角
     * - LEFT_BOTTOM:    左下角
     * - RIGHT_BOTTOM:   右下角（默认）
     * - CENTER:         居中
     */
    private String resolveTextPosition(String pos) {
        String p = (StringUtils.isBlank(pos))
                ? "RIGHT_BOTTOM"
                : pos.trim().toUpperCase();

        switch (p) {
            case "LEFT_TOP":
                return "x=10:y=10";
            case "RIGHT_TOP":
                return "x=w-tw-10:y=10";
            case "LEFT_BOTTOM":
                return "x=10:y=h-th-10";
            case "CENTER":
                return "x=(w-tw)/2:y=(h-th)/2";
            case "RIGHT_BOTTOM":
            default:
                return "x=w-tw-10:y=h-th-10";
        }
    }

}
