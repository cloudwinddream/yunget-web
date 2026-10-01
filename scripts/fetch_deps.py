#!/usr/bin/env python3
"""迷你 Maven 依赖解析器：用 curl（走代理）下载 POM，解析传递依赖，
把 jar+pom 按本地仓库布局写入 /root/.m2/repository，之后可用 mvn -o 离线构建，
或直接用 kotlinc 编译。"""
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

REPO = "/root/.m2/repository"
NS = {"m": "http://maven.apache.org/POM/4.0.0"}

# 中央仓库优先
REPOS = [
    "https://repo.maven.apache.org/maven2",
    "https://maven.aliyun.com/repository/public",
]

pom_cache = {}
downloaded = set()

def curl_fetch(url, out):
    os.makedirs(os.path.dirname(out), exist_ok=True)
    for _ in range(3):
        if os.path.exists(out):
            os.remove(out)
        r = subprocess.run(
            ["curl", "-sL", "--max-time", "60", "-o", out, "-w", "%{http_code}", url],
            capture_output=True, text=True)
        if r.stdout.strip() == "200" and os.path.exists(out) and os.path.getsize(out) > 0:
            return True
    return False

def gav_path(g, a, v, ext):
    return os.path.join(REPO, g.replace(".", "/"), a, v, f"{a}-{v}.{ext}")

def fetch_pom(g, a, v):
    key = (g, a, v)
    if key in pom_cache:
        return pom_cache[key]
    dest = gav_path(g, a, v, "pom")
    if not os.path.exists(dest):
        ok = False
        for repo in REPOS:
            url = f"{repo}/{g.replace('.', '/')}/{a}/{v}/{a}-{v}.pom"
            if curl_fetch(url, dest):
                ok = True
                break
        if not ok:
            pom_cache[key] = None
            return None
    try:
        pom_cache[key] = ET.parse(dest).getroot()
    except ET.ParseError:
        pom_cache[key] = None
    return pom_cache[key]

def sub_props(text, props):
    """替换 ${...}，最多迭代 10 次"""
    if text is None:
        return None
    for _ in range(10):
        m = re.search(r"\$\{([^}]+)\}", text)
        if not m:
            break
        name = m.group(1)
        val = props.get(name)
        if val is None:
            break
        text = text.replace(m.group(0), val)
    return text

def get_text(elem, tag):
    c = elem.find(f"m:{tag}", NS)
    return c.text.strip() if c is not None and c.text else None

def collect_pom_info(g, a, v, seen_poms=None):
    """返回 (properties, depMgmt, dependencies, parent_chain)。处理 parent 继承。"""
    if seen_poms is None:
        seen_poms = set()
    root = fetch_pom(g, a, v)
    if root is None:
        return {}, {}, []
    # parent
    props, dep_mgmt, deps = {}, {}, []
    parent = root.find("m:parent", NS)
    if parent is not None:
        pg, pa, pv = (get_text(parent, "groupId"), get_text(parent, "artifactId"),
                      get_text(parent, "version"))
        if pg and pa and pv and (pg, pa, pv) not in seen_poms:
            seen_poms.add((pg, pa, pv))
            p_props, p_mgmt, _ = collect_pom_info(pg, pa, pv, seen_poms)
            props.update(p_props)
            dep_mgmt.update(p_mgmt)
    # 自身 properties
    for p in root.findall("m:properties/*", NS):
        tag = p.tag.split("}")[-1]
        if p.text:
            props[tag] = p.text.strip()
    # Maven 内置属性（groupId 可从 parent 继承）
    _g = get_text(root, "groupId")
    if _g:
        props["project.groupId"] = _g
    _a = get_text(root, "artifactId")
    if _a:
        props["project.artifactId"] = _a
    _v = get_text(root, "version")
    if _v:
        props["project.version"] = _v
    # 自身 dependencyManagement（覆盖 parent）
    dm = root.find("m:dependencyManagement/m:dependencies", NS)
    if dm is not None:
        for d in dm.findall("m:dependency", NS):
            dg, da, dv = (get_text(d, "groupId"), get_text(d, "artifactId"),
                          get_text(d, "version"))
            if dg and da:
                scope = get_text(d, "scope") or "compile"
                if scope == "import":
                    # BOM import：把它的 depMgmt 合并进来
                    b_props, b_mgmt, _ = collect_pom_info(
                        sub_props(dg, props), sub_props(da, props),
                        sub_props(dv, props), seen_poms)
                    props.update(b_props)
                    dep_mgmt.update(b_mgmt)
                else:
                    dep_mgmt[(dg, da)] = (dv, get_text(d, "type") or "jar", scope)
    # 自身 dependencies
    ds = root.find("m:dependencies", NS)
    if ds is not None:
        for d in ds.findall("m:dependency", NS):
            dg = get_text(d, "groupId")
            da = get_text(d, "artifactId")
            dv = get_text(d, "version")
            scope = get_text(d, "scope") or "compile"
            optional = get_text(d, "optional") == "true"
            dtype = get_text(d, "type") or "jar"
            excl = set()
            ex = d.find("m:exclusions", NS)
            if ex is not None:
                for e in ex.findall("m:exclusion", NS):
                    excl.add((get_text(e, "groupId"), get_text(e, "artifactId")))
            if dg and da:
                deps.append({"g": dg, "a": da, "v": dv, "scope": scope,
                             "optional": optional, "type": dtype, "exclusions": excl})
    return props, dep_mgmt, deps

