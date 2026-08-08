"""设备主动助理的固定每日简报编排。"""

import asyncio
import re

from config.logger import setup_logging
from plugins_func.functions.get_news_from_newsnow import (
    CHANNEL_MAP,
    fetch_news_from_api,
    get_news_sources_from_config,
)
from plugins_func.functions.get_weather import (
    fetch_city_info,
    fetch_weather_page,
    parse_weather_info,
)

BRIEFING_FETCH_TIMEOUT_SECONDS = 8
MAX_NEWS_ITEMS = 3
MAX_BRIEFING_LENGTH = 420
TAG = __name__
logger = setup_logging()


class BriefingProviderError(RuntimeError):
    """简报数据源没有返回可播报内容。"""


def _clean_text(value, limit):
    text = re.sub(r"\s+", " ", str(value or "")).strip(" ，。；")
    return text[:limit]


async def _weather_summary(conn, location):
    config = conn.config.get("plugins", {}).get("get_weather", {})
    city = await fetch_city_info(
        location,
        config.get("api_key", "a861d0d5e7bf4ee1a83d9a9e4f96d4da"),
        config.get("api_host", "mj7p3y7naa.re.qweatherapi.com"),
    )
    if not city:
        raise BriefingProviderError("未找到天气城市")
    soup = await fetch_weather_page(city["fxLink"])
    if not soup:
        raise BriefingProviderError("天气服务请求失败")
    city_name, current, basic, forecast = parse_weather_info(soup)
    parts = [f"{_clean_text(city_name, 24)}今天{_clean_text(current, 50)}"]
    if forecast:
        _, condition, high, low = forecast[0]
        if condition and condition != "未知":
            parts.append(_clean_text(condition, 20))
        if low and high:
            parts.append(f"{_clean_text(low, 12)}到{_clean_text(high, 12)}")
    for key in ("风向", "湿度"):
        value = basic.get(key)
        if value and value != "0":
            parts.append(f"{key}{_clean_text(value, 20)}")
    return "，".join(parts) + "。"


async def _news_summary(conn):
    configured = [
        item.strip()
        for item in get_news_sources_from_config(conn).split(";")
        if item.strip() in CHANNEL_MAP
    ]
    source_name = configured[0] if configured else "澎湃新闻"
    source_id = CHANNEL_MAP.get(source_name, "thepaper")
    items = await fetch_news_from_api(conn, source_id)
    titles = []
    for item in items:
        title = _clean_text(item.get("title") if isinstance(item, dict) else "", 70)
        if title and title not in titles:
            titles.append(title)
        if len(titles) >= MAX_NEWS_ITEMS:
            break
    if not titles:
        raise BriefingProviderError("新闻服务没有返回标题")
    numbered = "；".join(f"{index + 1}，{title}" for index, title in enumerate(titles))
    return f"今日新闻，来源{source_name}：{numbered}。"


async def build_daily_briefing(conn, sections, location):
    """并行获取受控模块，单模块失败时仍返回其余内容。"""
    factories = {
        "weather": lambda: _weather_summary(conn, location),
        "news": lambda: _news_summary(conn),
    }
    tasks = [
        asyncio.wait_for(factories[section](), BRIEFING_FETCH_TIMEOUT_SECONDS)
        for section in sections
    ]
    results = await asyncio.gather(*tasks, return_exceptions=True)
    available = []
    for section, result in zip(sections, results):
        if isinstance(result, str) and result:
            available.append(result)
        elif isinstance(result, BaseException):
            logger.bind(tag=TAG).warning(
                f"每日简报{section}模块失败: {type(result).__name__}"
            )
        else:
            logger.bind(tag=TAG).warning(f"每日简报{section}模块没有返回内容")
    if not available:
        return "天气和新闻服务暂时不可用，请稍后再试。"
    text = "这是你的每日简报。" + "".join(available)
    return text[:MAX_BRIEFING_LENGTH].rstrip("，；")
