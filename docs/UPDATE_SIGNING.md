# 更新签名

更新清单使用项目专用 Ed25519 密钥。公钥固定在 `core/updatesign/public-key.txt`;私钥种子只保存在本地 `.local/update-signing.seed`,目录权限为 0700、文件为 0600,并已忽略。私钥不编进客户端,不进入 Git、日志或命令行参数。

## 发布配置

在发布仓库 Actions Secrets 中设置 `LP_UPDATE_SIGNING_SEED`,值为本地种子文件的 Base64 文本。不要把内容粘到代码、终端命令、issue 或提交说明。2026-10-03 已通过标准输入配置发布仓库的 `LP_UPDATE_SIGNING_SEED`,并通过 Secrets 元数据接口确认存在;没有读取或输出线上值。

- 预发布生成 `SHA256SUMS.txt` 与 `SHA256SUMS.txt.sig`,签名绑定预发布标签。
- 提升稳定版之前先验证预发布签名、检查平台状态和全部包的哈希;然后生成 `SHA256SUMS` 与 `SHA256SUMS.sig`,重新绑定稳定标签签名。
- 签名私钥缺失、格式错误或与项目固定公钥不匹配时,签名步骤失败,不发布未签名更新。
- 客户端先验证清单签名,再校验安装包内容;缓存也要通过签名及哈希验证,安装前再次校验内容。

签名输入为 UTF-8 字节 `LinPlayer update v1`、换行、完整发布标签、换行、校验清单原始字节。签名文件为 64 字节 Ed25519 签名的 Base64 文本。修改标签、清单字节或资产内容均不能沿用原签名;预发布签名不能用于稳定版。

## 本地签名和验证

激活项目工具链后,在仓库根运行:

```bash
source scripts/env.sh
cd core
go run ./cmd/signupdate -seed-file ../.local/update-signing.seed v2.0.0-dev ../build/pack/SHA256SUMS.txt
go run ./cmd/signupdate -verify v2.0.0-dev ../build/pack/SHA256SUMS.txt
```

客户端更新来源已同步到当前项目发布仓库。旧的未签名发布不再支持应用内自动安装,可手动安装经过核验的发行包。首次从旧客户端迁移到本项目签名身份也应手动安装新客户端,避免把原发布方与新的信任身份混同。

## 保管和更换

本次已在仓库外建立本机副本并逐字节核对,目录权限 0700、文件权限 0600。这只是同机冗余,不能替代离线或独立存储备份。请把私钥另存到可靠的离线或加密备份,丢失会导致无法继续为这批客户端签名。公钥可以公开,私钥必须保密。更换密钥需要先让客户端可信地接受新公钥;本次不提供从配置或网络动态替换信任公钥的入口,不能直接重新生成并覆盖现有文件。

签名能证明清单来自项目密钥持有者,不替代构建审查、仓库权限管理、Android APK 签名或 Windows 系统代码签名。2026-10-03 的云端独立签名检查已通过:使用实际 Secret 签名、固定公钥验签、篡改拒绝。未进行发布及真实设备安装验收。


## 独立二开 Android 身份

本项目 Android 安装包使用独立 applicationId `io.github.acecandy.linplayer`,与原版共存;Kotlin namespace 保留以兼容 JNI 类名。项目专用发布 keystore 已生成,保存在忽略的 `.local/android-signing/` 下,密钥与配套信息文件权限为 0600、目录为 0700,仓库外另有同机备份。后续版本必须复用此身份,不要重新生成覆盖。

四项 Android 签名 Actions Secrets 已配置: `ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`。APK 签名与更新清单 Ed25519 签名分别管理。二开项目不需要原作者私钥,也不承诺覆盖原版安装包或自动迁移原版应用数据。同机备份仍需补充离线或独立存储备份。
