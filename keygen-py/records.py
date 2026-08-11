"""注册记录存储（与 Android RegRecordManager 的 reg_records.json 格式兼容）。

数据模型 (每条记录一个 dict):
    id            唯一ID (毫秒时间戳)
    deviceId      设备ID hex (大写)
    requestCode   安装码 (未分组)
    packageName   目标App包名
    validDays     购买天数, 0=永久
    expiryDate    到期日 (yyyy-MM-dd 或 "永久")
    regAt         注册时间 (yyyy-MM-dd HH:mm:ss)
    activationCode 生成的激活码
"""

from __future__ import annotations

import json
import os
import sys
from datetime import datetime


def _app_dir() -> str:
    """可执行文件所在目录。

    打包成单文件 exe (PyInstaller --onefile) 时 __file__ 指向临时解压目录,
    故优先使用 sys.executable 所在目录, 保证配置稳定落在发布目录下。
    """
    exe = getattr(sys, "executable", None)
    if exe and os.path.isfile(exe):
        return os.path.dirname(os.path.abspath(exe))
    return os.path.dirname(os.path.abspath(__file__))


CONFIG_DIR = os.path.join(_app_dir(), "config")
CONFIG_FILE = os.path.join(CONFIG_DIR, "keygen_config.json")
DEFAULT_RECORDS_PATH = os.path.join(CONFIG_DIR, "reg_records.json")


def _ensure_config_dir() -> None:
    os.makedirs(CONFIG_DIR, exist_ok=True)


# ===== 配置 (保存位置持久化) =====
def load_config() -> dict:
    cfg = {"records_path": DEFAULT_RECORDS_PATH}
    if os.path.exists(CONFIG_FILE):
        try:
            with open(CONFIG_FILE, "r", encoding="utf-8") as fh:
                cfg.update(json.load(fh))
        except Exception:
            pass
    return cfg


def save_config(cfg: dict) -> None:
    _ensure_config_dir()
    with open(CONFIG_FILE, "w", encoding="utf-8") as fh:
        json.dump(cfg, fh, ensure_ascii=False, indent=2)


# ===== 设备备注 (随记录写入 reg_records.json, 每条记录带 remark 字段) =====
def get_device_remark(records_path: str, device_id: str) -> str:
    """获取设备备注（取该设备首条非空备注），无备注返回空字符串。"""
    if not device_id:
        return ""
    for r in RecordStore(records_path).load():
        if r.get("deviceId") == device_id and (r.get("remark") or ""):
            return r["remark"]
    return ""


def set_device_remark(records_path: str, device_id: str, remark: str) -> None:
    """设置/修改设备备注：写入该设备全部记录的 remark 字段。空字符串即清除。"""
    if not device_id:
        return
    store = RecordStore(records_path)
    recs = store.load()
    text = (remark or "").strip()
    changed = False
    for r in recs:
        if r.get("deviceId") == device_id:
            r["remark"] = text
            changed = True
    if changed:
        store.save_all(recs)


def delete_device_remark(records_path: str, device_id: str) -> None:
    """删除设备备注。"""
    set_device_remark(records_path, device_id, "")


def migrate_config_remarks(records_path: str) -> None:
    """旧版备注存于 keygen_config.json 的 device_remarks，迁移进记录文件。"""
    cfg = load_config()
    remarks = cfg.get("device_remarks") or {}
    if not remarks:
        return
    store = RecordStore(records_path)
    recs = store.load()
    if recs:
        by_dev: dict = {}
        for r in recs:
            by_dev.setdefault(r.get("deviceId"), []).append(r)
        changed = False
        for dev, text in remarks.items():
            if dev in by_dev and text:
                for r in by_dev[dev]:
                    r["remark"] = text
                changed = True
        if changed:
            store.save_all(recs)
    cfg.pop("device_remarks", None)
    save_config(cfg)


