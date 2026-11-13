# LegacyTV Device Probe

面向 Android 4.4/API 19 老电视的硬件诊断工具，重点用于海信电视，也可用于其他 Android TV/盒子。

## 能力

- 原生 Android Java，`minSdkVersion 19`。
- 读取 Android、Build、CPU ABI、`/proc/cpuinfo`、内存、显示和 OpenGL ES 声明信息。
- 使用 `MediaCodecList` 枚举编码器和解码器。
- 使用应用内置的本地短片段运行 H.264 Baseline/Main/High、声明支持时的 HEVC、AAC、MP3 测试。
- 测试结果区分“系统声明能力”和“实际运行结果”。视频画面检查是启发式结果，无法取得 Surface 时会标记为 `not_run`。
- 局域网 HTTP 服务监听 `0.0.0.0:10382`。启动失败只写入日志，应用仍可打开。
- APK 上传到应用私有临时目录，可通过系统安装器和卸载确认界面操作。
- 提供调试用远程 Shell：命令在应用自身 UID 下通过 `/system/bin/sh -c` 执行，不会自动获得 Root。

## 启动和端口

启动应用后，电视界面会显示本机 IPv4 地址和服务地址。电脑与电视在同一局域网时访问：

```sh
TV=192.168.1.50
curl "http://$TV:10382/api/status"
curl "http://$TV:10382/api/device"
curl "http://$TV:10382/api/codecs"
curl "http://$TV:10382/api/logs?limit=100"
```

服务没有账号认证，适合隔离的家庭局域网调试环境。

## 编解码测试

```sh
curl -X POST "http://$TV:10382/api/tests/run" \
  -H 'Content-Type: application/json' \
  -d '{"tests":["h264_baseline","h264_main","h264_high","hevc","aac","mp3"]}'

curl "http://$TV:10382/api/tests"
```

`POST /api/tests/run` 返回 `runId`，测试在后台串行运行。结果包含：

- 创建、configure、start 是否成功
- 首帧耗时、测试持续时间和输出帧数
- 实际分辨率、估算掉帧数和解码错误
- codec 名称及硬件/软件推断
- 黑屏、花屏启发式判断

API 19 没有 `VideoCapabilities` 的分辨率/帧率范围接口，因此 `/api/codecs` 会明确返回范围不可用，运行矩阵结果以 `/api/tests` 为准。

## APK 管理

```sh
curl -F "file=@Demo.apk" "http://$TV:10382/api/apps/upload"

curl -X POST "http://$TV:10382/api/apps/install" \
  -H 'Content-Type: application/json' \
  -d '{"fileName":"Demo.apk"}'

curl -X POST "http://$TV:10382/api/apps/uninstall" \
  -H 'Content-Type: application/json' \
  -d '{"packageName":"com.example.app"}'
```

上传只接受 `.apk`，单文件上限 64 MiB，最多保留 10 个暂存文件。安装和卸载仍由 Android 系统界面完成；诊断工具自身会拒绝卸载请求。

## 远程 Shell

```sh
curl -X POST "http://$TV:10382/api/shell/exec" \
  -H 'Content-Type: application/json' \
  -d '{"command":"getprop; cat /proc/cpuinfo","timeoutMs":5000}'
```

命令在应用 UID 下执行，输出上限为 stdout/stderr 各 1 MiB，单次超时上限 30 秒。接口不提供 Root 提权；设备上的 `pm install`、系统属性写入等操作仍受 Android UID 权限限制。

## GitHub Actions 构建

Actions 使用 JDK 17 执行：

```sh
./gradlew :app:assembleDebug :app:assembleRelease
```

每次 push、Pull Request 或手动触发都会上传 debug 和 release APK。项目没有 native `.so`，因此 universal、`armeabi-v7a` 和 `arm64-v8a` 产物功能相同；ABI split 仅用于交付时明确标识兼容架构。debug APK 使用 Gradle 默认 debug 签名，可直接安装；release APK 不包含项目私钥。

## 结果边界

应用只能通过 Android 公共 API、只读系统属性、CPU 信息、MediaCodec 行为和 Surface 结果间接判断硬件。未知厂商 MCU 或无法确认的 SoC 会报告为“系统无法确定”，不会猜测具体芯片型号。仓库构建成功不等于某一台真实海信电视已经通过运行验收。
