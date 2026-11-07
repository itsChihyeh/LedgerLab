# P8 本地部分完成（2026-11-06 01:10）

T01–T06、T06b、T08 全部验证并 push 到 p8。外部模型（T07）等用户批准：anthropic SDK 可选依赖、ANTHROPIC_API_KEY、模型与费用上限。p7 尚未更新（P6 收尾后再把 p7 合进 p8）。

# 外部模型完成（2026-11-07）
DeepSeek deepseek-flash 接入；T07、T07b、T07c、T07d 验证并 push。正式运行 dev 0.26、test 0.29、hard 0.28 美元，累计约 1.5 美元（上限 5）。hard 43/46（基线 14/46）。报告 docs/eval/agent-2026-11-07.md，解读 deepseek-2026-11-07-notes.md，提交 4e73121。
