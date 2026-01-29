package org.lix.mycatdemo.watermark.service;

import com.alibaba.nacos.common.utils.StringUtils;
import javafx.util.Pair;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@Slf4j
@Service
public class FFmpegService {

    /**
     * ffmpeg对应的程序名
     */
    private static final String FFMPEG_PATH = "ffmpeg";
    
    /**
     * ffprobe对应的程序名
     */
    private static final String FFPROBE_PATH = "ffprobe";

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

    /**
     * 获取视频文件的宽度和高度
     * @param inputVideoPath 视频文件路径
     * @return Pair<Integer, Integer> 包含宽度和高度的Pair对象，key为宽度，value为高度
     * @throws RuntimeException 如果文件不存在、ffmpeg不可用或无法解析视频信息时抛出异常
     */
    public Pair<Integer, Integer> getWidthAndHeight(String inputVideoPath) {
        // 校验文件是否存在
        ensureFileExists(inputVideoPath, "视频文件");
        log.debug("视频文件存在");
        
        // 检查 ffmpeg 是否已安装
        if (!checkFFmpegInstalled()) {
            log.error("ffmpeg 未安装或不可用，无法获取视频尺寸");
            throw new RuntimeException("ffmpeg 未安装或不可用");
        }
        
        // 优先尝试使用 ffprobe（更专业，输出更简洁）
        Pair<Integer, Integer> dimension = tryGetDimensionWithFFprobe(inputVideoPath);
        if (dimension != null) {
            return dimension;
        }
        
        // 如果 ffprobe 不可用，使用 ffmpeg -i 解析输出
        return getDimensionWithFFmpeg(inputVideoPath);
    }
    
    /**
     * 使用 ffprobe 获取视频尺寸（推荐方式）
     */
    private Pair<Integer, Integer> tryGetDimensionWithFFprobe(String inputVideoPath) {
        try {
            log.info("尝试使用 ffprobe 获取视频宽度和高度");
            String[] cmd = {
                    FFPROBE_PATH,
                    "-v", "error",
                    "-select_streams", "v:0",
                    "-show_entries", "stream=width,height",
                    "-of", "csv=s=x:p=0",
                    inputVideoPath
            };
            
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            log.debug("ffprobe 命令执行成功");

            StringBuilder output = new StringBuilder();
            StringBuilder errorOutput = new StringBuilder();
            
            // 读取标准输出
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                }
            }
            
            // 读取错误输出（如果有）
            try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = errorReader.readLine()) != null) {
                    errorOutput.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();
            log.debug("ffprobe 命令行返回：{}", exitCode);
            
            if (exitCode == 0) {
                String result = output.toString().trim();
                log.debug("ffprobe 输出: {}", result);
                
                // ffprobe 输出格式: widthxheight (例如: 1920x1080)
                if (result.contains("x")) {
                    String[] parts = result.split("x");
                    if (parts.length == 2) {
                        int width = Integer.parseInt(parts[0].trim());
                        int height = Integer.parseInt(parts[1].trim());
                        log.info("成功获取视频尺寸 - 文件: {}, 宽度: {}, 高度: {}", inputVideoPath, width, height);
                        return new Pair<>(width, height);
                    }
                }
            } else {
                log.warn("ffprobe 命令执行失败，退出码: {}, 错误输出: {}", exitCode, errorOutput.toString());
            }
        } catch (Exception e) {
            // ffprobe 不可用或执行失败，返回 null，后续使用 ffmpeg 方式
            log.debug("ffprobe 不可用或执行失败，将使用 ffmpeg 方式获取视频尺寸: {}", e.getMessage());
        }
        return null;
    }
    
    /**
     * 使用 ffmpeg -i 命令获取视频尺寸（备用方式）
     */
    private Pair<Integer, Integer> getDimensionWithFFmpeg(String inputVideoPath) {
        try {
            log.info("使用 ffmpeg -i 命令获取视频宽度和高度");
            String[] cmd = {
                    FFMPEG_PATH,
                    "-i", inputVideoPath
            };
            
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            log.debug("ffmpeg 命令执行成功");

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            // ffmpeg -i 命令会返回非零退出码（这是正常的，因为它只是显示信息）
            process.waitFor();
            
            // 解析输出，查找视频流信息
            // 典型输出格式: "Stream #0:0: Video: h264, yuv420p, 1920x1080 [SAR 1:1 DAR 16:9], 25 fps, 25 tbr, 1200k tbn, 50 tbc"
            String outputStr = output.toString();
            log.debug("ffmpeg 输出: {}", outputStr);
            
            // 使用正则表达式匹配分辨率: 数字x数字
            // 匹配格式: 至少2位数字 x 至少2位数字（例如: 1920x1080, 1280x720）
            Pattern pattern = Pattern.compile("(\\d{2,5})x(\\d{2,5})");
            Matcher matcher = pattern.matcher(outputStr);
            
            if (matcher.find()) {
                int width = Integer.parseInt(matcher.group(1));
                int height = Integer.parseInt(matcher.group(2));
                log.info("成功获取视频尺寸 - 文件: {}, 宽度: {}, 高度: {}", inputVideoPath, width, height);
                return new Pair<>(width, height);
            }
            
            // 如果正则匹配失败，抛出异常
            log.error("无法从视频文件中解析出宽度和高度信息。输出: {}", outputStr);
            throw new RuntimeException("无法解析视频尺寸信息，请确认视频文件格式正确");
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("获取视频尺寸时被中断，文件: {}", inputVideoPath, e);
            throw new RuntimeException("获取视频尺寸被中断", e);
        } catch (IOException e) {
            log.error("获取视频尺寸时发生IO异常，文件: {}", inputVideoPath, e);
            throw new RuntimeException("获取视频尺寸失败: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            // 重新抛出 RuntimeException
            throw e;
        } catch (Exception e) {
            log.error("获取视频尺寸时发生未知异常，文件: {}", inputVideoPath, e);
            throw new RuntimeException("获取视频尺寸失败: " + e.getMessage(), e);
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
