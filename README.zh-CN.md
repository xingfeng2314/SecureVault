# SecureVault

[English](README.md) | 简体中文

SecureVault 是一款离线优先的 Android 密码管理器和私密保险箱，用 Kotlin 和 Jetpack Compose 开发。所有数据都在手机上加密，除非你开启加密的 Google Drive 备份，否则数据不会离开手机。

## 功能

- **密码本**：保存账号，支持标签、搜索、复制密码和查看密码。复制时剪贴板会标记为敏感内容，30 秒后自动清空。
- **照片和文件保险箱**：存放重要证件照片和 PDF、Word 等文件，支持标签、重命名和长按删除，PDF 可以直接在软件里打开。
- **密码生成器**，使用 `SecureRandom`。
- **泄露检查**：使用免费的 [Pwned Passwords](https://haveibeenpwned.com/API/v3#PwnedPasswords) 接口，只发送密码 SHA-1 哈希的前 5 位，不会上传密码本身。
- **指纹解锁**：使用 Android Keystore 密钥，新录入指纹后密钥自动失效。
- **自动锁定**：切到后台 30 秒后自动锁定，并禁止截屏（`FLAG_SECURE`）。
- **加密备份**：可导出本地 `.svbak` 文件，也可自动备份到你自己的 Google Drive，保留最近 3 份。

## 安全设计

- 主密码经 PBKDF2-HMAC-SHA256（21 万次迭代）派生密钥，用来加密一把随机的 256 位数据密钥。
- 数据和文件使用 AES-256-GCM 加密，按 1 MB 分块，每块使用独立的 IV。
- 备份在上传前已加密，后台上传任务接触不到任何密钥。
- Google Drive 只申请 `drive.file` 权限，软件只能看到自己创建的文件。
- 关闭了 Android 系统备份（`allowBackup="false"`）。

忘记主密码后数据无法找回。

## 编译

环境要求：Android Studio、JDK 17、Android 8.0（API 26）及以上。

```bash
git clone https://github.com/xingfeng2314/SecureVault.git
```

用 Android Studio 打开项目，同步 Gradle 后运行即可。

### Google Drive 备份（可选）

仓库里的 OAuth 客户端绑定的是原作者的签名，用自己的签名编译时需要自己创建客户端：

1. 在 [Google Cloud Console](https://console.cloud.google.com/) 中启用 Google Drive API。
2. 创建类型为 **Android** 的 OAuth 客户端，包名填 `com.morterain.securevault`，再填你的签名证书 SHA-1（可用 `./gradlew signingReport` 查看）。
3. 配置 OAuth 同意屏幕，把你的账号加为测试用户，或者直接发布。

## 计划中的功能

- TOTP 动态验证码
- 自动填充服务
- 安全报告
- Argon2id 密钥派生
- 从 Google Drive 恢复

## 许可证

[MIT](LICENSE)
