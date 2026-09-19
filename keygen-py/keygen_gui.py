"""RegGate 注册机 GUI（Python / Tkinter）。

与 Android 端 keygen-app 功能对齐:
  - 从本地文件加载 RSA 私钥 (PKCS#8 / PKCS#1, PEM 或 DER)
  - 粘贴安装码, 或从二维码图片识别安装码, 解析出设备ID 与 包名
  - 指定有效天数 (0 = 永久)
  - 生成带包绑定的激活码, 支持复制与二维码展示/保存
"""

from __future__ import annotations

import os
import subprocess
import sys
from datetime import datetime
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

# 二维码为新增依赖: 未执行 `uv sync` 时仅二维码功能不可用, 不影响其他功能启动
try:
    import qrcode
    from qrcode.constants import ERROR_CORRECT_M
    from PIL import Image, ImageOps, ImageTk
except ImportError:  # pragma: no cover - 取决于运行环境
    qrcode = None
    ERROR_CORRECT_M = None
    Image = None
    ImageOps = None
    ImageTk = None

# 安装码图片识别/摄像头扫码依赖(headless: VideoCapture 仍可用, 预览在 Tk 内渲染)
try:
    import cv2
    import numpy as np
except ImportError:  # pragma: no cover - 取决于运行环境
    cv2 = None
    np = None

import reggate
import records

# ===== 配色(与 Android 端 reggate/kg 色板完全一致) =====
PRIMARY       = "#1976D2"   # 品牌主蓝
PRIMARY_DARK  = "#1565C0"   # 主蓝(悬停)
PRIMARY_PRESS = "#0D47A1"   # 主蓝(按下)
PRIMARY_LIGHT = "#E7F1FD"   # 浅蓝底(chip)
CHIP_PRESS    = "#D0E3FA"   # 浅蓝底(按下)
BG            = "#EDF2F7"   # 窗口底色(衬托白卡)
CARD          = "#FFFFFF"   # 卡片/表面
CARD_TINT     = "#F6F9FD"   # 次级卡片/字段底
STROKE        = "#DEE8F2"   # 卡片描边
FIELD_STROKE  = "#D4DFEB"   # 输入框描边
TEXT          = "#222222"   # 主文字
DARK          = TEXT
MUTED         = "#7A8699"   # 次要文字
BORDER        = STROKE
SUCCESS       = "#1E8E5A"   # 成功绿
DANGER        = "#DC2626"   # 红
FIELD_BG      = "#FFFFFF"
CODE_BG       = "#FFFFFF"

# 辅助色
PANEL       = CARD_TINT    # 底部状态栏(浅)
PANEL_FG    = MUTED
HEADER_FG   = "#CDE2FA"   # 蓝色标题栏上的副标题
CODE_FG     = "#1565C0"   # 激活码/设备码文字
CODE_BOX    = PRIMARY_LIGHT
PKG_FG      = "#7B1FA2"   # 包名 (紫)
DURATION_FG = "#E65100"   # 购买时长 (橙)
CARD_HDR    = CARD_TINT
BULLET      = "#C3D6EC"   # 圆点/占位描边


def _round_rect_points(x1, y1, x2, y2, r=10, steps=6):
    """生成平滑圆角矩形多边形点列(用于 Canvas.create_polygon)。"""
    pts = []
    # 上右 -> 下右 -> 下左 -> 上左
    arcs = [
        (x2 - r, y1 + r, -90, 0),
        (x2 - r, y2 - r, 0, 90),
        (x1 + r, y2 - r, 90, 180),
        (x1 + r, y1 + r, 180, 270),
    ]
    import math
    for cx, cy, a1, a2 in arcs:
        for i in range(steps + 1):
            a = math.radians(a1 + (a2 - a1) * i / steps)
            pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return pts


