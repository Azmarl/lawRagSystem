# Jev 意图识别接入说明

## 结论

当前默认使用**腾讯云 EdgeOne Makers 的 Jev**。它与旧版“API 网关”产品无关：API 网关（product/628）已经停售，但 EdgeOne Makers（product/1552）仍提供 `@makers/jev`。

Jev 只支持 `/v1/systemone` 结构化决策协议，不支持 `/chat/completions`，也不支持流式输出，这与当前 `AliyunDecisionClient` 的调用方式一致。

## 方案 A：腾讯云 EdgeOne Makers（当前默认）

1. 登录 EdgeOne Makers 控制台：

   https://console.cloud.tencent.com/edgeone/makers?tab=models&subTab=apikey

2. 在 `Makers -> Models -> API Key` 创建 API Key。
3. `.env` 配置：

```env
ALIYUN_DECISION_API_URL=https://ai-gateway.edgeone.link/v1/systemone
ALIYUN_DECISION_API_KEY=sk-你的EdgeOneKey
ALIYUN_DECISION_API_MODEL=@makers/jev
ALIYUN_DECISION_TIMEOUT_MS=2000
ALIYUN_DECISION_CONFIDENCE_THRESHOLD=0.85
```

4. 重启后端。

注意：

- 必须是 **EdgeOne Makers** 的 Key，不是腾讯云 API 网关的 Key；
- 旧版 API 网关 `product/628` 已停止售卖，不能用来接 Jev；
- 若账号无法开通 EdgeOne Makers，再使用方案 B 或 C。

## 方案 B：万维易源 JevK5（国内第三方备选）

这是第三方部署的开源 JevK5，兼容 Jev 协议，不是 TypeSafe 官方服务；适合不想开通 EdgeOne 的情况。

```env
ALIYUN_DECISION_API_URL=https://jev.showapi.com/v1/systemone
ALIYUN_DECISION_API_KEY=你的ShowAPI_appKey
ALIYUN_DECISION_API_MODEL=JevK5-V0.3
```

- 注册：https://www.showapi.com/
- 控制台 -> 我的应用 -> 创建应用 -> 复制 appKey
- 参考价格约 ¥0.2/百万输入 token，输出免费，以平台为准。

## 方案 C：TypeSafe 官方 API

```env
ALIYUN_DECISION_API_URL=https://api.typesafe.ai/v1/systemone
ALIYUN_DECISION_API_KEY=tsf_你的Key
ALIYUN_DECISION_API_MODEL=jev-latest
```

需要 TypeSafe 官方账号，可能涉及国际支付/网络访问；适合对“官方 Jev”有明确要求的场景。

## 当前配置状态

`.env` 已切到腾讯云 EdgeOne：

```env
ALIYUN_DECISION_API_URL=https://ai-gateway.edgeone.link/v1/systemone
ALIYUN_DECISION_API_KEY=
ALIYUN_DECISION_API_MODEL=@makers/jev
```

Key 暂时留空。留空时不会发起网络请求，自动使用规则兜底，不影响问答。填入有效 EdgeOne Key 后，意图识别会优先调用 Jev；失败、超时、401 或低置信度时仍然回退规则。

## 如何确认生效

后端启动日志：

```text
决策模型配置: enabled=true, endpoint=https://ai-gateway.edgeone.link/v1/systemone, model=@makers/jev
```

每次问答：

```text
意图识别（决策模型）: query='...', category=PRIVATE, confidence=...
```

如果仍是“意图识别（规则兜底）”，检查：

1. `ALIYUN_DECISION_API_KEY` 是否为空；
2. Key 是否为 EdgeOne Makers 的 API Key；
3. 服务器是否能访问 `https://ai-gateway.edgeone.link`；
4. 401 时看日志中的错误信息。

## 参考

- 腾讯云 EdgeOne Makers Jev：https://cloud.tencent.cn/document/product/1552/138731
- 万维易源 JevK5：https://www.showapi.com/ai/jev
- TypeSafe 官方快速开始：https://typesafe-jev.com/en/getting-started/
- TypeSafe API 文档：https://docs.typesafe.ai/api
