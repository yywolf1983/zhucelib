"""RegGate 注册机 GUI（Python / Tkinter）。

与 Android 端 keygen-app 功能对齐:
  - 从本地文件加载 RSA 私钥 (PKCS#8 / PKCS#1, PEM 或 DER)
  - 粘贴安装码, 解析出设备ID 与 包名
  - 指定有效天数 (0 = 永久)
  - 生成带包绑定的激活码并支持复制
"""

from __future__ import annotations

import os
from datetime import datetime
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

import reggate
import records

# ===== 配色 (现代、统一) =====
PRIMARY       = "#2563EB"   # 主蓝
PRIMARY_DARK  = "#1D4ED8"   # 主蓝(按下)
PRIMARY_LIGHT = "#DBEAFE"   # 主蓝(浅底)
BG            = "#F1F5F9"   # 主背景 (slate-100)
CARD          = "#FFFFFF"   # 卡片/表面
TEXT          = "#0F172A"   # 主文字 (slate-900)
DARK          = TEXT
MUTED         = "#64748B"   # 次要文字 (slate-500)
BORDER        = "#E2E8F0"   # 边框 (slate-200)
SUCCESS       = "#059669"   # 绿 (emerald)
DANGER        = "#DC2626"   # 红
FIELD_BG      = "#FFFFFF"
CODE_BG       = "#F8FAFC"

# 辅助色
PANEL       = "#0F172A"   # 深色面板/页脚
PANEL_FG    = "#E2E8F0"
HEADER_FG   = "#BFDBFE"   # 标题栏副标题
CODE_FG     = "#1D4ED8"   # 激活码文字
CODE_BOX    = "#EFF6FF"   # 激活码背景 (blue-50)
PKG_FG      = "#7E22CE"   # 包名 (紫)
DURATION_FG = "#EA580C"   # 购买时长 (橙)
CARD_HDR    = "#EEF2FF"   # 设备卡头部 (indigo-50)
BULLET      = "#CBD5E1"   # 圆点


