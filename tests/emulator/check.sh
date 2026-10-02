#!/bin/bash
# Collects what the JarvisOS service did on the running emulator.
ADB=~/android/lineage/out/host/linux-x86/bin/adb
sh() { $ADB shell "$@" 2>&1; }
echo "== boot";          sh getprop sys.boot_completed; sh getprop ro.build.fingerprint; sh uptime
echo "== system_server"; sh 'pidof system_server; getprop sys.system_server.start_count 2>/dev/null'
echo "== services";      sh 'service list | grep -i jarvis'
echo "== isReady (code 4)";     sh service call jarvis 4
echo "== processQuery (code 1)"; sh 'service call jarvis 1 s16 "hello jarvis"'
echo "== isIndexed (code 3)";   sh 'service call jarvis 3 s16 "/sdcard/Documents/none.txt"'
echo "== listTools (jarvis_tools code 1)"; sh 'service call jarvis_tools 1' | head -40
echo "== files";         $ADB root >/dev/null 2>&1; sleep 2; sh 'ls -laZR /data/system/jarvis 2>&1 | head -40; ls -la /system/lib64/libobjectbox-jni.so /system/lib64/libcactus.so /system/etc/jarvisos/tools 2>&1 | head -30'
echo "== logcat (jarvis)"; $ADB logcat -d 2>/dev/null | grep -iE 'jarvis|objectbox|cactus|ToolScanner|ToolDispatcher|DreamWorker|ModelRegistry|Box\[' | grep -v 'service call' | cut -c1-330 | head -150
echo "== fatal / wtf in system_server"; $ADB logcat -d -b crash 2>/dev/null | cut -c1-300 | head -60
echo "== avc denials (system_server / jarvis)"; $ADB logcat -d 2>/dev/null | grep 'avc: ' | grep -iE 'system_server|jarvis' | cut -c1-330 | sort | uniq -c | sort -rn | head -40
