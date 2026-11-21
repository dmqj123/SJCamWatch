# SJCamWatch

一个为 **OPPO Watch X3 mini**（Android 11 / API 30，466×466 圆形屏幕）重新开发的 SJCAM 运动相机遥控 App。

原版 SJCAM X App 只面向手机。本项目通过逆向 `SJCAMX_1006397.apk` 还原出相机的 HTTP/CGI 通信协议，然后用**纯原生 Android**（Java + Framework API，无 AndroidX、无 Kotlin、无第三方依赖）重写，专门适配手表的小尺寸圆屏。

> ⚠️ 本项目为个人逆向学习作品，与 SJCAM 官方无任何关联。相机协议的权利归原厂商所有。请仅在你自己的设备上使用。

## 功能

- **连接检测**：进入 App 自动探测相机热点（当前 Wi-Fi 网关、`192.168.1.254`、`192.168.8.1`），显示固件版本与在线状态，3.5 秒心跳保活。
- **拍摄**：照片 / 视频切换，可开关实时预览；拍照、录像清晰度分别可调。
- **历史**：按时间排序列出相机内的照片和视频，缩略图预览、全屏查看、下载到手表。
- **圆屏适配**：所有控件收拢到内接正方形安全区，避免越界；支持表冠滚动与右滑返回。
- **功耗优化**：实时预览使用低码率子码流，预览期间暂停 HTTP 心跳并在关闭时释放。

## 逆向得到的相机接口

请求统一为 HTTP GET（相机是一个开放 Wi-Fi 热点，无鉴权）：

```
http://<host>/?custom=1&cmd=<CMD>[&par=<PAR>][&str=<STR>]
```

响应是极简 XML：

```xml
<Function><Cmd>3015</Cmd><Status>0</Status><Value>...</Value></Function>
```

| CMD  | 含义                   | 关键参数                                  |
| ---- | ---------------------- | ----------------------------------------- |
| 1001 | 拍照                   | 需先切到拍照模式 `3001&par=0`            |
| 1002 | 照片清晰度             | `par` = 档位索引                          |
| 2001 | 录像开 / 关            | `par=1` 开始 / `par=0` 停止，需录像模式   |
| 2002 | 录像清晰度 / 码率档位  | `par` = 档位索引                          |
| 2015 | 实时预览开关           | `par=1` 开 / `par=0` 关                   |
| 3001 | 模式切换               | `par` = 0 拍照 / 1 录像 / 2 回放          |
| 3012 | 读取固件版本           | `String` 标签返回版本号                   |
| 3014 | 读取当前全部设置       |                                           |
| 3015 | 文件列表               | 返回 `<FPATH>` 文件树，如 `A:\CARDV\...` |
| 3016 | 心跳 / 保活            | 每 3~5 秒一次，否则相机断开 Wi-Fi         |
| 3021 | 保存设置到闪存         | 修改清晰度后调用                          |
| 3031 | 读取菜单选项表         | `str=all`，返回每个 CMD 的 `<MenuList>`   |
| 4001 | 缩略图                 | 追加在文件 URL 后，需先进入回放模式       |
| 4002 | 预览图                 | 同上                                      |
| 4003 | 删除文件               |                                           |

协议来自 APK 中的 `org/jght/sjcam/x/camera/ly/LyCmd`（命令常量）、`LyCmdApi`（`cmd`/`par`/`str` 查询键）与 `NovaCameraContent`（网关地址）。转发到本项目对应 `app/src/main/java/com/sjcam/watch/camera/CameraCmd.java`。

**几个必须遵守的坑**：

- 相机 HTTP 服务是**单线程**的，所有请求（含缩略图、下载）必须串行，本项目用 `ReentrantLock` 保证。
- `3015` 返回的设备路径 `A:\CARDV\MOVIE\x.TS` 要转成 `http://<host>/CARDV/MOVIE/x.TS`。
- `4001/4002` 只有在**回放模式**（`3001&par=2`）下才返回图片，否则会返回整个文件体。
- 实时预览为 RTSP：`rtsp://<host>/novatek/sub`（子码流）/ `/novatek/main`（主码流）。

## 项目结构

```
app/src/main/java/com/sjcam/watch/
├── camera/          # 协议命令、HTTP 客户端、会话/心跳、文件仓库、清晰度表、预览、视频探测
├── ui/              # 圆屏安全区布局、表冠滚动、缩略图加载
├── ConnectActivity  # 入口：检测连接
├── HomeActivity     # 拍摄 / 历史入口
├── CaptureActivity  # 拍照 / 录像 / 预览开关
├── HistoryActivity  # 时间排序的文件列表 + 下载
├── PreviewActivity  # 全屏预览
└── QualityActivity  # 拍照 / 录像清晰度选择
```

## 编译

**仅在 WSL Ubuntu 内编译**，不使用 Windows 工具链。环境：JDK 17、Android SDK 34、AGP 8.5.1、Gradle 8.7。

```bash
cd /home/dmqj/AndroidStudioProjects/SJCamWatch
./build.sh
# 或直接： ./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。

仓库通过阿里云镜像解析依赖（Maven Central 在部分网络不可达）；`local.properties` 中的 SDK 路径需按本机修改，已加入 `.gitignore` 不会提交。

## 设备适配说明

OPPO Watch X3 mini 运行 ColorOS Watch（Android 11），屏幕 466×466 圆形。本项目不使用任何 AndroidX 依赖；所有页面继承 `BaseRoundActivity`，内容置于 `RoundSafeLayout` 的内接正方形安全区内，表冠事件经 `CrownScrollView` 拦截（`AXIS_VSCROLL` / `AXIS_SCROLL`），并保留 DPAD / 音量键兜底。

## 许可证

见 [LICENSE](LICENSE)（MIT）。
