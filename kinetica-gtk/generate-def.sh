#!/usr/bin/env bash
# Kotlin/Native .def files do not expand shell substitutions, so generate this gitignored file
# after running pkg-config on Linux with libgtk-4-dev (KNT-0047).
set -euo pipefail
cd "$(dirname "$0")"

command -v pkg-config >/dev/null || { echo "pkg-config not found" >&2; exit 1; }
pkg-config --exists gtk4 || { echo "gtk4 dev package not found (apt install libgtk-4-dev)" >&2; exit 1; }

mkdir -p cinterop
# K/N cinterop can mix its glibc sysroot with host headers:
# - -I/usr/include keeps host headers together; fixes "__BEGIN_NAMESPACE_STD".
# - -D__glibc_clang_prereq(maj,min)=0 avoids "function-like macro is not defined" on glibc 2.38+.
cat > cinterop/gtk4.def <<EOF
headers = gtk/gtk.h
package = gtk4
compilerOpts.linux = $(pkg-config --cflags gtk4) -I/usr/include -D__glibc_clang_prereq(maj,min)=0
linkerOpts.linux = --allow-shlib-undefined -L/usr/lib/x86_64-linux-gnu -L/usr/lib $(pkg-config --libs gtk4)
EOF
echo "wrote cinterop/gtk4.def"