def resolve_artifact(g, a, v, scope="compile", queue=None, seen=None, excluded=None):
    if queue is None:
        queue, seen, excluded = [], set(), set()
    key = (g, a, v)
    if key in seen:
        return queue
    seen.add(key)
    queue.append((g, a, v, scope))
    props, dep_mgmt, deps = collect_pom_info(g, a, v)
    # 内置属性
    props.setdefault("project.version", v)
    for d in deps:
        if d["optional"] or d["scope"] in ("test", "provided", "system"):
            continue
        if d["type"] not in ("jar", "bundle"):
            continue
        dg = sub_props(d["g"], props)
        da = sub_props(d["a"], props)
        dv = d["v"]
        if dv is None:
            mgmt = dep_mgmt.get((dg, da))
            if mgmt:
                dv = mgmt[0]
        if dv is None:
            print(f"  WARN: 无版本 {dg}:{da} (from {g}:{a}:{v})，跳过", flush=True)
            continue
        dv = sub_props(dv, props)
        if dv is None or "${" in dv:
            print(f"  WARN: 版本未解析 {dg}:{da}:{dv}，跳过", flush=True)
            continue
        if (dg, da) in (excluded or set()) or (dg, da) in d["exclusions"]:
            continue
        new_excl = set(excluded or set()) | d["exclusions"]
        # 传递依赖 scope 降级规则简化：runtime->runtime/compile 保留，其它按 compile 处理
        child_scope = "runtime" if scope == "runtime" and d["scope"] == "runtime" else "compile"
        if d["scope"] == "runtime":
            child_scope = "runtime"
        resolve_artifact(dg, da, dv, child_scope, queue, seen, new_excl)
    return queue

def fetch_jar(g, a, v):
    dest = gav_path(g, a, v, "jar")
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        return True
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    for repo in REPOS:
        url = f"{repo}/{g.replace('.', '/')}/{a}/{v}/{a}-{v}.jar"
        tmp = dest + ".tmp"
        if curl_fetch(url, tmp):
            os.rename(tmp, dest)
            return True
    if os.path.exists(dest + ".tmp"):
        os.remove(dest + ".tmp")
    return False

def main():
    roots = []
    for arg in sys.argv[1:]:
        g, a, v = arg.split(":")
        roots.append((g, a, v))
    all_artifacts = []
    seen = set()
    for g, a, v in roots:
        print(f"解析 {g}:{a}:{v} ...", flush=True)
        for item in resolve_artifact(g, a, v, "compile", [], seen, set()):
            if item[:3] not in [x[:3] for x in all_artifacts]:
                all_artifacts.append(item)
    print(f"共 {len(all_artifacts)} 个构件，开始下载 jar ...", flush=True)
    ok, fail = 0, []
    for g, a, v, scope in all_artifacts:
        if fetch_jar(g, a, v):
            ok += 1
        else:
            fail.append(f"{g}:{a}:{v}")
    print(f"下载完成：成功 {ok}，失败 {len(fail)}", flush=True)
    for f in fail:
        print("  FAILED: " + f, flush=True)
    # 输出 classpath 文件
    cp = ":".join(gav_path(g, a, v, "jar") for g, a, v, s in all_artifacts)
    open("/tmp/cp.txt", "w").write(cp)
    print("classpath 已写入 /tmp/cp.txt", flush=True)

if __name__ == "__main__":
    main()
