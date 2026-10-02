#!/bin/bash
# Runs every JarvisOS test that needs no device and prints one report.
#   vendor/jarvisos/tests/run-all.sh
# See ../TESTING.md for what each layer does and does not prove.
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOP="$(cd "$HERE/../../.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-$TOP/prebuilts/jdk/jdk21/linux-x86}"
FAIL=0

echo "=== 1. ObjectBox store test (real database, host) ==="
"$TOP/frameworks/base/services/jarvis/objectbox/test.sh" || FAIL=1

echo
echo "=== 2. Unit tests (agent logic, host JVM) ==="
(cd "$HERE" && ./gradlew --no-daemon -q cleanTest test >/dev/null 2>&1) || FAIL=1
python3 - "$HERE/build/test-results/test" <<'PY'
import glob, sys, xml.etree.ElementTree as ET
total = failed = 0
for f in sorted(glob.glob(sys.argv[1] + '/*.xml')):
    r = ET.parse(f).getroot()
    print(r.get('name').split('.')[-1])
    for t in r.findall('testcase'):
        bad = t.find('failure') is not None or t.find('error') is not None
        total += 1; failed += bad
        print('  %s  %s' % ('FAIL' if bad else 'pass', t.get('name')))
print('%d tests, %d failed' % (total, failed))
PY

echo
[ $FAIL = 0 ] && echo "ALL HOST TESTS PASSED" || echo "SOME HOST TESTS FAILED"
exit $FAIL
