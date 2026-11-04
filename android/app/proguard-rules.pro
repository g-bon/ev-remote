# Paho loads network modules and its logger through reflection and ServiceLoader.
-keep class org.eclipse.paho.client.mqttv3.** { *; }
-dontwarn org.eclipse.paho.**
