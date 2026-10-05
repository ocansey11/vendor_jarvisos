#!/bin/bash
# Collects what the JarvisOS service did on the running emulator.
ADB=~/android/lineage/out/host/linux-x86/bin/adb
sh() { $ADB shell "$@" 2>&1; }
echo "== boot";          sh getprop sys.boot_completed; sh getprop ro.build.fingerprint; sh uptime
echo "== system_server"; sh 'pidof system_server; getprop sys.system_server.start_count 2>/dev/null'
echo "== status";        sh cmd jarvis status
echo "== services";      sh 'service list | grep -i jarvis'
echo "== isReady (code 4)";     sh service call jarvis 4
# The shell does not hold ACCESS_JARVIS_SERVICE, so the remaining Binder
# methods refuse it; `cmd jarvis` is the shell's way in.
echo "== query";   sh 'cmd jarvis query "hello jarvis"'
echo "== tools";   sh cmd jarvis tools | head -40
echo "== Binder without the permission (expect ffffffff)"; sh 'service call jarvis_tools 1' | head -2
echo "== files";         $ADB root >/dev/null 2>&1; sleep 2; sh 'ls -laZR /data/system/jarvis 2>&1 | head -40; ls -la /system/lib64/libobjectbox-jni.so /system/lib64/libcactus.so /system/etc/jarvisos/tools 2>&1 | head -30'
echo "== logcat (jarvis)"; $ADB logcat -d 2>/dev/null | grep -iE 'jarvis|objectbox|cactus|ToolScanner|ToolDispatcher|DreamWorker|ModelRegistry|Box\[' | grep -v 'service call' | cut -c1-330 | head -150
echo "== fatal / wtf in system_server"; $ADB logcat -d -b crash 2>/dev/null | cut -c1-300 | head -60
echo "== avc denials (system_server / jarvis)"; $ADB logcat -d 2>/dev/null | grep 'avc: ' | grep -iE 'system_server|jarvis' | cut -c1-330 | sort | uniq -c | sort -rn | head -40