class RoundedCard(tk.Canvas):
    """圆角白卡(Canvas 自绘, ttk 不支持圆角)。

    内容请 pack/grid 到 :attr:`inner`; 高度随内容自适应。
    """

    def __init__(self, parent, title: str = "", radius: int = 12,
                 padx: int = 16, title_gap: int = 6, **kw):
        super().__init__(parent, highlightthickness=0, bd=0, bg=kw.pop("bg", BG),
                         **kw)
        self.radius = radius
        self._padx = padx
        self._title_h = 34 if title else 12
        self._title_gap = title_gap if title else 0

        self.inner = tk.Frame(self, bg=CARD)
        self._win = self.create_window(padx, self._title_h, window=self.inner,
                                       anchor="nw")
        if title:
            self._title_id = self.create_text(
                padx + 2, 17, anchor="w", text=title,
                font=("TkDefaultFont", 12, "bold"), fill="#33414E")
        else:
            self._title_id = None

        self.inner.bind("<Configure>", self._on_inner)
        self.bind("<Configure>", self._on_canvas)
        self._cw = 1
        self._ch = 1

    def _on_inner(self, _e):
        h = self._title_h + self._title_gap + self.inner.winfo_reqheight() + 12
        self._ch = h
        self.configure(height=h)
        self.itemconfigure(self._win, width=self._cw - self._padx * 2)
        self._redraw()

    def _on_canvas(self, e):
        if e.width != self._cw:
            self._cw = e.width
            self.itemconfigure(self._win, width=self._cw - self._padx * 2)
            self._redraw()

    def _redraw(self):
        self.delete("bg")
        w = self._cw
        h = self._ch
        self.create_polygon(_round_rect_points(0.75, 0.75, w - 0.75, h - 0.75,
                                               self.radius),
                            fill=CARD, outline=STROKE, width=1, tags="bg")
        self.tag_lower("bg")
        if self._title_id is not None:
            self.tag_raise(self._title_id)


class RoundedButton(tk.Canvas):
    """圆角按钮: primary(实心蓝) / outline(白底蓝边) / tinted(浅蓝底)。"""

    def __init__(self, parent, text: str, command=None, variant: str = "primary",
                 height: int = 36, parent_bg: str = CARD, font_size: int = 10,
                 bold: bool = False):
        super().__init__(parent, height=height, highlightthickness=0, bd=0,
                         bg=parent_bg, cursor="hand2")
        self.command = command
        self.variant = variant
        self.height = height
        self.enabled = True
        self._hover = False
        self._pressed = False
        self._text = text
        self._font = ("TkDefaultFont", font_size, "bold" if bold else "normal")
        self._cw = 0

        import tkinter.font as tkfont
        f = tkfont.Font(family="TkDefaultFont", size=font_size,
                        weight="bold" if bold else "normal")
        self._cw = f.measure(text) + 36
        self.configure(width=self._cw)

        self.bind("<Configure>", self._on_configure)
        self.bind("<Enter>", self._enter)
        self.bind("<Leave>", self._leave)
        self.bind("<ButtonPress-1>", self._press)
        self.bind("<ButtonRelease-1>", self._release)

    def _on_configure(self, e):
        # fill=X 拉伸时实际宽度由布局决定
        if e.width > 1:
            self._cw = e.width
        self._redraw()

    def set_enabled(self, enabled: bool):
        if self.enabled != enabled:
            self.enabled = enabled
            self.configure(cursor="hand2" if enabled else "arrow")
            self._redraw()

    def _colors(self):
        if not self.enabled:
            if self.variant == "primary":
                return "#B8C7D9", "", "#FFFFFF"
            return CARD, "#DDE5EE", "#9AA8B6"
        if self.variant == "primary":
            fill = PRIMARY_PRESS if self._pressed else \
                PRIMARY_DARK if self._hover else PRIMARY
            return fill, "", "#FFFFFF"
        if self.variant == "tinted":
            fill = CHIP_PRESS if (self._hover or self._pressed) else PRIMARY_LIGHT
            return fill, "", CODE_FG
        # outline
        fill = PRIMARY_LIGHT if (self._hover or self._pressed) else CARD
        return fill, "#B8CCE2", CODE_FG

    def _redraw(self):
        self.delete("all")
        fill, outline, fg = self._colors()
        w = self._cw or int(self.cget("width"))
        h = self.height
        self.create_polygon(_round_rect_points(1, 1, w - 1, h - 1, 8),
                            fill=fill, outline=outline or fill, width=1)
        self.create_text(w / 2, h / 2, text=self._text, fill=fg,
                         font=self._font)

    def _enter(self, _e):
        self._hover = True
        self._redraw()

    def _leave(self, _e):
        self._hover = False
        self._pressed = False
        self._redraw()

    def _press(self, _e):
        if not self.enabled:
            return
        self._pressed = True
        self._redraw()

    def _release(self, _e):
        if not self.enabled:
            return
        was = self._pressed
        self._pressed = False
        self._redraw()
        if was and self._hover and self.command:
            self.command()


