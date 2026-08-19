#!/usr/bin/env bash
# Writes the Gradle plugin marker publication into the local Maven repository.
#
# `plugins { id("io.heapy.kinetica") }` resolves a pom-only artifact whose coordinates Gradle
# derives from the plugin id: io.heapy.kinetica:io.heapy.kinetica.gradle.plugin. That marker
# depends on the real implementation module. Nothing but `java-gradle-plugin` generates it, and
# this repository builds with the Kotlin Toolchain, so it is written here.
#
# The marker lands next to the modules the toolchain publishes, which is all `scripts/release.sh`
# needs: its staging step globs every artifact directory under the group, so the marker is copied,
# checksummed and signed with everything else.
#
#   scripts/gradle-plugin-marker.sh          write into ~/.m2/repository
#   MAVEN_LOCAL_REPO=/tmp/repo scripts/gradle-plugin-marker.sh
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_root"

version="$(sed -n 's/^ *version: *//p' publish.module-template.yaml | head -1)"
[ -n "$version" ] || { echo "cannot read version from publish.module-template.yaml" >&2; exit 1; }

maven_local="${MAVEN_LOCAL_REPO:-$HOME/.m2/repository}"
artifact="io.heapy.kinetica.gradle.plugin"
target="$maven_local/io/heapy/kinetica/$artifact/$version"
pom="$target/$artifact-$version.pom"

mkdir -p "$target"
# The metadata block mirrors publish.module-template.yaml: Central validates the marker POM like
# any other artifact and rejects it without name, description, url, licenses, developers and scm.
cat > "$pom" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>io.heapy.kinetica</groupId>
  <artifactId>$artifact</artifactId>
  <version>$version</version>
  <packaging>pom</packaging>
  <name>$artifact</name>
  <description>Gradle plugin marker for io.heapy.kinetica.</description>
  <url>https://github.com/Heapy/kinetica</url>
  <licenses>
    <license>
      <name>The Apache License, Version 2.0</name>
      <url>https://www.apache.org/licenses/LICENSE-2.0.txt</url>
    </license>
  </licenses>
  <developers>
    <developer>
      <name>Ruslan Ibrahimau</name>
    </developer>
  </developers>
  <scm>
    <connection>scm:git:https://github.com/Heapy/kinetica.git</connection>
    <developerConnection>scm:git:https://github.com/Heapy/kinetica.git</developerConnection>
    <url>https://github.com/Heapy/kinetica.git</url>
  </scm>
  <dependencies>
    <dependency>
      <groupId>io.heapy.kinetica</groupId>
      <artifactId>kinetica-gradle-plugin</artifactId>
      <version>$version</version>
    </dependency>
  </dependencies>
</project>
EOF

echo "marker: $pom"
