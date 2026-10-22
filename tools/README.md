# 研发命令

`tools` 是独立 Go module，编排研发构建、构件身份、发行装配与正式 MCP 业务试验。产品服务不依赖该 module。环境与命令见[贡献说明](../CONTRIBUTING.md)。

在仓库根运行：

```sh
go -C tools run ./cmd/olap-dev bootstrap
go -C tools run ./cmd/olap-dev build
go -C tools run ./cmd/olap-dev server-distribution --artifacts .tmp/dev-distribution
go -C tools run ./cmd/olap-dev test --distribution .tmp/dev-distribution
go -C tools run ./cmd/olap-dev example --example orders \
  --distribution .tmp/dev-distribution --artifacts .tmp/orders
```

`internal/products` 消费 Gradle 实际构件清单与 Go 构建结果，核对输入和字节身份，装配服务、连接器、SDK、平台包和 GitHub skill。`internal/scenario` 持有研发所需的作者输入、独立业务预期、官方 Go MCP 客户端、外部签名者和临时服务进程，通过真实公开边界观察行为。研发工具不复制计算引擎或授权规则。

构建只产生构件。试验使用明确的同轮发行目录；输出目录必须尚不存在，失败现场保留。`test-jvm --jvm-test <类或方法>` 与 `test-go --distribution <发行目录> --go-test <选择>` 用于聚焦已有行为。
