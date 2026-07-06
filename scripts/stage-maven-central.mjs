#!/usr/bin/env node

import { copyFileSync, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { execFileSync } from "node:child_process";

const modules = {
  "kinetica-compiler": {
    group: "io.heapy.kinetica",
    artifactId: "kinetica-compiler",
    version: "0.2.0",
    buildName: "kinetica-compiler",
    license: {
      name: "The Apache License, Version 2.0",
      url: "https://www.apache.org/licenses/LICENSE-2.0.txt",
    },
  },
};

const selected = process.argv.slice(2);
const names = selected.length > 0 ? selected : Object.keys(modules);
const stagingRoot = join("build", "staging-maven-central");

function run(args) {
  execFileSync("./kotlin", args, { stdio: "inherit" });
}

function copyRequired(from, to) {
  if (!existsSync(from)) {
    throw new Error(`Missing expected artifact: ${from}`);
  }
  mkdirSync(dirname(to), { recursive: true });
  copyFileSync(from, to);
}

function groupPath(group) {
  return group.split(".").join("/");
}

function checkPomMetadata(pomFile) {
  const pom = readFileSync(pomFile, "utf8");
  const missing = [];
  for (const tag of ["url", "developers", "scm", "licenses"]) {
    if (!pom.includes(`<${tag}`)) missing.push(tag);
  }
  return missing;
}

function xmlEscape(value) {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&apos;");
}

function ensurePomLicense(pomFile, license) {
  const pom = readFileSync(pomFile, "utf8");
  if (pom.includes("<licenses>")) return;

  const licenseXml = [
    "  <licenses>",
    "    <license>",
    `      <name>${xmlEscape(license.name)}</name>`,
    `      <url>${xmlEscape(license.url)}</url>`,
    "    </license>",
    "  </licenses>",
  ].join("\n");

  const projectUrlEnd = pom.indexOf("</url>");
  if (projectUrlEnd < 0) {
    throw new Error(`Cannot insert license metadata into ${pomFile}: no project <url> element`);
  }
  const insertAt = pom.indexOf("\n", projectUrlEnd);
  writeFileSync(
    pomFile,
    `${pom.slice(0, insertAt + 1)}${licenseXml}\n${pom.slice(insertAt + 1)}`,
  );
}

rmSync(stagingRoot, { recursive: true, force: true });

for (const name of names) {
  const module = modules[name];
  if (!module) {
    throw new Error(`Unknown module '${name}'. Known modules: ${Object.keys(modules).join(", ")}`);
  }

  run(["task", `:${name}:jarJvm`]);
  run(["task", `:${name}:sourcesJarJvm`]);
  run(["task", `:${name}:javadocJarJvm`]);
  run(["task", `:${name}:prepareMavenPublishables`]);

  const leaf = join(
    stagingRoot,
    groupPath(module.group),
    module.artifactId,
    module.version,
  );
  const base = `${module.artifactId}-${module.version}`;

  copyRequired(
    join("build", "tasks", `_${name}_jarJvm`, `${module.buildName}-jvm.jar`),
    join(leaf, `${base}.jar`),
  );
  copyRequired(
    join("build", "tasks", `_${name}_sourcesJarJvm`, `${module.buildName}-jvm-sources.jar`),
    join(leaf, `${base}-sources.jar`),
  );
  copyRequired(
    join("build", "tasks", `_${name}_javadocJarJvm`, `${module.buildName}-jvm-javadoc.jar`),
    join(leaf, `${base}-javadoc.jar`),
  );
  copyRequired(
    join("build", "tasks", `_${name}_prepareMavenPublishables`, `${module.buildName}-jvm.pom`),
    join(leaf, `${base}.pom`),
  );

  ensurePomLicense(join(leaf, `${base}.pom`), module.license);

  const missing = checkPomMetadata(join(leaf, `${base}.pom`));
  if (missing.length > 0) {
    console.warn(
      `warning: ${name} POM is missing Central-required metadata: ${missing.join(", ")}`,
    );
  }

  console.log(`staged ${module.group}:${module.artifactId}:${module.version} at ${leaf}`);
}

console.log(`\nMaven-layout staging root: ${stagingRoot}`);
