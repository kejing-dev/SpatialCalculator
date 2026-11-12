import json, os, html
from PIL import Image
B = "/Users/bytedance/Desktop/Spatial Apps/spatial-app-factory/apps/spatial-calc/build"
OUT = os.path.join(B, "simplify"); os.makedirs(OUT, exist_ok=True)
def crop(src, name):
    im = Image.open(src).convert("RGB")
    if im.size[1] < 2000:  # vibelab jpg 2560x1440: window centre region
        box = (880, 0, 1700, 1000)
    else:
        box = (760, 690, 1860, 1830)
    im = im.crop(box); im.thumbnail((620, 700)); im.save(os.path.join(OUT, name), quality=85)
    return "simplify/" + name
def after_count(key, lang):
    p = os.path.join(B, "shots-v012", f"{key}-{lang}.controls.json")
    try: d = json.load(open(p)); return len(d["controls"]), [c.get("name") or "(slider)" for c in d["controls"]]
    except Exception: return None, []
# (title, before shot, before count, before list, after key, body in room?, note)
R2 = os.path.join(B, "shots-r2")
rows = [
 ("首启 S01", "S01-welcome", 5, "3 页签 · 跳过 · 开始", "S01-welcome", 0, "[跳过] 和 [开始] 做同一件事，只留 [开始]"),
 ("选活 S02", "S02-pick", 12, "3 页签 · 6 个活 · 饰条 量一下 / 拟合盒 / 清空", "S02-pick", 0, "饰条这页不显示；去掉副标题和「量到的数自己进算式」；六块加大成两行"),
 ("算式 · 量完三边 S03", "S03-tank-paste-after", 16, "3 页签 · 方缸/圆缸 2 · 音量 · 3 尺寸块 · 结果行 · 单位 L · = / 打数 / 存卡 · 饰条 3", "S03-tank-filled", 0, "结果放大成唯一主角（显示屏框），三个尺寸块合成一条可点的算式条，主按钮只剩「= 摆出来」，其余进 ⋯"),
 ("算式 · 结果体摆出来 S08", "S08-tank", 18, "上一行 + 房间里的 1:1 水块", "S08-tank-body", 1, "主按钮变「存卡」；拖它 / 摇杆转的提示第一次出现一次（之后在 ⋯ → 怎么量）"),
 ("装箱 6 槽", "S03-fit-filled", 19, "3 页签 · 音量 · 6 尺寸块 · 结果行 · 单位 · 3 钮 · 饰条 3（不含体）", "S03-fit-empty", 0, "6 个尺寸块合成一条；饰条只剩「拟合盒」（主按钮已是「量格宽」）"),
 ("存卡 S12", "S12-saved", 19, "3 页签 · 方缸/圆缸 2 · 音量 · 3 尺寸块 · 结果行 · 单位 · 钉在这里 / 只存 / 存成模板 / 下一个 · 饰条 3（置灰）· 体", "S12-saved", 1, "只留 钉在这里 / 只存 / 下一个；「存成模板」进 ⋯ 和记录卡的 ⋯"),
 ("清空确认 S23", "S23-clear", 13, "3 页签 · 方缸/圆缸 2 · 音量 · 3 尺寸块 · 结果行 · 取消 / 清掉 · 饰条 3（置灰）", "S23-clear-ask", 1, "确认时只有 取消 / 清掉"),
 ("打数 S07（键盘）", "S07-typing-paste", 37, "3 页签 · 3 尺寸块 · 单位 4 · 粘贴 · 收起 · 22 键 · 饰条 3", "S07-typing", 0, "键盘页本身是键盘，> 7 有理由；饰条不再显示"),
 ("记录 · 空", "S13-records-empty", 8, "3 页签 · 新建 · 三块「接着量一个」· 量一下", "S13-records-empty", 0, "只留一个主按钮 [量一下]"),
 ("记录 · 1 张", "S13-records-1card", 12, "3 页签 · 新建 · 重放实体 / 取下 / 带到面前 / 存成模板 / 删 · 三块「接着量一个」", "S13-records-1card", 0, "卡上只留 [重放实体] [⋯ 更多]（钉住/取下 · 带到面前 · 存成模板 · 删 进 ⋯）；底下一个主按钮「接着量一个」；去掉卡上的免责说明"),
 ("记录 · 2 张", "S13-records-2cards", 13, "上一行 + 第二张卡", "S13-records-2cards", 0, ""),
 ("设置 S15", "S15-settings", 19, "3 页签 · 语言 3 · 单位 2 · 透明度 3 · 音量条 · 静音 · 吸附 · 桌高 −/+ · 重开面板 · 退出", "S15-settings", 0, "首屏：语言（点一下换下一个）· 单位 · 声音（静音 + 音量条）· 更多设置 · 退出应用；透明度 / 吸附 / 桌高 / 重开面板 / 关于进「更多设置」"),
 ("退出确认 S16", "S16-exit", 19, "设置页全部 + 取消 / 退出（替换重开 / 退出）", "S16-exit", 0, ""),
]
new_only = [
 ("新：⋯ 更多（结果体在时）", "S08-more", 1, "次要项都在这里：打数 · 量一下 · 拟合盒 · 单位 · 换成圆缸 · 带到面前 · 收起实体 · 声音 · 怎么量 · 清空。菜单本身是清单，> 7 有理由"),
 ("新：算式条点开（改数）", "S03-chip-edit", 1, "点算式条才把尺寸块变成按钮 + 改数 / 重量 / 删 + ✓ 收起；收起后回到一条"),
 ("新：首次提示", "S03-tank-first-tip", 0, "说明文字只在第一次选这个活时出现一次，10 秒后自己收起；之后从 ⋯ → 怎么量 再看"),
 ("新：瞄准 S04", "S04-aim", 0, "量的时候只有一句步骤 + 打数 / 取消"),
 ("新：记录卡 ⋯（真实点按）", "S13-records-cardmenu", 0, "钉住/取下 · 带到面前 · 存成模板 · 删 · 收起"),
 ("新：更多设置", "S15-settings-more", 0, "透明度（点一下换）· 吸附已有钉 · 虚拟桌高 −/+ · 重开面板 · 关于"),
]
css = """body{font-family:-apple-system,'PingFang SC',sans-serif;margin:24px;color:#1b1f24;background:#f6f5f2}h1{font-size:22px}h2{font-size:17px;margin:28px 0 8px}
table{border-collapse:collapse;width:100%;font-size:13px;background:#fff}td,th{border:1px solid #ddd;padding:6px 8px;text-align:left;vertical-align:top}
th{background:#eceae4}.ok{color:#256a61;font-weight:600}.over{color:#b5441f;font-weight:600}.pair{display:flex;gap:12px;align-items:flex-start;flex-wrap:wrap}
.pair figure{margin:0;background:#fff;padding:8px;border:1px solid #ddd}.pair img{width:300px;display:block}figcaption{font-size:12px;color:#3a3f45;margin-top:4px;max-width:300px}.note{font-size:13px;color:#3a3f45}"""
H = [f"<!DOCTYPE html><html lang='zh'><head><meta charset='utf-8'><title>空间计算器 v0.1.1 → v0.1.2 界面精简 before / after</title><style>{css}</style></head><body>"]
H.append("<h1>空间计算器 v0.1.1 → v0.1.2 · 界面精简（before / after）</h1>")
H.append("<p class='note'>用户 2026-11-12 真机看 v0.1.1：「空间计算器我觉得界面还需要简洁一点，现在有点太乱」。规矩 profile B31：每屏一个视觉主角 + 一个主按钮；可点控件（含顶部 3 个页签、底部饰条、房间里可点的结果体）≤ 7；次要项收进「更多」或设置；说明文字不常驻；面板和饰条不放重复按钮。功能一个没删，都收进 ⋯ / 更多设置。<br>before = v0.1.1 研发第 2 轮截图（shots-r2，按代码逐个数）；after = v0.1.2 模拟器 emulator-5560 截图（shots-v012），计数取自 <code>pico-cli interact query</code> 的可点控件数，房间里有结果体时 +1。</p>")
H.append("<h2>每屏可点控件计数</h2><table><tr><th>屏</th><th>before v0.1.1</th><th>after v0.1.2 中</th><th>after v0.1.2 英</th><th>改了什么</th></tr>")
def cnt(key, body, lang):
    n, names = after_count(key, lang)
    if n is None: return "—", []
    n += body
    return n, names
