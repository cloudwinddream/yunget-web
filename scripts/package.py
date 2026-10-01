#!/usr/bin/env python3
"""打包 yunget-web fat jar：合并 classes + 去重后的依赖 jar + 静态资源。"""
import os
import re
import shutil
import zipfile

CLASSES = "/tmp/yunget-classes"
RESOURCES = os.path.expanduser("~/workspace/yunget-web/yunget-web/backend/src/main/resources")
STAGE = "/tmp/jarstage"
OUT = os.path.expanduser("~/workspace/yunget-web/yunget-web/yunget-web.jar")

def parse_version(v):
    parts = []
    for p in re.split(r"[.\-]", v):
        parts.append((0, int(p)) if p.isdigit() else (1, p))
    return parts

def main():
    cp = open("/tmp/cp.txt").read().strip().split(":")
    # 按 (group_path, artifact) 分组，保留最高版本
    best = {}
    for jar in cp:
        m = re.match(r"(.*/repository/(.+)/([^/]+)/([^/]+))/\3-\4\.jar$", jar)
        if not m:
            print("SKIP(unparseable):", jar)
            continue
        key = (m.group(2), m.group(3))  # (group_path, artifactId)
        ver = m.group(4)
        if key not in best or parse_version(ver) > parse_version(best[key][1]):
            if key in best:
                print(f"  去重 {key[1]}: {best[key][1]} -> {ver}")
            best[key] = (jar, ver)
    jars = sorted(j for j, _ in best.values())
    print(f"选中 {len(jars)} 个依赖 jar（去重后）")

    if os.path.exists(STAGE):
        shutil.rmtree(STAGE)
    os.makedirs(STAGE)

    # 1. 依赖 jar 解包
    for jar in jars:
        with zipfile.ZipFile(jar) as z:
            for info in z.infolist():
                name = info.filename
                if name.endswith("/"):
                    continue
                # 剔除签名文件
                up = name.upper()
                if up.startswith("META-INF/") and (up.endswith(".SF") or up.endswith(".DSA") or up.endswith(".RSA")):
                    continue
                if name == "META-INF/MANIFEST.MF":
                    continue
                # module-info 可能冲突，跳过（fat jar 不需要模块化）
                if name == "module-info.class":
                    continue
                dest = os.path.join(STAGE, name)
                os.makedirs(os.path.dirname(dest), exist_ok=True)
                with z.open(info) as src, open(dest, "wb") as dst:
                    shutil.copyfileobj(src, dst)

    # 2. 自己的 classes（覆盖）
    for root, _, files in os.walk(CLASSES):
        for f in files:
            src = os.path.join(root, f)
            rel = os.path.relpath(src, CLASSES)
            dest = os.path.join(STAGE, rel)
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            shutil.copy2(src, dest)

    # 3. 静态资源
    for root, _, files in os.walk(RESOURCES):
        for f in files:
            src = os.path.join(root, f)
            rel = os.path.relpath(src, RESOURCES)
            dest = os.path.join(STAGE, rel)
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            shutil.copy2(src, dest)

    # 4. 打包
    if os.path.exists(OUT):
        os.remove(OUT)
    manifest = "Manifest-Version: 1.0\nMain-Class: com.yunget.web.MainKt\n\n"
    with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        z.writestr("META-INF/MANIFEST.MF", manifest)
        for root, _, files in os.walk(STAGE):
            for f in files:
                src = os.path.join(root, f)
                rel = os.path.relpath(src, STAGE)
                if rel == "META-INF/MANIFEST.MF":
                    continue
                z.write(src, rel)
    size = os.path.getsize(OUT)
    print(f"打包完成: {OUT} ({size/1024/1024:.1f} MB)")

if __name__ == "__main__":
    main()
