# 与 :app 相同的策略：开源项目保留类名，仅做裁剪与优化
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-dontobfuscate

# Room / 模型 keep 规则由 :core 的 consumer-rules.pro 提供
