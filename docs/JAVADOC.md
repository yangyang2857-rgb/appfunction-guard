# Javadoc / Dokka

本仓库暂时没有引入 Dokka：四个模块的公开 API 都用 KDoc 写在源码里，`sources.jar` 一并发布，
IDE 里可以直接看到。

Maven Central 要求每个 artifact 附带 `-javadoc.jar`，所以发布时用一个只含本说明的占位 jar
满足校验，而不是伪造一份空文档。等 API 稳定（0.2.0）再接入 Dokka 生成真实文档站。

这不是长期方案，记在 docs/PUBLISHING.md 的待办里。
