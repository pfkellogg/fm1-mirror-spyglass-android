#!/bin/sh
# Checks the screen and updater messages on a computer (no phone needed).
cd "$(dirname "$0")/.." && mkdir -p build/test &&
javac -d build/test app/src/main/java/com/pfkellogg/fm1mirror/Codec.java test/CodecTest.java &&
java -cp build/test com.pfkellogg.fm1mirror.CodecTest firmware
