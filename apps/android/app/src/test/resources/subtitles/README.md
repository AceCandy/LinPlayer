# ASS 轨道合并回归样本

`embedded.ass` 为自制字幕，`embedded.mkv` 仅包含该 ASS 轨，无音视频和真实片源信息。
用于验证 Matroska 解封装、解析器原始 ID 与合并后所选 ID 的接线，不用于解码/像素验收。

生成命令（FFmpeg 8.1.1）：

```sh
ffmpeg -fflags +bitexact -i embedded.ass -map 0:s -c:s copy -flags +bitexact -fflags +bitexact embedded.mkv
```
