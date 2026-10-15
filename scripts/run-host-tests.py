#!/usr/bin/env python3
"""Run *HostTest against the installed API 3 host and packaged debug plugin.

Usage: JAVA_HOME=... ANDROID_HOME=... python3 scripts/run-host-tests.py --serial emulator-5556
Requires an already running test device with LightNovelReader 1.2.x installed.
Does not install packages, launch the host, or change its data. The temporary DEX
contains tests/JUnit only; production and API classes load from the actual APKs.
"""

import argparse
import os
from pathlib import Path
import re
import shlex
import subprocess
import tempfile
import zipfile


def run(args, **kwargs):
    return subprocess.run(args, check=True, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--no-build", action="store_true", help="Use the already verified current build")
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[1]
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
    adb = [str(sdk / "platform-tools/adb"), "-s", args.serial]
    if not args.no_build:
        run([str(repo / "gradlew"), "--offline", ":plugin:assembleDebug", ":plugin:compileDebugUnitTestKotlin"], cwd=repo)

    classes_root = repo / "plugin/build/intermediates/built_in_kotlinc"
    tests = classes_root / "debugUnitTest/compileDebugUnitTestKotlin/classes"
    production = classes_root / "debug/compileDebugKotlin/classes"
    test_files = sorted(tests.rglob("*HostTest*.class"))
    names = [str(p.relative_to(tests).with_suffix("")).replace(os.sep, ".") for p in test_files if p.name.endswith("HostTest.class")]
    if not names:
        raise RuntimeError("No compiled HostTest classes; build the unit test sources first")
    plugin = repo / "plugin/build/outputs/apk/debug/plugin-debug.apk.lnrp"
    if not plugin.is_file():
        raise RuntimeError("No packaged debug plugin")
    host_paths = run(adb + ["shell", "pm", "path", "indi.dmzz_yyhyy.lightnovelreader"], capture_output=True, text=True).stdout
    host_apks = [line.removeprefix("package:").strip() for line in host_paths.splitlines() if line.startswith("package:")]
    if not host_apks:
        raise RuntimeError("Install the API 3 host on the test device first")

    versions_text = (repo / "gradle/libs.versions.toml").read_text()
    def version(key):
        return re.search(r'^' + re.escape(key) + r' = "([^"]+)"', versions_text, re.MULTILINE).group(1)

    cache = Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle"))) / "caches/modules-2/files-2.1"
    def artifact(group, name, release, suffix="jar"):
        matches = sorted((cache / group / name / release).glob(f"*/{name}-{release}.{suffix}"))
        if not matches:
            raise RuntimeError(f"Missing cached artifact: {group}:{name}:{release}")
        return matches[0]

    junit = artifact("junit", "junit", version("junit"))
    hamcrest = artifact("org.hamcrest", "hamcrest-core", "1.3")
    kotlin = artifact("org.jetbrains.kotlin", "kotlin-stdlib", version("kotlin"))
    coroutines = artifact("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", version("kotlinxCoroutinesCore"))
    api = artifact("io.nightfish.lightnovelreader", "api", version("lightnovelreaderApi"), "aar")
    desugar = artifact("com.android.tools", "desugar_jdk_libs_configuration", version("desugar"))
    compile_sdk = re.search(r"compileSdk = (\d+)", (repo / "plugin/build.gradle.kts").read_text()).group(1)
    android = sdk / f"platforms/android-{compile_sdk}/android.jar"
    # Use AGP's D8, which understands the same Kotlin metadata as the build.
    r8 = artifact("com.android.tools.build", "builder", version("agp"))
    java = Path(os.environ["JAVA_HOME"]) / "bin/java"

    with tempfile.TemporaryDirectory(prefix="linovelib-host-tests-") as directory:
        tmp = Path(directory)
        test_jar = tmp / "tests.jar"
        with zipfile.ZipFile(test_jar, "w") as archive:
            for path in test_files:
                archive.write(path, path.relative_to(tests))
        api_jar = tmp / "api.jar"
        with zipfile.ZipFile(api) as archive:
            api_jar.write_bytes(archive.read("classes.jar"))
        desugar_config = tmp / "desugar.json"
        with zipfile.ZipFile(desugar) as archive:
            desugar_config.write_bytes(archive.read("META-INF/desugar/d8/desugar.json"))
        dex = tmp / "tests-dex.jar"
        # Match the plugin's java.time -> j$.time descriptors on the API 3 host.
        command = [str(java), "-cp", str(r8), "com.android.tools.r8.D8", "--min-api", "24", "--lib", str(android), "--desugared-lib", str(desugar_config)]
        for path in (production, api_jar, kotlin, coroutines):
            command += ["--classpath", str(path)]
        run(command + ["--output", str(dex), str(test_jar), str(junit), str(hamcrest)])
        remote = run(adb + ["shell", "mktemp", "-d", "/data/local/tmp/linovelib-host-tests.XXXXXX"], capture_output=True, text=True).stdout.strip()
        if not re.fullmatch(r"/data/local/tmp/linovelib-host-tests\.[A-Za-z0-9]+", remote):
            raise RuntimeError(f"Unexpected temporary directory: {remote!r}")
        try:
            run(adb + ["push", str(plugin), remote + "/plugin.apk"])
            run(adb + ["push", str(dex), remote + "/tests.jar"])
            classpath = ":".join(host_apks + [remote + "/plugin.apk", remote + "/tests.jar"])
            print("Host APKs:", host_apks, "\nTest classes:", names, flush=True)
            run(adb + ["shell", "CLASSPATH=" + shlex.quote(classpath) + " app_process /system/bin org.junit.runner.JUnitCore " + " ".join(map(shlex.quote, names))])
        finally:
            run(adb + ["shell", "rm", "-rf", remote])


if __name__ == "__main__":
    main()
