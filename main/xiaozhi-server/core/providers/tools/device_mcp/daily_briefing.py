"""设备主动助理的固定每日简报编排。"""

import asyncio
import re

from config.logger import setup_logging
from core.providers.tools.device_mcp.proactive_policy import claim_proactive_opportunity
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
_RAIN_NEGATION_PATTERN = re.compile(
    r"(?:没有|不会|不|无|未)[\u4e00-\u9fff]{0,3}(?:暴雨|大雨|中雨|小雨|阵雨|降雨|雨)"
)


def weather_action_suggestion(conn, weather_text):
    """只根据已取得的天气文本中的明确关键词生成固定建议。"""
    if not isinstance(weather_text, str) or not weather_text:
        return ""
    rain_text = _RAIN_NEGATION_PATTERN.sub("", weather_text)
    rain_text = re.sub(r"雨(?:已经|已)?停", "", rain_text)
    rule = None
    if any(word in rain_text for word in ("暴雨", "大雨", "中雨", "小雨", "阵雨", "有雨")):
        rule = ("weather_rain", "今天可能有雨，出门记得带伞。")
    elif any(word in weather_text for word in ("高温", "酷热", "炎热")):
        rule = ("weather_heat", "今天天气较热，记得补水。")
    elif any(word in weather_text for word in ("降温", "寒潮", "骤降")):
        rule = ("weather_cooling", "今天有降温，出门记得添衣。")
    if rule and claim_proactive_opportunity(
        conn, rule[0], cooldown_seconds=12 * 3600, policy_topic="weather"
    ):
        return rule[1]
    return ""


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


async def build_daily_briefing(conn, sections, location, suggestion_topics=None):
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
            if section == "weather" and location:
                suggestion = weather_action_suggestion(conn, result)
                if suggestion:
                    available.append(suggestion)
                    if suggestion_topics is not None:
                        suggestion_topics.append("weather")
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
