# SOURCE-00 · AiToEarn / DTK 源码审计 —— **无法执行**

日期：2026-11-16　状态：**阻塞，未产出审计结论**

## 1. 阻塞原因

本地不存在这两个项目的源码。已检索：

```
D:/devworkspace/software/workspace/           （全部一级目录）
  AIProjects/ AI_Project/ coolpilot-platform/ devcodes/
  myproject/ opensourcecodes/ testcodes/
搜索 *aitoearn* / *dtk* → 0 命中
opensourcecodes/ 内均为 JDK/Kafka/Netty/MyBatis 等无关项目
```

**我不会凭 README 或公开 Issue 写"审计结论"。**

任务卡自己写了这条验收标准：

> README 功能介绍不能作为代码已实现的证据。

同理，我没有代码就声称"AiToEarn 的 OAuth 回调有安全问题""DTK 的 Adapter 注册机制可以吸收"，
那不是审计，是复述网上说法。

## 2. 需要你提供其一

| 方式 | 说明 |
|---|---|
| A | 把两个仓库 clone 到 `opensourcecodes/` 下，告诉我路径 |
| B | 给我可访问的仓库地址 + 允许联网 clone |
| C | 只审计其中一个（比如先 DTK） |

## 3. 拿到源码后我会做什么（不预设结论）

| 项 | 判定依据 |
|---|---|
| 平台能力元数据 | 找到注册表实现与调用点，确认是"声明"还是"绑定到函数" |
| Adapter 注册 | 看是否真的动态加载，还是硬编码 if-else |
| OAuth 回调安全 | 读回调入口代码，确认 state/ticket 是否一次性、是否校验登录主体 |
| 限流与错误分类 | 看错误码枚举与重试策略的真实实现 |
| 可复用性 | 必须给出文件路径 + 函数名 + 调用链，否则记"未验证" |

## 4. 与此同时可推进的部分

SOURCE-01（本仓库现状审计）不依赖外部源码，已完成，见 `SOURCE-01-BASELINE.md`。

其中一条结论会直接影响 SOURCE-00 的取舍：
**我们自己的 Python 侧根本没有 Adapter Registry**，能力 JSON 是手写的、与实现零联动。
所以外部项目的注册机制值不值得借鉴，要等看到我们自己的缺口全貌再判断。