for t, bshot, bn, blist, akey, body, note in rows:
    nz, names = cnt(akey, body, "zh"); ne, _ = cnt(akey, body, "en")
    cls = lambda n: "ok" if isinstance(n, int) and n <= 7 else "over"
    H.append(f"<tr><td>{t}</td><td><span class='over' title='{html.escape(blist)}'>{bn}</span><br><span class='note'>{html.escape(blist)}</span></td>"
             f"<td><span class='{cls(nz)}'>{nz}</span><br><span class='note'>{html.escape(' · '.join(names))}{' · 房间里的结果体' if body else ''}</span></td><td><span class='{cls(ne)}'>{ne}</span></td><td class='note'>{html.escape(note)}</td></tr>")
for t, akey, body, note in new_only:
    nz, names = cnt(akey, body, "zh"); ne, _ = cnt(akey, body, "en")
    H.append(f"<tr><td>{t}</td><td>—</td><td>{nz}<br><span class='note'>{html.escape(' · '.join(names))}</span></td><td>{ne}</td><td class='note'>{html.escape(note)}</td></tr>")
H.append("</table><p class='note'>&gt; 7 的屏（理由写在 ui-audit §14）：打数键盘页（键盘本身）、⋯ 更多菜单、算式条点开后的改数状态（临时编辑态）、选活页（6 个活就是这页的内容，+ 3 页签 = 9）、设置首屏 9 / 更多设置 9 / 退出确认 10（设置是选项清单；静音按 D15 必须一点即达）。</p>")
H.append("<h2>每屏截图对比（中文）</h2>")
for t, bshot, bn, blist, akey, body, note in rows:
    bsrc = os.path.join(R2, bshot + "-zh.png")
    asrc = os.path.join(B, "shots-v012", akey + "-zh.png")
    if not os.path.exists(bsrc) or not os.path.exists(asrc): continue
    b = crop(bsrc, f"before-{bshot}-zh.jpg"); a = crop(asrc, f"after-{akey}-zh.jpg")
    nz, _ = cnt(akey, body, "zh")
    H.append(f"<h2>{t}</h2><div class='pair'><figure><img src='{b}'><figcaption>before v0.1.1 · {bn} 个可点</figcaption></figure><figure><img src='{a}'><figcaption>after v0.1.2 · {nz} 个可点 · {html.escape(note)}</figcaption></figure></div>")