class _StatusPill(tk.Canvas):
    """标题栏右侧的私钥状态胶囊(蓝底深胶囊 + 状态点)。"""

    HEIGHT = 28
    DOT_EMPTY = "#9FB8D4"
    DOT_LOADED = "#7BE0B0"

    def __init__(self, parent):
        super().__init__(parent, height=self.HEIGHT, highlightthickness=0, bd=0,
                         bg=PRIMARY)
        import tkinter.font as tkfont
        self._font = tkfont.Font(family="TkDefaultFont", size=9)
        self.set_empty()

    def _render(self, text: str, dot: str):
        text_w = self._font.measure(text)
        w = text_w + 40
        self.configure(width=w)
        self.delete("all")
        self.create_polygon(
            _round_rect_points(0.5, 0.5, w - 0.5, self.HEIGHT - 0.5, 9),
            fill=PRIMARY_DARK, outline=PRIMARY_DARK)
        self.create_text(20, self.HEIGHT / 2 + 0.5, text="●", fill=dot,
                         font=("TkDefaultFont", 9))
        self.create_text(32, self.HEIGHT / 2, anchor="w", text=text,
                         fill="#FFFFFF", font=("TkDefaultFont", 9))

    def set_empty(self):
        self._render("未加载私钥", self.DOT_EMPTY)

    def set_loaded(self, file_name: str):
        if len(file_name) > 22:
            file_name = file_name[:10] + "…" + file_name[-10:]
        self._render("已加载 · " + file_name, self.DOT_LOADED)


class _InfoTile(tk.Canvas):
    """圆角浅色字段块: 小标题 + 值(值控件由外部放进 inner)。整块可点击复制。"""

    def __init__(self, parent, title: str):
        super().__init__(parent, height=64, highlightthickness=0, bd=0, bg=CARD,
                         cursor="hand2")
        self.inner = tk.Frame(self, bg=CARD_TINT)
        self._win = self.create_window(10, 9, window=self.inner, anchor="nw")
        tk.Label(self.inner, text=title, bg=CARD_TINT, fg=MUTED,
                 font=("TkDefaultFont", 8, "bold")).pack(anchor=tk.W)
        self._cw = 1
        self._ch = 56
        self.inner.bind("<Configure>", self._on_inner)
        self.bind("<Configure>", self._on_canvas)

    def _on_inner(self, _e):
        h = max(self.inner.winfo_reqheight() + 18, 56)
        self._ch = h
        self.configure(height=h)
        self.itemconfigure(self._win, width=self._cw - 20)
        self._redraw()

    def _on_canvas(self, e):
        if e.width != self._cw:
            self._cw = e.width
            self.itemconfigure(self._win, width=self._cw - 20)
            self._redraw()

    def _redraw(self):
        self.delete("bg")
        self.create_polygon(
            _round_rect_points(0.5, 0.5, self._cw - 0.5, self._ch - 0.5, 8),
            fill=CARD_TINT, outline=CARD_TINT, tags="bg")
        self.tag_lower("bg")

    def bind_click(self, callback):
        self.bind("<Button-1>", lambda _e: callback())
        for child in (self.inner, *self.inner.winfo_children()):
            child.bind("<Button-1>", lambda _e: callback())


