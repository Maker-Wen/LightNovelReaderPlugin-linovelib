#!/usr/bin/env python3
"""Compare the settings entry point in a built host and plugin APK using Android SDK apkanalyzer."""

import argparse
import re
import subprocess


def page_descriptors(analyzer, apk, class_name):
    result = subprocess.run(
        [analyzer, "dex", "code", "--class", class_name, apk],
        check=True, capture_output=True, text=True,
    )
    return set(re.findall(r"^\.method public[^\n]* (PageContent\([^\n]+)", result.stdout, re.MULTILINE))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("host_apk")
    parser.add_argument("plugin_apk")
    parser.add_argument("--apkanalyzer", default="apkanalyzer")
    args = parser.parse_args()
    host = page_descriptors(args.apkanalyzer, args.host_apk, "io.nightfish.lightnovelreader.api.plugin.LightNovelReaderPlugin")
    plugin = page_descriptors(args.apkanalyzer, args.plugin_apk, "io.nightfish.lightnovelreader.plugin.linovelib.LinovelibPlugin")
    print("Host:", ", ".join(sorted(host)) or "missing PageContent")
    print("Plugin:", ", ".join(sorted(plugin)) or "missing PageContent")
    if not host or not host.issubset(plugin):
        print("FAIL: plugin does not override the host settings entry point; the default empty page may run.")
        return 1
    print("PASS: settings entry descriptors match. Rendering still requires a device check.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