class KeygenApp:
    def __init__(self, root: tk.Tk) -> None:
        self.root = root
        self.root.title("RegGate 注册机")
        self.root.configure(bg=BG)
        self.root.geometry("620x760")
        self.root.resizable(True, True)

        self.private_key = None
        self.private_key_path = None

        self.config = records.load_config()
        self.records_path = self.config.get("records_path", records.DEFAULT_RECORDS_PATH)
        self.current_pkg = ""
        self.current_dev = ""

        self._setup_style()
        self._build_ui()
        self._refresh_save_location_label()
        self._load_saved_private_key()
        self._refresh_ui_state()  # 必须在自动加载私钥后刷新, 否则按钮保持置灰
        records.migrate_config_remarks(self.records_path)

    # ---------------- 样式 ----------------
    def _setup_style(self) -> None:
        style = ttk.Style()
        try:
            style.theme_use("clam")
        except Exception:
            pass
        style.configure("TFrame", background=BG)
        style.configure("TLabel", background=BG, foreground=TEXT)
        style.configure("Card.TLabelframe", background=CARD, borderwidth=1,
                        relief="solid", bordercolor=BORDER)
        style.configure("Card.TLabelframe.Label", background=BG, foreground=PRIMARY,
                        font=("TkDefaultFont", 11, "bold"))
        style.configure("TButton", padding=(10, 6), font=("TkDefaultFont", 10),
                        background=CARD, foreground=TEXT, borderwidth=1, relief="solid")
        style.map("TButton", background=[("active", "#E2E8F0"), ("disabled", "#E2E8F0")],
                  foreground=[("disabled", "#94A3B8")])
        style.configure("Accent.TButton", background=PRIMARY, foreground="white",
                        borderwidth=0, padding=(14, 9), font=("TkDefaultFont", 11, "bold"))
        style.map("Accent.TButton", background=[("active", PRIMARY_DARK), ("disabled", "#93C5FD")],
                  foreground=[("disabled", PRIMARY_LIGHT)])
        style.configure("Ghost.TButton", background=CARD, foreground=PRIMARY,
                        borderwidth=1, relief="solid", padding=(10, 6),
                        font=("TkDefaultFont", 10))
        style.map("Ghost.TButton", background=[("active", PRIMARY_LIGHT)])
        style.configure("Danger.TButton", background=DANGER, foreground="white",
                        borderwidth=0, padding=(10, 6), font=("TkDefaultFont", 10, "bold"))
        style.map("Danger.TButton", background=[("active", "#B91C1C"), ("disabled", "#FCA5A5")],
                  foreground=[("disabled", "#FEE2E2")])
        style.configure("TEntry", padding=7, fieldbackground=FIELD_BG, foreground=TEXT,
                        borderwidth=1, relief="solid")
        style.configure("TSpinbox", padding=6, fieldbackground=FIELD_BG, foreground=TEXT,
                         borderwidth=1, relief="solid")
        style.configure("Link.TLabel", foreground=PRIMARY, background=BG,
                        font=("TkDefaultFont", 9))

    # ---------------- UI ----------------
    def _section(self, parent: ttk.Frame, title: str) -> ttk.Frame:
        lf = ttk.LabelFrame(parent, text=title, style="Card.TLabelframe", padding=(14, 10))
        lf.pack(fill=tk.X, pady=(0, 10))
        return lf

    def _divider(self, parent: ttk.Frame) -> None:
        sep = ttk.Separator(parent, orient="horizontal")
        sep.pack(fill=tk.X, pady=8)

    def _build_ui(self) -> None:
        # 顶部标题栏
        header = tk.Frame(self.root, bg=PRIMARY, height=66)
        header.pack(fill=tk.X)
        tk.Label(header, text="RegGate 注册机", bg=PRIMARY, fg="white",
                 font=("TkDefaultFont", 18, "bold")).pack(side=tk.LEFT, padx=22, pady=12)
        tk.Label(header, text="激活码生成工具", bg=PRIMARY, fg=HEADER_FG,
                 font=("TkDefaultFont", 10)).pack(side=tk.LEFT, padx=4, pady=16)

        # 主体（窗口可缩放, 内容随窗口填充）
        body = ttk.Frame(self.root, style="TFrame")
        body.pack(fill=tk.BOTH, expand=True)
        inner = ttk.Frame(body, style="TFrame", padding=(20, 14))
        inner.pack(fill=tk.BOTH, expand=True)

        # —— 密钥与存储 ——
        sec = self._section(inner, "密钥与存储")
        key_row = ttk.Frame(sec, style="TFrame")
        key_row.pack(fill=tk.X, pady=(0, 10))
        ttk.Button(key_row, text="选择私钥…", command=self._select_private_key,
                   style="Ghost.TButton").pack(side=tk.LEFT)
        self.key_status = ttk.Label(key_row, text="未加载私钥", foreground=MUTED)
        self.key_status.pack(side=tk.LEFT, padx=12)

        self._divider(sec)
        save_row = ttk.Frame(sec, style="TFrame")
        save_row.pack(fill=tk.X)
        ttk.Button(save_row, text="选择目录…", command=self._choose_save_location,
                   style="Ghost.TButton").pack(side=tk.LEFT)
        ttk.Button(save_row, text="查看记录",
                   command=lambda: self._view_records(
                       pkg_highlight=self.current_pkg or None,
                       device_highlight=self.current_dev or None),
                   style="Ghost.TButton").pack(side=tk.LEFT, padx=8)

        self.save_label = ttk.Label(sec, text="-", foreground=MUTED,
                                    font=("TkDefaultFont", 9), wraplength=520)
        self.save_label.pack(anchor=tk.W, pady=(8, 0))

        # —— 安装码 ——
        sec = self._section(inner, "安装码请求")
        req_row = ttk.Frame(sec, style="TFrame")
        req_row.pack(fill=tk.X)
        ttk.Button(req_row, text="粘贴", command=self._paste_request,
                   style="Ghost.TButton").pack(side=tk.LEFT)
        self.request_var = tk.StringVar()
        self.request_entry = ttk.Entry(req_row, textvariable=self.request_var,
                                       font=("Courier", 11))
        self.request_entry.pack(side=tk.LEFT, fill=tk.X, expand=True, padx=(10, 0))
        self.request_entry.bind("<KeyRelease>", lambda _e: self._on_request_changed())

        self._divider(sec)
        info_row = ttk.Frame(sec, style="TFrame")
        info_row.pack(fill=tk.X)
        ttk.Label(info_row, text="设备 ID", foreground=MUTED).grid(row=0, column=0, sticky=tk.W, padx=(0, 12))
        self.device_id_label = ttk.Label(info_row, text="-", font=("Courier", 10),
                                          foreground=PRIMARY, cursor="hand2")
        self.device_id_label.grid(row=0, column=1, sticky=tk.W)
        self.device_id_label.bind("<Button-1>", lambda _e: self._copy_device_id())
        ttk.Label(info_row, text="包名", foreground=MUTED).grid(row=1, column=0, sticky=tk.W, padx=(0, 12), pady=(6, 0))
        self.pkg_label = ttk.Label(info_row, text="-", foreground=PKG_FG,
                                   font=("TkDefaultFont", 10, "bold"), cursor="hand2")
        self.pkg_label.grid(row=1, column=1, sticky=tk.W, pady=(6, 0))
        self.pkg_label.bind("<Button-1>", lambda _e: self._copy_pkg())

        self.device_hint = ttk.Label(sec, text="", foreground=MUTED,
                                      font=("TkDefaultFont", 9), cursor="")
        self.device_hint.pack(anchor=tk.W, pady=(8, 0))

        # —— 有效天数 ——
        sec = self._section(inner, "有效期")
        days_row = ttk.Frame(sec, style="TFrame")
        days_row.pack(fill=tk.X)
        self.days_var = tk.StringVar(value="365")
        self.days_spin = ttk.Spinbox(days_row, from_=0, to=36500, increment=30,
                                     textvariable=self.days_var, width=12)
        self.days_spin.pack(side=tk.LEFT)
        ttk.Label(days_row, text="天（0 = 永久）", foreground=MUTED).pack(side=tk.LEFT, padx=12)

        # —— 生成按钮 ——
        self.generate_btn = ttk.Button(inner, text="生成激活码", command=self._generate,
                                       style="Accent.TButton")
        self.generate_btn.pack(fill=tk.X, pady=(0, 14))

        # —— 激活码输出 ——
        sec = self._section(inner, "激活码")
        self.activation_text = tk.Text(sec, height=3, wrap="word", font=("Courier", 10),
                                       bg=CODE_BG, fg=TEXT, insertbackground=TEXT,
                                       relief="solid", bd=1, state="disabled",
                                       padx=10, pady=8)
        self.activation_text.pack(fill=tk.X)
        out_row = ttk.Frame(sec, style="TFrame")
        out_row.pack(fill=tk.X, pady=(8, 0))
        ttk.Button(out_row, text="复制激活码", command=self._copy_activation,
                   style="Ghost.TButton").pack(side=tk.LEFT)
        self.expiry_label = ttk.Label(out_row, text="", foreground=PRIMARY)
        self.expiry_label.pack(side=tk.LEFT, padx=14)

        # —— 底部状态栏 ——
        self.status_var = tk.StringVar(value="就绪")
        footer = tk.Frame(self.root, bg=PANEL, height=26)
        footer.pack(side=tk.BOTTOM, fill=tk.X)
        tk.Label(footer, textvariable=self.status_var, bg=PANEL, fg=PANEL_FG,
                 font=("TkDefaultFont", 9), anchor=tk.W, padx=12).pack(fill=tk.X)

    # ---------------- 逻辑 ----------------
    def _select_private_key(self) -> None:
        init = self.config.get("last_dir")
        path = filedialog.askopenfilename(
            title="选择私钥文件",
            initialdir=init if init else None,
            filetypes=[("密钥文件", "*.pem *.der *.key"), ("所有文件", "*.*")],
        )
        if not path:
            return
        self.config["last_dir"] = os.path.dirname(os.path.abspath(path))
        self.config["private_key_path"] = path
        records.save_config(self.config)
        try:
            with open(path, "r", encoding="utf-8") as fh:
                content = fh.read()
        except UnicodeDecodeError:
            with open(path, "rb") as fh:
                content = fh.read().decode("latin-1")
        try:
            self.private_key = reggate.parse_private_key(content)
        except Exception as exc:  # noqa: BLE001
            self.private_key = None
            self.private_key_path = None
            messagebox.showerror("私钥加载失败", str(exc))
            self._refresh_ui_state()
            return
        self.private_key_path = path
        self.key_status.config(text="已加载: " + os.path.basename(path), foreground=SUCCESS)
        self.status_var.set("私钥加载成功")
        self._refresh_ui_state()
        self._on_request_changed()

    def _load_saved_private_key(self) -> None:
        """启动时若配置中已记录私钥路径则自动加载，免去每次选择。"""
        path = self.config.get("private_key_path")
        if not path or not os.path.exists(path):
            return
        try:
            with open(path, "r", encoding="utf-8") as fh:
                content = fh.read()
        except UnicodeDecodeError:
            with open(path, "rb") as fh:
                content = fh.read().decode("latin-1")
        except OSError:
            return
        try:
            self.private_key = reggate.parse_private_key(content)
        except Exception:  # noqa: BLE001
            return
        self.private_key_path = path
        self.key_status.config(text="已加载: " + os.path.basename(path), foreground=SUCCESS)

    def _paste_request(self) -> None:
        try:
            clip = self.root.clipboard_get()
        except tk.TclError:
            clip = ""
        if clip:
            self.request_var.set(clip.strip())
            self._on_request_changed()

    def _on_request_changed(self) -> None:
        raw = self.request_var.get().strip()
        if not raw:
            self.device_id_label.config(text="-", foreground=TEXT)
            self.pkg_label.config(text="-", foreground=TEXT)
            self._update_device_hint(None)
            return
        ungrouped = reggate.Base32.ungroup(raw)
        parsed = reggate.parse_request_code(ungrouped)
        if parsed is None:
            self.device_id_label.config(text="解析失败", foreground=DANGER)
            self.pkg_label.config(text="-", foreground=TEXT)
            self._update_device_hint(None)
            return
        device_id, _nonce, pkg_bytes = parsed
        dev_hex = device_id.hex().upper()
        current_pkg = pkg_bytes.decode("utf-8") if pkg_bytes else ""
        self.device_id_label.config(text=dev_hex, foreground=TEXT)
        if pkg_bytes:
            self.pkg_label.config(text=current_pkg, foreground=PKG_FG)
        else:
            self.pkg_label.config(text="(无)", foreground=MUTED)
        self._update_device_hint(dev_hex, current_pkg)

    def _update_device_hint(self, device_id: str, current_pkg: str = "") -> None:
        """粘贴安装码后只做展示 (不新增记录):
        已存在 -> 内联展示该设备最近一条记录概览, 可点击查看全部 (并高亮当前包名);
        不存在 -> 提示生成激活码时才会新增。"""
        self.current_pkg = current_pkg
        self.current_dev = device_id or ""
        if not device_id:
            self.device_hint.config(text="", foreground=MUTED, cursor="")
            try:
                self.device_hint.unbind("<Button-1>")
            except Exception:
                pass
            return
        recs = [r for r in records.RecordStore(self.records_path).load()
                if r.get("deviceId") == device_id]
        if recs:
            # 取当前注册码包名对应的注册信息
            pkg_recs = [r for r in recs if (r.get("packageName") or "") == current_pkg] if current_pkg else []
            if pkg_recs:
                latest = max(pkg_recs, key=lambda r: r.get("id", 0))
                dur = latest.get("validDays", 0)
                dur_txt = "永久" if dur == 0 else f"{dur} 天"
                info = f"{current_pkg} · {dur_txt} · 到期 {latest.get('expiryDate', '')}"
                self.device_hint.config(
                    text=f"✓ 已注册 · {info} · 点击查看",
                    foreground=SUCCESS, cursor="hand2")
            else:
                self.device_hint.config(
                    text=f"✓ 该设备已存在 (共 {len(recs)} 条) · 当前包未注册 · 点击查看",
                    foreground=SUCCESS, cursor="hand2")
            self.device_hint.bind("<Button-1>",
                                  lambda _e: self._view_records(
                                      device_filter=device_id,
                                      pkg_highlight=self.current_pkg or None,
                                      device_highlight=device_id))
        else:
            self.device_hint.config(text="＋ 新设备 · 生成激活码时将自动新增记录",
                                    foreground=MUTED, cursor="")
            try:
                self.device_hint.unbind("<Button-1>")
            except Exception:
                pass

    def _generate(self) -> None:
        if self.private_key is None:
            messagebox.showwarning("缺少私钥", "请先选择私钥文件")
            return
        raw = self.request_var.get().strip()
        if not raw:
            messagebox.showwarning("缺少安装码", "请先填写安装码")
            return
        try:
            days = int(self.days_var.get())
            if days < 0:
                raise ValueError("天数不能为负")
        except ValueError:
            messagebox.showwarning("无效天数", "有效天数必须是整数")
            return

        ungrouped = reggate.Base32.ungroup(raw)
        parsed = reggate.parse_request_code(ungrouped)
        if parsed is None:
            messagebox.showerror("生成失败", "安装码格式错误")
            return
        device_id, _nonce, pkg_bytes = parsed
        pkg = pkg_bytes.decode("utf-8") if pkg_bytes else ""

        try:
            code = reggate.generate_activation_code(ungrouped, days, self.private_key)
        except ValueError as exc:
            messagebox.showerror("生成失败", str(exc))
            return
        except Exception as exc:  # noqa: BLE001
            messagebox.showerror("生成失败", str(exc))
            return

        expiry = reggate.format_expiry(days)
        rec = records.build_record(device_id, ungrouped, pkg, days, expiry, code)
        store = records.RecordStore(self.records_path)
        total = store.upsert_by_request_code(rec)

        self.activation_text.configure(state="normal")
        self.activation_text.delete(1.0, tk.END)
        self.activation_text.insert(1.0, code)
        self.activation_text.configure(state="disabled")
        self.expiry_label.config(text="到期: " + expiry)
        self.status_var.set(f"激活码已生成并保存 (共 {total} 条记录)")
        self._refresh_ui_state()

    def _copy_activation(self) -> None:
        code = self.activation_text.get(1.0, "end-1c").strip()
        if not code:
            return
        self.root.clipboard_clear()
        self.root.clipboard_append(code)
        self.status_var.set("激活码已复制到剪贴板")

    def _refresh_ui_state(self) -> None:
        has_key = self.private_key is not None
        self.generate_btn.config(state=tk.NORMAL if has_key else tk.DISABLED)

    # ---------------- 记录保存位置 ----------------
    def _choose_save_location(self) -> None:
        init = self.config.get("last_dir")
        dir_path = filedialog.askdirectory(title="选择记录保存目录",
                                            initialdir=init if init else None)
        if not dir_path:
            return
        # 固定文件名 reg_records.json 落在所选目录内
        self.records_path = os.path.abspath(os.path.join(dir_path, "reg_records.json"))
        self.config["records_path"] = self.records_path
        self.config["last_dir"] = os.path.abspath(dir_path)
        records.save_config(self.config)

        store = records.RecordStore(self.records_path)
        if os.path.exists(self.records_path):
            count = store.count()
            self.status_var.set(f"已选择目录, 将向现有 {count} 条记录新增")
        else:
            store.save_all([])  # 无 json 才重建空文件
            self.status_var.set("已选择目录, 新建记录文件")
        self._refresh_save_location_label()

    def _view_records(self, device_filter=None, pkg_highlight=None,
                      device_highlight=None) -> None:
        filt = [device_filter] if device_filter else None
        RecordsViewer(self.root, self.records_path, self._refresh_save_location_label,
                      filt, pkg_highlight=pkg_highlight, device_highlight=device_highlight)

    def _copy_device_id(self) -> None:
        txt = self.device_id_label.cget("text")
        if not txt or txt in ("-", "解析失败"):
            return
        self.root.clipboard_clear()
        self.root.clipboard_append(txt)
        self.status_var.set("设备ID已复制到剪贴板")

    def _copy_pkg(self) -> None:
        txt = self.pkg_label.cget("text")
        if not txt or txt in ("-", "(无)"):
            return
        self.root.clipboard_clear()
        self.root.clipboard_append(txt)
        self.status_var.set("包名已复制到剪贴板")

    def _refresh_save_location_label(self) -> None:
        try:
            count = records.RecordStore(self.records_path).count()
        except Exception:
            count = 0
        self.save_label.config(text=f"{self.records_path}  (共 {count} 条)")