H.append("<h2>新出现的状态（中文）</h2><div class='pair'>")
for t, akey, body, note in new_only:
    a = crop(os.path.join(B, "shots-v012", akey + "-zh.png"), f"after-{akey}-zh.jpg")
    H.append(f"<figure><img src='{a}'><figcaption>{t} · {html.escape(note)}</figcaption></figure>")
H.append("</div><h2>English（after，同一组状态）</h2><div class='pair'>")
for t, bshot, bn, blist, akey, body, note in rows + [(x[0], None, None, None, x[1], x[2], x[3]) for x in new_only]:
    p = os.path.join(B, "shots-v012", akey + "-en.png")
    if os.path.exists(p):
        a = crop(p, f"after-{akey}-en.jpg"); ne, _ = cnt(akey, body, "en")
        H.append(f"<figure><img src='{a}'><figcaption>{t} · {ne} tappables</figcaption></figure>")
H.append("</div><h2>English before（v0.1.1，有的几张）</h2><div class='pair'>")
for k in ["S01-welcome", "S03-tile-filled", "S03-fit-filled", "S07-typing-paste", "S13-records-empty", "S13-records-1card", "S13-records-2cards", "S15-settings", "S16-exit"]:
    p = os.path.join(R2, k + "-en.png")
    if os.path.exists(p):
        H.append(f"<figure><img src='{crop(p, f'before-{k}-en.jpg')}'><figcaption>before · {k}</figcaption></figure>")
H.append("</div></body></html>")
open(os.path.join(B, "simplify-before-after.html"), "w").write("\n".join(H))
print("ok")
