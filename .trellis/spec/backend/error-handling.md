# 命令错误契约

## 适用范围与签名

`core/bus` 的命令处理器签名：

```go
type Handler func(ctx context.Context, seq int64, args map[string]any) (any, error)
```

## 结果与错误

成功经总线包装为 `t=result`、`ok=true`、`data`；失败为 `ok=false`、`err={code,msg,retryable,detail}`。
业务失败使用 `*bus.Err{Code, Msg, Detail, Retryable}`；普通 error 转 `E_INTERNAL`。
用户可见 Msg 写中文，既有错误码保持稳定，是否重试由 Retryable 明确表达。

## 校验与错误矩阵

| 情况 | 行为 |
|---|---|
| 合法参数 / 已完成操作 | 返回实际结果 |
| 参数无效 / 未鉴权 / 能力缺失 | 对应既有码，如 E_INVALID / E_AUTH / E_UNSUPPORTED，不伪装成功 |
| 普通未分类 error | 总线统一归 E_INTERNAL |
| handler panic | runGuarded 转内部错误并记录；不得穿越 C ABI |

## 示例与错误写法

`bus.NewErr(code, msg, args...)`：msg 有格式占位符时按格式串处理，否则首个 string 参数作 Detail。见 [NewErr 回归](../../../core/bus/newerr_test.go)。
正确：返回带码错误交给总线包装。错误：吞掉 handler 错误后返回空数组 / 成功，或自行拼出不同形状的错误对象。

## 必要测试

断言错误码、消息、重试标记、格式化消息和结果信封；能力缺失不能与合法空结果混淆。
实现依据：[总线](../../../core/bus/bus.go)、[C 边界](../../../core/ffi/main.go)。
