import importlib
import math
import os
import sys
from typing import Any


def parse_bool(value: Any, field_name: str) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, int) and value in (0, 1):
        return bool(value)
    if isinstance(value, str):
        normalized = value.strip().lower()
        if normalized in ("true", "1", "yes"):
            return True
        if normalized in ("false", "0", "no"):
            return False
    raise ValueError(f"{field_name} 必须是布尔值")


def parse_int(value: Any, field_name: str, minimum: int, maximum: int) -> int:
    if isinstance(value, bool):
        raise ValueError(f"{field_name} 必须是整数")
    if isinstance(value, float) and not value.is_integer():
        raise ValueError(f"{field_name} 必须是整数")
    try:
        parsed = int(value)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"{field_name} 必须是整数") from exc
    if parsed < minimum or parsed > maximum:
        raise ValueError(f"{field_name} 必须在 {minimum} 到 {maximum} 之间")
    return parsed


def parse_float(
    value: Any, field_name: str, minimum: float, maximum: float
) -> float:
    if isinstance(value, bool):
        raise ValueError(f"{field_name} 必须是数字")
    try:
        parsed = float(value)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"{field_name} 必须是数字") from exc
    if not math.isfinite(parsed):
        raise ValueError(f"{field_name} 必须是有限数字")
    if parsed < minimum or parsed > maximum:
        raise ValueError(f"{field_name} 必须在 {minimum} 到 {maximum} 之间")
    return parsed


def import_firered_module(source_dir: str, module_name: str):
    """从官方 FireRedASR2S 源码目录加载独立子模块，避开顶层全量依赖。"""
    package_dir = None
    if source_dir:
        source_dir = os.path.abspath(source_dir)
        package_dir = os.path.join(source_dir, "fireredasr2s")
        if not os.path.isdir(package_dir):
            raise FileNotFoundError(
                f"FireRedASR2S 源码目录无效，未找到: {package_dir}"
            )
        if package_dir not in sys.path:
            sys.path.insert(0, package_dir)

    errors = []
    for candidate in (module_name, f"fireredasr2s.{module_name}"):
        try:
            module = importlib.import_module(candidate)
            if package_dir:
                module_file = os.path.realpath(getattr(module, "__file__", ""))
                expected_root = os.path.realpath(package_dir)
                try:
                    within_source = (
                        os.path.commonpath((module_file, expected_root))
                        == expected_root
                    )
                except ValueError:
                    within_source = False
                if not within_source:
                    raise RuntimeError(
                        f"{module_name} 已从其他源码目录加载: {module_file}；"
                        f"当前配置要求: {expected_root}。同一进程不能混用多个 FireRedASR2S 版本"
                    )
            return module
        except ImportError as exc:
            errors.append(f"{candidate}: {exc}")

    detail = "; ".join(errors)
    raise ImportError(
        "无法加载 FireRedASR2S。请按 docs/firered-asr2s-integration.md "
        f"安装依赖并配置 source_dir。详细错误: {detail}"
    )


def require_model_dir(model_dir: Any, model_name: str) -> str:
    if not isinstance(model_dir, str) or not model_dir.strip():
        raise ValueError(f"{model_name} model_dir 不能为空")
    normalized = os.path.abspath(model_dir.strip())
    if not os.path.isdir(normalized):
        raise FileNotFoundError(f"{model_name} 模型目录不存在: {normalized}")
    return normalized


def require_model_files(model_dir: str, model_name: str, file_names) -> None:
    missing = [
        name for name in file_names if not os.path.isfile(os.path.join(model_dir, name))
    ]
    if missing:
        raise FileNotFoundError(
            f"{model_name} 模型文件不完整，缺少: {', '.join(missing)}"
        )
