# SecureVault

本地加密账号管理器（Android）

## 当前进度：阶段 1~4 完成

### 已实现功能
- 多标签系统（一个账号可属于多个标签，支持筛选）
- 账号增删改查
- 密码 AES-GCM 加密存储（主密码保护）
- Have I Been Pwned 泄露查询入口
- 深色安全风格 UI（海军蓝 + 青色）

### 重要说明
1. **主密码**：当前代码里为了演示写死了 `"demo123"`，正式使用请改成用户设置的主密码，并加上解锁界面。
2. **HIBP**：需要到 https://haveibeenpwned.com/API/Key 申请 API Key，然后替换 `HibpClient.kt` 中的 `API_KEY`。
3. **存储**：当前使用本地 JSON 文件。二次开发建议换成 Room + SQLCipher。
4. **加密**：`CryptoUtils` 使用 PBKDF2 + AES-GCM，可直接使用，也可升级为 Android Keystore。

### 如何运行
1. 用 Android Studio 打开 `SecureVault` 文件夹
2. Gradle Sync
3. 运行到模拟器或真机

### 二次开发建议
- 增加主密码设置/验证界面
- 把存储换成 Room
- 增加密码生成器、搜索、自动锁定
- 标签颜色自定义
- 导出加密备份

项目采用清晰分层，方便你继续扩展。