class RecordsViewer(tk.Toplevel):
    """查看记录窗口：按 设备 → 包 分组，与 Android RecordsActivity 逻辑一致。
    - 首页以设备为单位，点击设备 ID 头部可展开/收起（首页id 进入查看）；
    - 点击记录行打开详情对话框（支持详情查看）；
    - 详情对话框含激活码展开/收起、删除此条；设备卡片可删除该设备全部。
    """

    def __init__(self, parent, records_path: str, on_changed=None, device_filter=None,
                 pkg_highlight=None, device_highlight=None) -> None:
        super().__init__(parent)
        self.records_path = records_path
        self.on_changed = on_changed
        self.device_filter = set(device_filter) if device_filter else None
        self.pkg_highlight = pkg_highlight or ""
        self.device_highlight = device_highlight or ""
        self.title("查看记录" if not self.device_filter else "查询记录")
        self.configure(bg=BG)
        self.geometry("720x600")
        self.resizable(True, True)
        self.transient(parent)

        header = tk.Frame(self, bg=PRIMARY, height=50)
        header.pack(fill=tk.X)
        tk.Label(header, text="注册记录", bg=PRIMARY, fg="white",
                 font=("TkDefaultFont", 15, "bold")).pack(side=tk.LEFT, padx=20, pady=9)
        self.summary = tk.Label(header, text="", bg=PRIMARY, fg=HEADER_FG,
                                font=("TkDefaultFont", 10))
        self.summary.pack(side=tk.LEFT, padx=10, pady=12)
        ttk.Button(header, text="刷新", command=self._refresh,
                   style="Ghost.TButton").pack(side=tk.RIGHT, padx=16, pady=8)

        # 按ID查询（设备ID / 记录ID）—— 放在查看记录窗口内
        search_bar = tk.Frame(self, bg=BG)
        search_bar.pack(fill=tk.X, padx=16, pady=(6, 2))
        tk.Label(search_bar, text="按ID查询", bg=BG, fg=MUTED,
                 font=("TkDefaultFont", 10)).pack(side=tk.LEFT)
        self.search_var = tk.StringVar()
        ttk.Entry(search_bar, textvariable=self.search_var, font=("Courier", 10),
                  width=22).pack(side=tk.LEFT, padx=8)
        ttk.Button(search_bar, text="粘贴", command=self._paste_search,
                   style="Ghost.TButton").pack(side=tk.LEFT)
        ttk.Button(search_bar, text="查询", command=self._apply_search,
                   style="Ghost.TButton").pack(side=tk.LEFT, padx=(6, 0))
        ttk.Button(search_bar, text="清除", command=self._clear_search,
                   style="Ghost.TButton").pack(side=tk.LEFT, padx=6)

        canvas = tk.Canvas(self, bg=BG, highlightthickness=0)
        scroll = ttk.Scrollbar(self, orient="vertical", command=canvas.yview)
        canvas.configure(yscrollcommand=scroll.set)
        canvas.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        scroll.pack(side=tk.RIGHT, fill=tk.Y)
        self.content = ttk.Frame(canvas, style="TFrame", padding=(16, 14))
        canvas.create_window((0, 0), window=self.content, anchor="nw")
        self.content.bind("<Configure>", lambda e: canvas.configure(scrollregion=canvas.bbox("all")))

        self._refresh()

    @staticmethod
    def _group(recs: list[dict]) -> list[tuple[str, dict]]:
        dev_map: dict[str, dict] = {}
        order: list[str] = []
        for r in sorted(recs, key=lambda x: x.get("id", 0), reverse=True):
            dev = r.get("deviceId") or "(未知设备)"
            pkg = r.get("packageName") or "(未指定)"
            if dev not in dev_map:
                dev_map[dev] = {}
                order.append(dev)
            dev_map[dev].setdefault(pkg, []).append(r)
        return [(d, dev_map[d]) for d in order]

    @staticmethod
    def _bind_recursive(widget, event: str, callback) -> None:
        """递归绑定：Tk 中点击子控件不会冒泡到父 Frame，需逐层绑定。"""
        try:
            widget.bind(event, callback)
        except Exception:
            pass
        for child in widget.winfo_children():
            RecordsViewer._bind_recursive(child, event, callback)

    def _apply_search(self) -> None:
        q = self.search_var.get().strip()
        if not q:
            self.device_filter = None
        else:
            recs = records.RecordStore(self.records_path).load()
            devs = self._matched_devices(recs, q)
            if not devs:
                messagebox.showinfo("未找到", f"未找到与 “{q}” 相关的记录")
                return
            self.device_filter = devs
        self._refresh()

    def _clear_search(self) -> None:
        self.search_var.set("")
        self.device_filter = None
        self._refresh()

    def _paste_search(self) -> None:
        try:
            clip = self.clipboard_get()
        except tk.TclError:
            clip = ""
        if clip:
            self.search_var.set(clip.strip())
            self._apply_search()

    @staticmethod
    def _matched_devices(recs: list[dict], q: str) -> set:
        ql = q.lower()
        devs: set = set()
        for r in recs:
            if (r.get("deviceId") or "").lower() == ql \
                    or (r.get("deviceId") or "").lower().startswith(ql) \
                    or str(r.get("id")) == q:
                devs.add(r.get("deviceId"))
        return devs

    def _refresh(self) -> None:
        for w in self.content.winfo_children():
            w.destroy()
        recs = records.RecordStore(self.records_path).load()
        if self.device_filter is not None:
            recs = [r for r in recs if r.get("deviceId") in self.device_filter]
        grouped = self._group(recs)
        total_pkgs = sum(len(pkgs) for _, pkgs in grouped)
        summary = f"{len(grouped)} 个设备 · {total_pkgs} 个包 · {len(recs)} 条记录"
        if self.device_filter is not None:
            summary = "（按设备筛选）" + summary
        self.summary.config(text=summary)

        if not recs:
            ttk.Label(self.content, text="暂无注册记录", foreground=MUTED).pack(pady=30)
            return

        for dev, pkg_map in grouped:
            self._build_device(dev, pkg_map)

    def _build_device(self, dev: str, pkg_map: dict) -> None:
        """可折叠设备卡片：点击设备 ID 头部展开/收起（首页id 进入查看）。"""
        card = ttk.LabelFrame(self.content, text="", style="Card.TLabelframe",
                              padding=(0, 0))
        card.pack(fill=tk.X, pady=(0, 14))

        total = sum(len(v) for v in pkg_map.values())

        # 可点击的设备头部
        hdr = tk.Frame(card, bg=CARD_HDR, cursor="hand2")
        hdr.pack(fill=tk.X, pady=0)
        arrow = tk.Label(hdr, text="▶", fg=PRIMARY, bg=CARD_HDR,
                         font=("TkDefaultFont", 10, "bold"))
        arrow.pack(side=tk.LEFT, padx=(10, 4), pady=8)
        tk.Label(hdr, text=f"设备 {dev}", fg=DARK, bg=CARD_HDR,
                 font=("Courier", 11, "bold")).pack(side=tk.LEFT, pady=8)
        tk.Label(hdr, text=f"{len(pkg_map)} 个包 · {total} 条",
                 fg=MUTED, bg=CARD_HDR, font=("TkDefaultFont", 9)).pack(side=tk.RIGHT, padx=12, pady=10)

        # 设备备注（如有则始终可见，对齐 Android）
        remark = records.get_device_remark(self.records_path, dev)
        if remark:
            tk.Label(card, text="📝 " + remark, fg=MUTED, bg=BG,
                     font=("TkDefaultFont", 10), wraplength=660, justify=tk.LEFT
                     ).pack(anchor=tk.W, padx=12, pady=(0, 4))

        # 折叠体（默认收起，与 Android 一致）
        body = ttk.Frame(card, style="TFrame", padding=(12, 8))
        expanded = {"state": False}

        def toggle(event=None):
            if expanded["state"]:
                body.pack_forget()
                arrow.config(text="▶")
            else:
                body.pack(fill=tk.X)
                arrow.config(text="▼")
            expanded["state"] = not expanded["state"]

        self._bind_recursive(hdr, "<Button-1>", toggle)

        # 备注编辑入口（始终可点：无备注=添加，有备注=编辑，对齐 Android）
        remark_btn = tk.Label(body, text="添加备注" if not remark else "编辑备注",
                              fg=PRIMARY, bg=CARD, font=("TkDefaultFont", 10), cursor="hand2")
        remark_btn.pack(anchor=tk.E, pady=(2, 6))
        remark_btn.bind("<Button-1>", lambda e, d=dev: self.show_remark_dialog(d))

        for pkg, rec_list in pkg_map.items():
            self._build_pkg(body, pkg, rec_list)

        del_dev = tk.Label(body, text="删除该设备全部记录", fg=DANGER,
                           bg="#FFFFFF", font=("TkDefaultFont", 10), cursor="hand2")
        del_dev.pack(anchor=tk.E, pady=(6, 0))
        del_dev.bind("<Button-1>", lambda e, d=dev: self._confirm_delete_device(d))

    def _build_pkg(self, parent, pkg: str, rec_list: list[dict]) -> None:
        sec = ttk.Frame(parent, style="TFrame")
        sec.pack(fill=tk.X, pady=(4, 2))
        hdr = ttk.Frame(sec, style="TFrame")
        hdr.pack(fill=tk.X)
        # 仅当前安装码所属设备的该包名才高亮 (设备名不同的不亮)
        same_dev = (self.device_highlight == ""
                    or any(r.get("deviceId") == self.device_highlight for r in rec_list))
        highlight = bool(self.pkg_highlight) and pkg == self.pkg_highlight and same_dev
        if highlight:
            tk.Label(hdr, text="●", fg=PKG_FG, bg=BG, font=("TkDefaultFont", 9)).pack(side=tk.LEFT, padx=(0, 6))
            tk.Label(hdr, text=f" {pkg}  ({len(rec_list)} 次)", fg=PKG_FG,
                     bg="#F3E8FF", font=("Courier", 10, "bold")).pack(side=tk.LEFT)
        else:
            tk.Label(hdr, text="●", fg=PKG_FG, bg=BG, font=("TkDefaultFont", 9)).pack(side=tk.LEFT, padx=(0, 6))
            ttk.Label(hdr, text=f"{pkg}  ({len(rec_list)} 次)", font=("Courier", 10)).pack(side=tk.LEFT)
        for r in rec_list:
            self._build_record(sec, r)

    @staticmethod
    def _is_expired(r: dict) -> bool:
        """按到期日判断记录是否已过期（永久 / 空值视为未过期）。"""
        expiry = r.get("expiryDate", "")
        if not expiry or expiry == "永久":
            return False
        try:
            exp_date = datetime.strptime(expiry, "%Y-%m-%d").date()
        except Exception:
            return False
        return exp_date < datetime.now().date()

    def _build_record(self, parent, r: dict) -> None:
        """记录行，整行可点击打开详情（支持详情查看）。过期的记录用红色标注。"""
        expired = self._is_expired(r)
        line_fg = DANGER if expired else TEXT
        row = ttk.Frame(parent, style="TFrame", cursor="hand2")
        row.pack(fill=tk.X, pady=3, padx=(16, 0))
        tk.Label(row, text="•", fg=(DANGER if expired else BULLET), bg=BG,
                 font=("TkDefaultFont", 12)).pack(side=tk.LEFT, padx=(0, 8))
        info = ttk.Frame(row, style="TFrame")
        info.pack(side=tk.LEFT, fill=tk.X, expand=True)
        dur = r.get("validDays", 0)
        dur_txt = "永久" if dur == 0 else f"{dur} 天"
        ttk.Label(info, text=r.get("regAt", ""), font=("TkDefaultFont", 10),
                  foreground=line_fg).pack(anchor=tk.W)
        ttk.Label(info, text=f"{dur_txt} · 到期 {r.get('expiryDate', '')}",
                  foreground=DANGER if expired else SUCCESS,
                  font=("TkDefaultFont", 9)).pack(anchor=tk.W)
        tk.Label(row, text="详情", fg=PRIMARY, bg=BG, font=("TkDefaultFont", 9), cursor="hand2").pack(side=tk.RIGHT, padx=6)

        self._bind_recursive(row, "<Button-1>", lambda e, rec=r: self.show_record_detail(rec))

    def show_record_detail(self, r: dict) -> None:
        """与 Android dialog_record_detail 对齐的详情对话框（直接布局，内容必显示）。"""
        dlg = tk.Toplevel(self)
        dlg.title("注册详情")
        dlg.geometry("420x500")
        dlg.resizable(True, True)
        dlg.configure(bg=BG)
        dlg.transient(self)
        dlg.grab_set()

        outer = ttk.Frame(dlg, style="TFrame", padding=(18, 16))
        outer.pack(fill=tk.BOTH, expand=True)

        tk.Label(outer, text="注册详情", font=("TkDefaultFont", 15, "bold"),
                 fg=DARK, bg=BG).pack(anchor=tk.W, pady=(0, 10))

        content = ttk.Frame(outer, style="TFrame")
        content.pack(fill=tk.BOTH, expand=True)

        def field(label: str, value, color=DARK, mono=False, show=True) -> None:
            if not show or value in (None, ""):
                return
            f = ttk.Frame(content, style="TFrame")
            f.pack(fill=tk.X, pady=4)
            ttk.Label(f, text=f"{label}: ", font=("TkDefaultFont", 11),
                      foreground=MUTED).pack(side=tk.LEFT, anchor=tk.NW)
            ttk.Label(f, text=str(value),
                      font=("Courier", 11) if mono else ("TkDefaultFont", 11),
                      foreground=color, wraplength=320).pack(side=tk.LEFT, anchor=tk.NW)

        reg_at = r.get("regAt", "")
        if len(reg_at) >= 19:
            reg_at = reg_at[:19].replace("T", " ")
        dur = r.get("validDays", 0)
        dur_txt = "永久" if dur == 0 else f"{dur} 天"
        pkg = r.get("packageName", "")

        field("记录ID", r.get("id", ""), mono=True)
        field("设备ID", r.get("deviceId", ""), color=PRIMARY, mono=True)
        field("包名", pkg, color=PKG_FG, mono=True, show=bool(pkg))
        field("注册时间", reg_at)
        field("购买时长", dur_txt, color=DURATION_FG)
        field("到期", r.get("expiryDate", ""), color=SUCCESS)
        dev_remark = r.get("remark", "")
        field("设备备注", dev_remark, color=MUTED, show=bool(dev_remark))

        # 激活码（折叠，对齐 Android dialog_detail_act_toggle）
        act_toggle = tk.Label(content, text="激活码 ▶", fg=MUTED, cursor="hand2",
                              bg=BG, font=("TkDefaultFont", 11))
        act_toggle.pack(anchor=tk.W, pady=(8, 2))
        act_code = tk.Label(content, text=r.get("activationCode", ""), font=("Courier", 10),
                            fg=CODE_FG, bg=CODE_BOX, wraplength=340,
                            justify=tk.LEFT, padx=6, pady=6)
        shown = {"state": False}

        def toggle_act(event=None):
            if shown["state"]:
                act_code.pack_forget()
                act_toggle.config(text="激活码 ▶")
            else:
                act_code.pack(fill=tk.X, pady=2)
                act_toggle.config(text="激活码 ▼")
            shown["state"] = not shown["state"]

        act_toggle.bind("<Button-1>", toggle_act)

        # 底部按钮栏
        bar = ttk.Frame(outer, style="TFrame")
        bar.pack(fill=tk.X, pady=(10, 0))
        ttk.Button(bar, text="删除此条", style="Danger.TButton",
                   command=lambda: self._confirm_delete(r.get("id"), dlg)).pack(side=tk.RIGHT, padx=(6, 0))
        ttk.Button(bar, text="关闭", command=dlg.destroy).pack(side=tk.RIGHT)

    def show_remark_dialog(self, device_id: str) -> None:
        """添加/编辑设备备注（对齐 Android showRemarkDialog）。无备注也可编辑。"""
        current = records.get_device_remark(self.records_path, device_id)
        dlg = tk.Toplevel(self)
        dlg.title("添加备注" if not current else "编辑备注")
        dlg.geometry("400x230")
        dlg.resizable(False, False)
        dlg.configure(bg=BG)
        dlg.transient(self)
        dlg.grab_set()

        outer = ttk.Frame(dlg, style="TFrame", padding=(18, 16))
        outer.pack(fill=tk.BOTH, expand=True)
        tk.Label(outer, text=f"设备 {device_id}", font=("Courier", 10),
                 fg=MUTED, bg=BG).pack(anchor=tk.W, pady=(0, 8))
        entry = tk.Text(outer, height=4, wrap="word", font=("TkDefaultFont", 11),
                        bg=FIELD_BG, fg=TEXT, insertbackground=TEXT,
                        relief="solid", bd=1, padx=8, pady=6)
        entry.insert(1.0, current)
        entry.pack(fill=tk.BOTH, expand=True)
        entry.focus_set()

        bar = ttk.Frame(outer, style="TFrame")
        bar.pack(fill=tk.X, pady=(10, 0))

        def save():
            text = entry.get(1.0, "end-1c").strip()
            records.set_device_remark(self.records_path, device_id, text)
            dlg.destroy()
            self._refresh()
            if self.on_changed:
                self.on_changed()

        ttk.Button(bar, text="保存", style="Accent.TButton", command=save).pack(side=tk.RIGHT, padx=(6, 0))
        ttk.Button(bar, text="取消", command=dlg.destroy).pack(side=tk.RIGHT)

    def _confirm_delete(self, rid, dlg=None) -> None:
        if messagebox.askyesno("确认删除", "确定删除这条注册记录？此操作不可撤销。"):
            records.RecordStore(self.records_path).delete_by_id(rid)
            if dlg:
                dlg.destroy()
            if self.on_changed:
                self.on_changed()
            self._refresh()

    def _confirm_delete_device(self, dev: str) -> None:
        if messagebox.askyesno("确认删除", f"确定删除设备 {dev} 的全部记录吗？"):
            records.RecordStore(self.records_path).delete_by_device_id(dev)
            records.delete_device_remark(self.records_path, dev)
            if self.on_changed:
                self.on_changed()
            self._refresh()


def main() -> None:
    root = tk.Tk()
    KeygenApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()
