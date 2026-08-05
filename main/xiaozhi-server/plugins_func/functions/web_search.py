import httpx
import re
from datetime import date
from config.logger import setup_logging
from plugins_func.register import (
    register_function,
    ToolType,
    ActionResponse,
    Action,
)
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from core.connection import ConnectionHandler

TAG = __name__
logger = setup_logging()
MAX_SEARCH_RESULTS = 3
MAX_RESULT_CONTENT_CHARS = 500

_DEFAULT_DESCRIPTION = (
    "联网搜索工具。当用户明确需要联网搜索问题时使用此工具。"
)

WEB_SEARCH_FUNCTION_DESC = {
    "type": "function",
    "function": {
        "name": "web_search",
        "description": _DEFAULT_DESCRIPTION,
        "parameters": {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "搜索关键词或问题",
                }
            },
            "required": ["query"],
        },
    },
}


async def _search_metaso(api_key: str, query: str, max_results: int) -> str:
    """调用秘塔搜索API"""
    url = "https://metaso.cn/api/v1/search"
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }
    payload = {
        "q": query,
        "size": max_results,
        "stream": False,
        "scope": "webpage",
        "includeSummary": True,
        "includeRawContent": False,
        "conciseSnippet": False,
    }
    logger.bind(tag=TAG).debug(f"秘塔搜索请求 | URL: {url} | payload: {payload}")
    async with httpx.AsyncClient(timeout=httpx.Timeout(15.0, connect=3.0)) as client:
        response = await client.post(url, json=payload, headers=headers)
    data = response.json()
    logger.bind(tag=TAG).debug(f"秘塔搜索响应 | status: {response.status_code}")

    webpages = data.get("webpages", [])
    if not webpages:
        return "未找到相关搜索结果。"

    lines = ["【联网搜索结果】"]
    for i, item in enumerate(webpages, 1):
        title = item.get("title", "无标题")
        snippet = item.get("summary", "")
        date = item.get("date", "")
        lines.append(f"{i}. 标题：{title}")
        if date:
            lines.append(f"   日期：{date}")
        if snippet:
            lines.append(f"   摘要：{snippet}")

    return "\n".join(lines)


async def _search_tavily(api_key: str, query: str, max_results: int) -> str:
    """调用Tavily搜索API"""
    url = "https://api.tavily.com/search"
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }
    payload = {
        "query": query,
        "max_results": max_results,
        # 语音交互优先首包速度；原来的 advanced 检索在实测中
        # 单次约需 7 秒，basic 仍会返回可追溯的原始来源。
        "search_depth": "basic",
        # Tavily 的 answer 是二次生成内容，无法核对来源日期；这里只返回
        # 原始搜索结果，让后续模型基于可追溯来源回答。
        "include_answer": False,
        "include_raw_content": False,
    }
    logger.bind(tag=TAG).debug(f"Tavily搜索请求 | URL: {url} | payload: {payload}")
    async with httpx.AsyncClient(timeout=httpx.Timeout(15.0, connect=3.0)) as client:
        response = await client.post(url, json=payload, headers=headers)
    response.raise_for_status()
    data = response.json()
    logger.bind(tag=TAG).debug(f"Tavily搜索响应 | status: {response.status_code} | data: {data}")

    results = data.get("results", [])[:MAX_SEARCH_RESULTS]
    if not results:
        return "未找到相关搜索结果。"

    lines = ["【联网搜索结果】"]
    for index, item in enumerate(results, 1):
        title = item.get("title") or "无标题"
        url = item.get("url") or ""
        published_date = item.get("published_date") or item.get("publishedDate") or ""
        summary = re.sub(r"\s+", " ", item.get("content") or "").strip()
        if len(summary) > MAX_RESULT_CONTENT_CHARS:
            summary = f"{summary[:MAX_RESULT_CONTENT_CHARS].rstrip()}…"
        lines.append(f"{index}. 标题：{title}")
        if published_date:
            lines.append(f"   日期：{published_date}")
        if url:
            lines.append(f"   来源：{url}")
        if summary:
            lines.append(f"   摘要：{summary}")

    return "\n".join(lines)


def _market_closure_notice(query: str) -> str:
    """对查询中明确落在周末的 A 股日期给出确定性休市提示。"""
    if not re.search(
        r"A股|中国(?:大陆)?股市|沪深|上证|深证",
        query,
        re.IGNORECASE,
    ):
        return ""

    match = re.search(r"(\d{4})[年/-](\d{1,2})[月/-](\d{1,2})日?", query)
    if not match:
        return ""

    try:
        query_date = date(*(int(value) for value in match.groups()))
    except ValueError:
        return ""

    if query_date.weekday() < 5:
        return ""

    weekday = "星期六" if query_date.weekday() == 5 else "星期日"
    return (
        f"重要校验：{query_date.isoformat()} 是{weekday}，A股休市，"
        "不存在该日收盘行情。回答时必须明确休市，并把行情数据归属到最近交易日。"
    )


@register_function("web_search", WEB_SEARCH_FUNCTION_DESC, ToolType.SYSTEM_CTL)
async def web_search(conn: "ConnectionHandler", query: str = None):
    logger.bind(tag=TAG).info(f"web_search 被调用 | query={query}")
    if not query:
        return ActionResponse(Action.REQLLM, "请提供搜索关键词。", None)

    web_search_config = conn.config.get("plugins", {}).get("web_search", {})
    provider = web_search_config.get("provider", "").lower()
    configured_max_results = int(web_search_config.get("max_results", 3))
    max_results = max(1, min(configured_max_results, MAX_SEARCH_RESULTS))
    logger.bind(tag=TAG).info(f"web_search 配置 | provider={provider} | max_results={max_results} | config_keys={list(web_search_config.keys())}")

    api_key = web_search_config.get("api_key", "")
    if not api_key:
        return ActionResponse(
            Action.REQLLM,
            "联网搜索功能未配置API Key，请在配置文件中填写。",
            None,
        )

    closure_notice = _market_closure_notice(query)
    effective_query = query
    if closure_notice:
        effective_query = f"{query} 最近一个交易日行情 交易所或主流财经来源"

    try:
        if provider == "metaso":
            result_text = await _search_metaso(api_key, effective_query, max_results)
        elif provider == "tavily":
            result_text = await _search_tavily(api_key, effective_query, max_results)
        else:
            return ActionResponse(
                Action.REQLLM,
                f"联网搜索功能未配置或配置的搜索源无效（当前：{provider}），请检查配置。",
                None,
            )
        if closure_notice:
            result_text = f"{closure_notice}\n{result_text}"
        logger.bind(tag=TAG).info(f"搜索结果组装完成:\n{result_text}")
    except httpx.TimeoutException:
        logger.bind(tag=TAG).error("联网搜索请求超时")
        result_text = "联网搜索请求超时，请稍后重试。"
    except httpx.HTTPStatusError as e:
        logger.bind(tag=TAG).error(f"联网搜索请求失败: {e}")
        result_text = "联网搜索请求失败，请稍后重试。"
    except Exception as e:
        logger.bind(tag=TAG).error(f"联网搜索异常: {e}")
        result_text = "联网搜索出现异常，请稍后重试。"

    return ActionResponse(Action.REQLLM, result_text, None)
