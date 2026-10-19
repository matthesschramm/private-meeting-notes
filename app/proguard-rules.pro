# Native code in these libraries calls back into their Java/Kotlin classes by name.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class ai.onnxruntime.** { *; }
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**
-keep class dev.matthes.privatemeetingnotes.diarization.FallbackDiarizer$Progress { *; }
