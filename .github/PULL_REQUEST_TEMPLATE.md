## 这个 PR 做了什么

<!-- 一句话说明改动目的与影响面。 -->

## 关联 issue

<!-- 例如 Closes #12；没有就写「无」。 -->

## 验证范围

- [ ] `python3 scripts/check_repository.py`
- [ ] `scripts/verify.sh`（原生改动时必须，含主机 CTest）
- [ ] JVM 单测
- [ ] 设备回归（`scripts/verify.sh --device`）
- [ ] UI 改动：`AdaptiveUiTest` 与滑动/撤销/历史/自动替换回归

请按实际执行情况勾选，并在下面写明哪一项没有跑、为什么：

## 格式与许可约定

- [ ] 转码核心改动说明了严格保留与相册兼容模式各自的行为
- [ ] 没有静默丢弃 HDR / 动态视频，也没有把兼容模式描述为逐值无损
- [ ] 新增依赖固定了版本与下载散列，并同步了 `THIRD_PARTY_NOTICES.md` 与许可快照
- [ ] 没有提交签名材料、`local.properties`、APK、原始像素 dump 或个人样本
- [ ] 文档中的机型/序列号已用占位符（如 `<device-model>`）

## 备注

<!-- 已知限制、后续计划，或需要 reviewer 特别关注的点。 -->
