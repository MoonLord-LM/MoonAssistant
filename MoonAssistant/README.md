# MoonAssistant（屏幕监控 / 录屏直播）

Spring Boot 3 + JDK 17 桌面监控网页应用：后台定时截取屏幕、采样 CPU/内存/磁盘，并用
`ffmpeg`（gdigrab）录制屏幕，以 **HLS 分段直播**形式实时回看，同时把分片追加为持久化录像
`video.ts`。前端为 Thymeleaf + Bootstrap + Chart.js / video.js / HLS.js，启动自动打开
`http://localhost:8080`。

## 构建 / 运行

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.11.9-hotspot"
mvn -DskipTests package
java -jar target\*.jar
```

依赖外部 **ffmpeg.exe**（录屏/切片，路径与参数见 `service/ScreenshotVideoRecorder`），需在 PATH
或与 jar 同目录。

## 代码结构

- `PerformanceRecorder`：每 3s 采样 CPU/内存/磁盘 → `PerformanceVO`（Chart.js 数据）。
- `ScreenshotRecorder`：每 2s 用 AWT Robot 抓屏（内存持有最新帧，供页面缩放预览）。
- `ScreenshotVideoRecorder`：启动即用 FFmpeg 后台录屏，HLS 分片 + 追加 `video.ts` 供回放。
- `APIController` / `PageController`：REST 与页面路由。

## 已知问题（维护建议）

- 录屏分片缓存无上限（`video.ts` 持续追加、`fileCache` 只删磁盘不释放内存、后台读取线程无退出
  标志自旋），长时间运行资源会持续增长，需重构录制/清理生命周期。
- `ffmpeg` 依赖进程工作目录与外部 exe，部署环境变化易失效。

## 使用的开源软件

[`Spring Boot`](https://spring.io/projects/spring-boot) · [`Thymeleaf`](https://www.thymeleaf.org/doc/tutorials/3.1/usingthymeleaf.html) · [`Bootstrap`](https://getbootstrap.com/docs/5.3/) · [`Chart.js`](https://www.chartjs.org/docs/latest/) · [`FFmpeg`](https://github.com/FFmpeg/FFmpeg) · [`video.js`](https://github.com/videojs/video.js) · [`HLS.js`](https://github.com/video-dev/hls.js)
