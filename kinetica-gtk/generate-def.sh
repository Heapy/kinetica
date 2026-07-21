#!/usr/bin/env bash
# Generates cinterop/gtk4.def with the GTK4 compiler/linker flags expanded by pkg-config.
#
# Why generated (KNT-0047): Kotlin/Native .def files do NOT run shell substitution —
# `compilerOpts.linux = $(pkg-config ...)` would pass the literal `$(...)` string to clang
# (def-file "substitution" is target-suffix resolution only). So the shell expands the flags
# HERE, before the toolchain ever sees the file. Run on a machine with libgtk-4-dev installed
# (the CI linux job does); the output is gitignored.
set -euo pipefail
cd "$(dirname "$0")"

command -v pkg-config >/dev/null || { echo "pkg-config not found" >&2; exit 1; }
pkg-config --exists gtk4 || { echo "gtk4 dev package not found (apt install libgtk-4-dev)" >&2; exit 1; }

mkdir -p cinterop
# Two glibc-mixing workarounds for the cinterop indexer (KNT-0047):
# - -I/usr/include forces <time.h> & friends to resolve into the HOST glibc consistently.
#   Without it the indexer mixes the K/N cross-sysroot glibc 2.19 headers with the system's
#   2.39 ones (sys/cdefs.h from one, time.h from the other → "unknown type name
#   '__BEGIN_NAMESPACE_STD'"). Plain -I ranks before the sysroot search paths.
# - -D__glibc_clang_prereq(maj,min)=0: glibc 2.38+ probes the indexer's clang through this
#   macro before defining it → "function-like macro is not defined" in sys/cdefs.h.
#   0 takes the conservative glibc paths; harmless for GTK's API surface.
cat > cinterop/gtk4.def <<EOF
headers = gtk/gtk.h
package = gtk4
compilerOpts.linux = $(pkg-config --cflags gtk4) -I/usr/include -D__glibc_clang_prereq(maj,min)=0
linkerOpts.linux = $(pkg-config --libs gtk4)
EOF
echo "wrote cinterop/gtk4.def"