# ===== 记录存储 =====
def _coerce_records(data) -> list:
    """兼容多种 JSON 形态:
       - 手机版: 裸数组 [ {...}, ... ]
       - 包裹对象: {"records": [...]} / {"data": [...]} / {"items": [...]}
       非法内容返回空列表。
    """
    if isinstance(data, dict):
        for key in ("records", "data", "items"):
            if isinstance(data.get(key), list):
                data = data[key]
                break
        else:
            return []
    if not isinstance(data, list):
        return []
    return [normalize_rec(r) for r in data if isinstance(r, dict)]


def normalize_rec(r: dict) -> dict:
    """补齐缺省字段, 保证与手机版字段结构兼容。"""
    return {
        "id": r.get("id", int(datetime.now().timestamp() * 1000)),
        "deviceId": r.get("deviceId", "") or "",
        "requestCode": r.get("requestCode", "") or "",
        "packageName": r.get("packageName", "") or "",
        "validDays": r.get("validDays", 0) or 0,
        "expiryDate": r.get("expiryDate", "") or "",
        "regAt": r.get("regAt", "") or "",
        "activationCode": r.get("activationCode", "") or "",
        "remark": r.get("remark", "") or "",
    }


class RecordStore:
    def __init__(self, path: str) -> None:
        self.path = path

    def load(self) -> list[dict]:
        if not os.path.exists(self.path):
            return []
        try:
            with open(self.path, "r", encoding="utf-8") as fh:
                data = json.load(fh)
        except Exception:
            return []
        return _coerce_records(data)

    def save_all(self, records: list[dict]) -> None:
        parent = os.path.dirname(os.path.abspath(self.path))
        os.makedirs(parent, exist_ok=True)
        with open(self.path, "w", encoding="utf-8") as fh:
            json.dump(records, fh, ensure_ascii=False, indent=2)

    def upsert_by_request_code(self, record: dict) -> int:
        """按安装码覆盖：删除同安装码旧记录，追加新记录。返回总记录数。"""
        records = [r for r in self.load() if r.get("requestCode") != record["requestCode"]]
        records.append(record)
        self.save_all(records)
        return len(records)

    def merge_from(self, source_path: str) -> tuple[int, int]:
        """从外部 JSON (如手机版 reg_records.json) 合并记录, 按 id 去重。
        返回 (新增条数, 合并后总条数)。
        """
        try:
            with open(source_path, "r", encoding="utf-8") as fh:
                data = json.load(fh)
        except Exception:
            return (0, self.count())
        incoming = _coerce_records(data)
        existing = self.load()
        seen = {r.get("id") for r in existing if r.get("id") is not None}
        added = 0
        for r in incoming:
            rid = r.get("id")
            if rid is not None and rid in seen:
                continue
            existing.append(r)
            if rid is not None:
                seen.add(rid)
            added += 1
        self.save_all(existing)
        return (added, len(existing))

    def count(self) -> int:
        return len(self.load())

    def delete_by_id(self, record_id) -> bool:
        """按唯一 id 删除单条记录。返回是否删除成功。"""
        records = self.load()
        kept = [r for r in records if r.get("id") != record_id]
        if len(kept) == len(records):
            return False
        self.save_all(kept)
        return True

    def delete_by_device_id(self, device_id) -> int:
        """删除指定设备的全部记录。返回实际删除条数（与 Android deleteByDeviceId 对齐）。"""
        records = self.load()
        kept = [r for r in records if r.get("deviceId") != device_id]
        removed = len(records) - len(kept)
        if removed > 0:
            self.save_all(kept)
        return removed


def build_record(device_id: bytes, ungrouped_request: str, package_name: str,
                 valid_days: int, expiry_date: str, activation_code: str) -> dict:
    return {
        "id": int(datetime.now().timestamp() * 1000),
        "deviceId": device_id.hex().upper(),
        "requestCode": ungrouped_request,
        "packageName": package_name or "",
        "validDays": valid_days,
        "expiryDate": expiry_date,
        "regAt": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "activationCode": activation_code,
        "remark": "",
    }
