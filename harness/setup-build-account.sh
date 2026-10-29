#!/bin/bash
# One-time setup, as root: the shared build account.
#
# Everyone in group jarvis builds and runs the emulator as jarvisbuild, which
# owns out/: Android's build sets file times there, and only a file's owner may
# do that, so a tree built by one person cannot be rebuilt by another. jh runs
# the scripts below through `sudo -n -u jarvisbuild` once this account exists.
# jarvisbuild has no rights beyond group jarvis and owning out/ and the ccache.
set -e
[ "$(id -u)" = 0 ] || { echo "run as root"; exit 1; }
H=/srv/jarvis/lineage/vendor/jarvisos

id jarvisbuild >/dev/null 2>&1 || useradd --system --gid jarvis \
    --home-dir /srv/jarvis/buildhome --create-home --shell /usr/sbin/nologin \
    --comment "JarvisOS shared builds" jarvisbuild
# The ObjectBox build reads its settings from the builder's home.
[ -f /root/.objectbox-build.properties ] && install -o jarvisbuild -g jarvis -m 644 \
    /root/.objectbox-build.properties /srv/jarvis/buildhome/

rule=$(mktemp)
cat > "$rule" <<EOF
# JarvisOS (vendor/jarvisos/harness/setup-build-account.sh): group jarvis
# builds and runs the emulator as jarvisbuild. Only these scripts, any arguments.
%jarvis ALL=(jarvisbuild) NOPASSWD: $H/tests/emulator/build.sh, $H/tests/emulator/run.sh, $H/harness/build-rom.sh, $H/harness/build-needle-arm64.sh
EOF
visudo -cf "$rule" >/dev/null && install -m 440 "$rule" /etc/sudoers.d/jarvis-build
rm -f "$rule"

echo "jarvisbuild takes out/ and the ccache (file records only; a few minutes)"
chown -R -h jarvisbuild:jarvis /srv/jarvis/lineage/out /srv/jarvis/ccache
echo "done: $(id jarvisbuild)"
