# 烟火账

烟火账是一款本地优先的个人 Android 记账应用，面向 Android 12–16，主要验收设备为 Android 16、HyperOS 3、机型代码 `2509FPN0BC`。

## 主要功能

- 兼容微信 Lite 支付成功页：读取 `contentDescription` 中的商户、金额和完成操作。
- 无障碍服务连接或恢复后会主动检查当前页面，避免静态支付成功页漏采。

- 手动新增、编辑、软删除和恢复账单；
- 微信系统通知自动识别明确的“已支付￥金额”模板；
- 微信支付成功页自动提取金额和交易对象；
- 微信红包发送前暂存金额，返回聊天页并检测到新增的己方红包卡片后生成待确认事件；
- 微信支付结果短信识别与通知账单合并；
- 普通银行短信只生成待确认事件；
- 同源幂等、跨来源合并和同金额歧义待确认；
- 转账统计属性和退款统计月份人工确认；
- 首页、本月统计、分类排行、分类占比和六个月趋势；
- 六个月趋势以对齐的同屏双柱图展示收入与支出，分类统计使用连续圆环图和排行列表；
- 参考 Cashew 的绿色种子色主题、柔和背景、大圆角面板、紧凑账单行和深色模式；
- 底部使用五等分导航，中间加号可从任意页面打开手动记账；
- 自动分类按“个人对象规则、本地高可信关键词、DeepSeek”依次执行；
- 手动分类后记住标准化交易对象，并可同步整理历史相同对象的未分类账单；
- DeepSeek 无法判断、未配置、网络异常或额度不足时提醒手动分类；
- 可选择 DeepSeek V4 Flash 或 V4 Pro，并分别使用思考或非思考模式；
- 每月最后一天 23:30 使用同一 DeepSeek 配置分析本月分类支出，并在统计页保存展示；
- 自定义分类和个人规则；
- 生物识别或设备凭据应用锁；
- AES-256-GCM 密码加密备份与事务性恢复；
- 除可选的 DeepSeek 辅助分类外，账单存储、个人规则和统计均在本地完成。

## 工程环境

- JDK 17
- Android SDK Platform 36
- Android Build Tools 36.0.0
- Gradle Wrapper 8.11.1
- Kotlin 2.1.20 / Android Gradle Plugin 8.9.2

构建前请设置 JDK 和 Android SDK 环境变量，`build.ps1` 会检查配置并调用项目自带的 Gradle Wrapper：

```powershell
$env:JAVA_HOME="C:\path\to\jdk-17"
$env:ANDROID_SDK_ROOT="C:\path\to\android-sdk"
.\build.ps1 testDebugUnitTest lintDebug assembleDebug
```

## 正式签名

Release 构建从环境变量读取签名信息，不在源码中保存密钥：

```powershell
$env:YANHUO_STORE_FILE="C:\secure\yanhuozhang-release.jks"
$env:YANHUO_STORE_PASSWORD="密钥库密码"
$env:YANHUO_KEY_ALIAS="yanhuozhang"
$env:YANHUO_KEY_PASSWORD="密钥密码"
.\build.ps1 assembleRelease
```

后续覆盖安装必须复用同一签名密钥。密钥丢失后只能卸载重装，卸载会删除本机数据。

## 代码结构

- `core/`：金额、微信模板解析、指纹、匹配和统计；
- `classification/`：本地规则后的 DeepSeek 结构化分类与失败回退；
- `data/`：Room 表、索引、DAO、事务仓库和个人规则；
- `system/`：通知监听、短信接收、提醒与 HyperOS 设置跳转；
- `backup/`：版本化备份、认证加密和恢复校验；
- `ui/`：Compose 页面、统一账单编辑器和 ViewModel。

## 隐私边界

- 只有启用 DeepSeek 且本地规则无法分类时，才会发送当前账单的交易对象、备注、金额、收支方向、支付渠道和候选分类；
- DeepSeek API Key 保存在应用本地设置中，并随密码加密备份保存；
- 无障碍服务仅订阅微信包名，最多读取 200 个可见文字节点，不使用截图或 OCR；
- 只申请 `RECEIVE_SMS`，不申请 `READ_SMS`，不会扫描历史短信；
- 原始通知和短信文本仅保存在应用私有数据库；
- 自动生成的普通支出静默入账，异常、待确认或待手动分类时才发通知；
- 导出的备份文件必须使用密码解密，密码无法找回。

## 当前限制

- 首版硬验收模板只有已提供的微信支付通知和微信网络异常结果短信；
- 银行短信模板没有真实样例，因此仅做待确认候选；
- 微信通知没有商户信息时，本地关键词通常无法分类；DeepSeek 仍无法判断则保持“未分类”；
- 微信未向无障碍框架公开文字时，支付成功页无法补全交易对象；
- 微信红包需要从金额页连续经过发送操作返回聊天页；应用未同时观察到金额和新增己方红包卡片时不会生成事件；
- 支付宝、OCR、云同步、资产账户和预算不属于当前版本；
- 应用无法早于微信或运营商产生系统通知/短信。

安装与真机设置见 [用户指南](docs/USER_GUIDE.md)，测试口径见 [验收清单](docs/TESTING.md)。

## 许可证

本项目源码采用 [PolyForm Noncommercial License 1.0.0](LICENSE)（SPDX：`PolyForm-Noncommercial-1.0.0`）。允许为非商业目的使用、修改和分发；商业用途需要另行获得授权。该许可证属于限制商业用途的源码公开许可证，不是 OSI 认定的开源许可证。第三方依赖和素材仍遵循各自的许可证。