class KeygenApp:
    def __init__(self, root: tk.Tk) -> None:
        self.root = root
        self.root.title("RegGate 注册机")
        self.root.configure(bg=BG)
        self.root.geometry("640x820")
        self.root.minsize(580, 680)
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
        # 记录窗口仍在使用 LabelFrame 卡片
        style.configure("Card.TLabelframe", background=CARD, borderwidth=1,
                        relief="solid", bordercolor=STROKE)
        style.configure("Card.TLabelframe.Label", background=BG, foreground=PRIMARY,
                        font=("TkDefaultFont", 11, "bold"))
        style.configure("TButton", padding=(10, 6), font=("TkDefaultFont", 10),
                        background=CARD, foreground=TEXT, borderwidth=1, relief="solid")
        style.map("TButton", background=[("active", CARD_TINT), ("disabled", CARD_TINT)],
                  foreground=[("disabled", "#9AA8B6")])
        # 蓝色标题栏上的按钮(记录窗口)
        style.configure("Header.TButton", background=PRIMARY_DARK, foreground="white",
                        borderwidth=0, padding=(12, 5),
                        font=("TkDefaultFont", 10))
        style.map("Header.TButton",
                  background=[("active", PRIMARY_PRESS), ("disabled", PRIMARY_DARK)])
        style.configure("Accent.TButton", background=PRIMARY, foreground="white",
                        borderwidth=0, padding=(14, 9), font=("TkDefaultFont", 11, "bold"))
        style.map("Accent.TButton",
                  background=[("active", PRIMARY_DARK), ("disabled", "#B8C7D9")],
                  foreground=[("disabled", "#FFFFFF")])
        style.configure("Ghost.TButton", background=CARD, foreground=CODE_FG,
                        borderwidth=1, relief="solid", padding=(10, 6),
                        font=("TkDefaultFont", 10), bordercolor="#B8CCE2")
        style.map("Ghost.TButton", background=[("active", PRIMARY_LIGHT)])
        style.configure("Danger.TButton", background=DANGER, foreground="white",
                        borderwidth=0, padding=(10, 6), font=("TkDefaultFont", 10, "bold"))
        style.map("Danger.TButton", background=[("active", "#B91C1C"), ("disabled", "#FCA5A5")],
                  foreground=[("disabled", "#FEE2E2")])
        style.configure("TEntry", padding=8, fieldbackground=FIELD_BG, foreground=TEXT,
                        borderwidth=1, relief="solid", bordercolor=FIELD_STROKE,
                        lightcolor=FIELD_STROKE, darkcolor=FIELD_STROKE,
                        insertcolor=TEXT)
        style.map("TEntry",
                  bordercolor=[("focus", PRIMARY)],
                  lightcolor=[("focus", PRIMARY)],
                  darkcolor=[("focus", PRIMARY)])
        style.configure("TSpinbox", padding=7, fieldbackground=FIELD_BG, foreground=TEXT,
                        borderwidth=1, relief="solid", bordercolor=FIELD_STROKE,
                        lightcolor=FIELD_STROKE, darkcolor=FIELD_STROKE,
                        arrowsize=14)
        style.map("TSpinbox",
                  bordercolor=[("focus", PRIMARY)],
                  lightcolor=[("focus", PRIMARY)],
                  darkcolor=[("focus", PRIMARY)])
        style.configure("Link.TLabel", foreground=PRIMARY, background=BG,
                        font=("TkDefaultFont", 9))

    # ---------------- UI 小工具 ----------------
    @staticmethod
    def _divider(parent) -> None:
        sep = tk.Frame(parent, bg=STROKE, height=1)
        sep.pack(fill=tk.X, pady=10)

    def _build_ui(self) -> None:
        # ============ 顶部品牌栏 ============
        header = tk.Frame(self.root, bg=PRIMARY, height=60)
        header.pack(fill=tk.X)
        header.pack_propagate(False)
        title_box = tk.Frame(header, bg=PRIMARY)
        title_box.pack(side=tk.LEFT, padx=20)
        tk.Label(title_box, text="RegGate 注册机", bg=PRIMARY, fg="white",
                 font=("TkDefaultFont", 17, "bold")).pack(anchor=tk.W, pady=(9, 0))
        tk.Label(title_box, text="激活码生成工具", bg=PRIMARY, fg=HEADER_FG,
                 font=("TkDefaultFont", 9)).pack(anchor=tk.W, pady=(0, 8))

        # 私钥状态胶囊(始终可见)
        self.key_status = _StatusPill(header)
        self.key_status.pack(side=tk.RIGHT, padx=18)

        # ============ 底部状态栏(先占位) ============
        self.status_var = tk.StringVar(value="就绪")
        footer = tk.Frame(self.root, bg=PANEL, height=28)
        footer.pack(side=tk.BOTTOM, fill=tk.X)
        footer.pack_propagate(False)
        tk.Frame(footer, bg=STROKE, height=1).pack(fill=tk.X, side=tk.TOP)
        tk.Label(footer, textvariable=self.status_var, bg=PANEL, fg=PANEL_FG,
                 font=("TkDefaultFont", 9), anchor=tk.W, padx=16).pack(fill=tk.BOTH,
                                                                        expand=True)

        # ============ 主体(可滚动) ============
        container = tk.Frame(self.root, bg=BG)
        container.pack(fill=tk.BOTH, expand=True)
        canvas = tk.Canvas(container, bg=BG, highlightthickness=0, bd=0)
        vsb = ttk.Scrollbar(container, orient="vertical", command=canvas.yview)
        canvas.configure(yscrollcommand=vsb.set)
        vsb.pack(side=tk.RIGHT, fill=tk.Y)
        canvas.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        inner = tk.Frame(canvas, bg=BG)
        self.scroll_inner = inner
        canvas_window = canvas.create_window((0, 0), window=inner, anchor="nw")
        inner.bind("<Configure>",
                   lambda _e: canvas.configure(scrollregion=canvas.bbox("all")))
        canvas.bind("<Configure>",
                    lambda e: canvas.itemconfigure(canvas_window, width=e.width))

        def _on_wheel(e):
            canvas.yview_scroll(int(-1 * (e.delta / 120)), "units")
        canvas.bind("<Enter>", lambda _e: canvas.bind_all("<MouseWheel>", _on_wheel))
        canvas.bind("<Leave>", lambda _e: canvas.unbind_all("<MouseWheel>"))

        # —— 卡片 1: 密钥与存储 ——
        card = RoundedCard(inner, "密钥与存储")
        card.pack(fill=tk.X, padx=18, pady=(14, 5))
        sec = card.inner
        row = tk.Frame(sec, bg=CARD)
        row.pack(fill=tk.X)
        RoundedButton(row, "选择私钥…", self._select_private_key,
                      variant="outline", height=34).pack(side=tk.LEFT)

        self._divider(sec)
        row = tk.Frame(sec, bg=CARD)
        row.pack(fill=tk.X)
        RoundedButton(row, "选择目录…", self._choose_save_location,
                      variant="outline", height=34).pack(side=tk.LEFT)
        RoundedButton(row, "查看记录",
                      lambda: self._view_records(
                          pkg_highlight=self.current_pkg or None,
                          device_highlight=self.current_dev or None),
                      variant="outline", height=34).pack(side=tk.LEFT, padx=(10, 0))
        self.save_label = tk.Label(sec, text="-", fg=MUTED, bg=CARD, anchor=tk.W,
                                   justify=tk.LEFT, wraplength=520,
                                   font=("TkDefaultFont", 9))
        self.save_label.pack(fill=tk.X, pady=(10, 0))

        # —— 卡片 2: 客户机安装码 ——
        card = RoundedCard(inner, "客户机安装码")
        card.pack(fill=tk.X, padx=18, pady=5)
        sec = card.inner
        self.request_var = tk.StringVar()
        self.request_entry = ttk.Entry(sec, textvariable=self.request_var,
                                       font=("Courier", 11))
        self.request_entry.pack(fill=tk.X)
        self.request_entry.bind("<KeyRelease>", lambda _e: self._on_request_changed())

        row = tk.Frame(sec, bg=CARD)
        row.pack(fill=tk.X, pady=(10, 0))
        RoundedButton(row, "粘贴", self._paste_request,
                      variant="outline", height=34).pack(side=tk.LEFT)
        RoundedButton(row, "图片识别…", self._pick_request_image,
                      variant="tinted", height=34).pack(side=tk.LEFT, padx=(10, 0))

        self._divider(sec)
        # 设备信息: 两个字段块(强制等宽)
        info_row = tk.Frame(sec, bg=CARD)
        info_row.pack(fill=tk.X)
        info_row.grid_columnconfigure(0, weight=1, uniform="infotile")
        info_row.grid_columnconfigure(1, weight=1, uniform="infotile")
        dev_tile = self._info_tile(info_row, "设备 ID（点击复制）")
        dev_tile.grid(row=0, column=0, sticky=tk.EW, padx=(0, 5))
        pkg_tile = self._info_tile(info_row, "包名（点击复制）")
        pkg_tile.grid(row=0, column=1, sticky=tk.EW, padx=(5, 0))

        self.device_id_label = tk.Label(dev_tile.inner, text="-", bg=CARD_TINT,
                                        fg=CODE_FG, font=("Courier", 9, "bold"),
                                        cursor="hand2", justify=tk.LEFT, anchor=tk.W,
                                        wraplength=150)
        self.device_id_label.pack(fill=tk.X)
        self.device_id_label.bind("<Button-1>", lambda _e: self._copy_device_id())
        dev_tile.bind_click(self._copy_device_id)
        self.pkg_label = tk.Label(pkg_tile.inner, text="-", bg=CARD_TINT,
                                  fg=PKG_FG, font=("TkDefaultFont", 10, "bold"),
                                  cursor="hand2", justify=tk.LEFT, anchor=tk.W,
                                  wraplength=150)
        self.pkg_label.pack(fill=tk.X)
        self.pkg_label.bind("<Button-1>", lambda _e: self._copy_pkg())
        pkg_tile.bind_click(self._copy_pkg)
        # 字段块随窗口缩放时动态调整换行宽度
        for _t, _lbl in ((dev_tile, self.device_id_label),
                         (pkg_tile, self.pkg_label)):
            _t.bind("<Configure>",
                    lambda e, lbl=_lbl: lbl.configure(
                        wraplength=max(80, e.width - 26)))

        self.device_hint = tk.Label(sec, text="", fg=MUTED, bg=CARD, anchor=tk.W,
                                    justify=tk.LEFT, font=("TkDefaultFont", 9))
        self.device_hint.pack(fill=tk.X, pady=(10, 0))

        # —— 卡片 3: 有效期与生成 ——
        card = RoundedCard(inner, "有效期")
        card.pack(fill=tk.X, padx=18, pady=5)
        sec = card.inner
        row = tk.Frame(sec, bg=CARD)
        row.pack(fill=tk.X)
        self.days_var = tk.StringVar(value="365")
        self.days_spin = ttk.Spinbox(row, from_=0, to=36500, increment=30,
                                     textvariable=self.days_var, width=9,
                                     font=("TkDefaultFont", 11))
        self.days_spin.pack(side=tk.LEFT)
        tk.Label(row, text="天（0 = 永久）", fg=MUTED, bg=CARD,
                 font=("TkDefaultFont", 9)).pack(side=tk.LEFT, padx=(10, 0))
        quick = tk.Frame(row, bg=CARD)
        quick.pack(side=tk.RIGHT)
        for days, txt in ((30, "30 天"), (90, "90 天"), (365, "一年"), (0, "永久")):
            RoundedButton(quick, txt, lambda d=days: self._set_days(d),
                          variant="outline", height=28, font_size=9
                          ).pack(side=tk.LEFT, padx=(0, 6))

        self.generate_btn = RoundedButton(sec, "生成激活码", self._generate,
                                          variant="primary", height=42,
                                          parent_bg=CARD, font_size=12, bold=True)
        self.generate_btn.pack(fill=tk.X, pady=(12, 0))

        # —— 卡片 4: 激活码 ——
        card = RoundedCard(inner, "激活码")
        card.pack(fill=tk.X, padx=18, pady=(5, 14))
        sec = card.inner
        self.activation_text = tk.Text(sec, height=3, wrap="word", font=("Courier", 10),
                                       bg=FIELD_BG, fg=TEXT, insertbackground=TEXT,
                                       relief="flat", highlightthickness=1,
                                       highlightbackground=FIELD_STROKE,
                                       highlightcolor=PRIMARY,
                                       state="disabled", padx=10, pady=9)
        self.activation_text.pack(fill=tk.X)
        row = tk.Frame(sec, bg=CARD)
        row.pack(fill=tk.X, pady=(10, 0))
        RoundedButton(row, "复制激活码", self._copy_activation,
                      variant="outline", height=34).pack(side=tk.LEFT)
        self.expiry_label = tk.Label(row, text="", fg=CODE_FG, bg=CARD,
                                     font=("TkDefaultFont", 10, "bold"))
        self.expiry_label.pack(side=tk.LEFT, padx=(14, 0))

        # 二维码区(占位 -> 生成后白框展示)
        qr_wrap = tk.Frame(sec, bg=CARD)
        qr_wrap.pack(fill=tk.X, pady=(12, 0))
        self.qr_placeholder = tk.Canvas(qr_wrap, width=200, height=148,
                                        bg=CARD, highlightthickness=0)
        self.qr_placeholder.pack()
        self.qr_placeholder.create_rectangle(
            2, 2, 198, 146, dash=(5, 5), outline=BULLET, width=1.5, fill=CARD_TINT)
        self.qr_placeholder.create_text(
            100, 60, text="激活码二维码", fill=MUTED,
            font=("TkDefaultFont", 10, "bold"))
        self.qr_placeholder.create_text(
            100, 86, text="生成激活码后在此显示", fill="#A5B2C0",
            font=("TkDefaultFont", 9))

        self.qr_holder = tk.Frame(qr_wrap, bg=FIELD_STROKE, padx=1, pady=1)
        self.qr_image_label = tk.Label(self.qr_holder, bg="white",
                                       padx=10, pady=10)
        self.qr_image_label.pack()
        self.qr_photo = None
        self.qr_pil_image = None

        self.qr_caption = tk.Label(
            qr_wrap, text="客户可扫码激活，也可保存图片后通过聊天软件发送",
            fg="#A5B2C0", bg=CARD, font=("TkDefaultFont", 9))
        self.qr_caption.pack(pady=(8, 0))

        self.save_qr_btn = RoundedButton(
            qr_wrap, "保存二维码图片…", self._save_qr_image,
            variant="outline", height=34, parent_bg=CARD)
        self.save_qr_btn.pack(pady=(10, 0))
        self.save_qr_btn.set_enabled(False)

    # ---------------- UI 小组件 ----------------
    def _set_days(self, days: int) -> None:
        self.days_var.set(str(days))

    def _info_tile(self, parent, title: str):
        """设备/包名字段块(圆角浅底, 整块可点击)。"""
        tile = _InfoTile(parent, title)
        return tile

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
        self.key_status.set_loaded(os.path.basename(path))
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
        self.key_status.set_loaded(os.path.basename(path))

    def _paste_request(self) -> None:
        try:
            clip = self.root.clipboard_get()
        except tk.TclError:
            clip = ""
        if clip:
            self.request_var.set(clip.strip())
            self._on_request_changed()

    # ---------------- 安装码二维码(图片识别) ----------------
    def _pick_request_image(self) -> None:
        """选择客户机发来的安装码二维码截图/照片并识别。"""
        if cv2 is None:
            messagebox.showinfo(
                "缺少依赖",
                "图片识别需要 OpenCV 依赖。\n请在项目目录执行: uv sync",
                parent=self.root)
            return
        init = self.config.get("last_dir")
        path = filedialog.askopenfilename(
            title="选择安装码二维码图片",
            initialdir=init if init else None,
            filetypes=[("图片文件", "*.png *.jpg *.jpeg *.bmp *.webp"),
                       ("所有文件", "*.*")],
            parent=self.root)
        if not path:
            return
        self.root.config(cursor="watch")
        self.root.update_idletasks()
        try:
            text = self._decode_qr_image_file(path)
        finally:
            self.root.config(cursor="")
        if text:
            self._apply_scanned_request(text, source="图片")
        else:
            messagebox.showwarning(
                "未识别到二维码",
                "未在该图片中识别到二维码。\n请尝试更清晰的原图或截图，避免图片被过度压缩。",
                parent=self.root)

    def _apply_scanned_request(self, text: str, source: str = "图片") -> None:
        if not text:
            return
        self.request_var.set(text.strip())
        self._on_request_changed()
        self.status_var.set(f"已从{source}识别安装码")

    @staticmethod
    def _decode_qr_image_file(path: str) -> str:
        """解码本地图片中的二维码(主链路优先 + 预处理兜底)。

        - 经 PIL 读取并做 EXIF 方向校正(手机照片常见旋转)
        - 主链路: 原图及 90/180/270 旋转直接解码, 避免预处理误伤清晰图
        - 兜底: 聊天软件压缩/低对比/光照不均时, 依次尝试放大、
          自适应阈值二值化、对比度增强, 每个候选再跑四个方向
        """
        pil = ImageOps.exif_transpose(Image.open(path)).convert("RGB")
        base = np.array(pil)[:, :, ::-1].copy()  # RGB -> BGR
        detector = cv2.QRCodeDetector()

        def try_decode(img) -> str:
            for k in (0, 1, 2, 3):
                text, _points, _ = detector.detectAndDecode(np.rot90(img, k))
                if text:
                    return text.strip()
            return ""

        # 1) 主链路: 原图 + 旋转
        result = try_decode(base)
        if result:
            return result

        # 2) 兜底候选(原图失败后才执行, 兼顾清晰图的速度与稳定)
        gray = cv2.cvtColor(base, cv2.COLOR_BGR2GRAY)
        h, w = gray.shape
        candidates = []

        # 2a) 小图放大(截图缩略图常见, 双三次插值保留边缘)
        scale = 1.0
        if max(h, w) < 1000:
            scale = min(2.0, 1200.0 / max(h, w))
        if scale > 1.05:
            up = cv2.resize(gray, None, fx=scale, fy=scale,
                            interpolation=cv2.INTER_CUBIC)
            candidates.append(cv2.cvtColor(up, cv2.COLOR_GRAY2BGR))

        # 2b) 自适应阈值二值化(光照不均/阴影)
        binary = cv2.adaptiveThreshold(
            gray, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C,
            cv2.THRESH_BINARY, 31, 5)
        candidates.append(cv2.cvtColor(binary, cv2.COLOR_GRAY2BGR))

        # 2c) CLAHE 对比度受限增强(发灰/低对比)
        clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8)).apply(gray)
        candidates.append(cv2.cvtColor(clahe, cv2.COLOR_GRAY2BGR))

        # 2d) Otsu 全局二值化(整体偏暗/偏亮)
        _ok, otsu = cv2.threshold(
            gray, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
        candidates.append(cv2.cvtColor(otsu, cv2.COLOR_GRAY2BGR))

        for img in candidates:
            result = try_decode(img)
            if result:
                return result
        return ""

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
        self._show_activation_qr(code)

    # ---------------- 激活码二维码 ----------------
    def _show_activation_qr(self, code_grouped: str) -> None:
        """生成激活码二维码并内联展示。

        内容为无连字符纯码(与 Android 注册机一致), M 级 15% 纠错;
        box_size=8 时约 600px, 保存后经聊天软件压缩仍可稳定扫描。
        """
        if qrcode is None:
            self.save_qr_btn.set_enabled(False)
            self.status_var.set("缺少 qrcode/Pillow 依赖, 二维码不可用(请运行 uv sync)")
            return
        qr = qrcode.QRCode(error_correction=ERROR_CORRECT_M, box_size=8, border=2)
        qr.add_data(reggate.Base32.ungroup(code_grouped))
        qr.make(fit=True)
        # convert 经 qrcode PilImage 的 __getattr__ 委托到底层 PIL.Image
        self.qr_pil_image = qr.make_image(
            fill_color="black", back_color="white").convert("RGB")

        display = self.qr_pil_image.copy()
        display.thumbnail((240, 240), Image.NEAREST)
        self.qr_photo = ImageTk.PhotoImage(display)
        self.qr_image_label.config(image=self.qr_photo, bg="white")
        # 占位框 -> 白边二维码图
        self.qr_placeholder.pack_forget()
        self.qr_holder.pack(pady=(10, 0))
        self.qr_caption.config(fg=MUTED)
        self.save_qr_btn.set_enabled(True)

    def _save_qr_image(self) -> None:
        if self.qr_pil_image is None:
            return
        stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        path = filedialog.asksaveasfilename(
            title="保存激活码二维码",
            defaultextension=".png",
            initialfile=f"激活码二维码_{stamp}.png",
            filetypes=[("PNG 图片", "*.png")],
        )
        if not path:
            return
        try:
            self.qr_pil_image.save(path, format="PNG")
        except OSError as exc:
            messagebox.showerror("保存失败", str(exc))
            return
        self.status_var.set(f"二维码已保存: {path}")
        self._reveal_in_file_manager(path)

    @staticmethod
    def _reveal_in_file_manager(path: str) -> None:
        """保存后在系统文件管理器中定位文件, 方便直接拖入聊天软件发送。"""
        try:
            if sys.platform == "darwin":
                subprocess.Popen(["open", "-R", path])
            elif sys.platform.startswith("win"):
                subprocess.Popen(["explorer", "/select,", os.path.normpath(path)])
            else:
                subprocess.Popen(["xdg-open", os.path.dirname(path)])
        except OSError:
            pass

    def _copy_activation(self) -> None:
        code = self.activation_text.get(1.0, "end-1c").strip()
        if not code:
            return
        self.root.clipboard_clear()
        self.root.clipboard_append(code)
        self.status_var.set("激活码已复制到剪贴板")

    def _refresh_ui_state(self) -> None:
        has_key = self.private_key is not None
        self.generate_btn.set_enabled(has_key)

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
                   style="Header.TButton").pack(side=tk.RIGHT, padx=16, pady=8)

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
