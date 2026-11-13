# The application uses reflection only for the optional Android system property API.
# Keep the public application classes readable in release diagnostics.
-keep class com.mtgalegacy.deviceprobe.** { *; }
