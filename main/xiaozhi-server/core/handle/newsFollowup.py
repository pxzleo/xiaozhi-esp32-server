import json
import re


NEWS_DETAIL_CANONICAL_TEXT = "请介绍刚才那条新闻的详情"
NEWS_EXIT_CANONICAL_TEXT = "退出"

_DETAIL_INTENT_PATTERN = re.compile(
    r"^(?:(?:不要|别|不用)退出)?请?(?:"
    r"(?:要|想|好|可以|行)?(?:了解|看看|听听|说说|讲讲|开歌)?"
    r"(?:那个|这个)(?:新闻|详情|内容)?"
    r"|(?:了解|说说|讲讲)?详情"
    r"|(?:(?:我要|我想|要|想|好的?|可以|给我)?(?:了解|听|讲|讲讲|说说))详情"
    r"|(?:(?:我想|我要|想要|请)?(?:听|了解|介绍|讲讲|说说|播放|播报一下|播报)?)"
    r"(?:刚才(?:(?:那条|这条|那个|这个)(?:新闻)?)?(?:的?(?:详情|内容))?"
    r"|(?:那个|这个|那条|这条)新闻(?:的?(?:详情|内容))?"
    r"|新闻(?:的)?(?:详情|内容))"
    r")(?:吧|呢|吗)?$"
)
_NEGATED_EXIT_PATTERN = re.compile(
    r"(?:不要|别|不用|不需要)(?:现在|马上|急着|立刻|先)?退出"
)
_EXIT_THEN_REJECTION_PATTERN = re.compile(r"^退出(?:不要|不用|别|不想|不需要).+$")
_EXIT_SUFFIX_PATTERN = re.compile(
    r"(?:退一下|退出一下|退出|关闭对话|结束对话)(?:吧|呀)?$"
)
_EXIT_SHORT_REPLY_PATTERN = re.compile(
    r"^(?:不用了|不用听了|不要了|算了|不需要了?|(?:我)?不想听了?)(?:吧|呀)?$"
)


def _normalize(value: str) -> str:
    return re.sub(r"[^0-9A-Za-z\u4e00-\u9fff]", "", value).lower()


def _is_detail_intent(normalized: str) -> bool:
    if normalized in {
        "要",
        "想",
        "好",
        "好的",
        "可以",
        "行",
        "嗯",
        "对",
        "详情",
        "详细",
        "说说",
        "讲讲",
        "好给我说说",
        "好的给我说说",
        "可以给我说说",
        "行给我说说",
    }:
        return True
    return bool(_DETAIL_INTENT_PATTERN.fullmatch(normalized))


def _is_suffix_exit_negated(normalized: str, exit_start: int) -> bool:
    prefix = normalized[:exit_start]
    if prefix.endswith("识别"):
        return False
    return bool(
        re.search(r"(?:不要|别|不用|不需要)(?:现在|马上|急着|立刻|先)?$", prefix)
    )


def classify_news_followup(content: str) -> str | None:
    """识别重大新闻播报后的单轮详情或退出回答。"""
    if not isinstance(content, str):
        return None
    normalized = _normalize(content)
    if not normalized:
        return None

    if _is_detail_intent(normalized):
        return "detail"
    if _EXIT_THEN_REJECTION_PATTERN.fullmatch(normalized):
        return "exit"
    exit_match = _EXIT_SUFFIX_PATTERN.search(normalized)
    if exit_match and not _is_suffix_exit_negated(normalized, exit_match.start()):
        return "exit"
    if _EXIT_SHORT_REPLY_PATTERN.fullmatch(normalized):
        return "exit"
    return None


def correct_news_followup_asr(raw_text):
    """在新闻追问上下文内把高置信意图规范为稳定文本，并保留ASR元数据。"""
    if isinstance(raw_text, dict):
        content = raw_text.get("content")
        intent = classify_news_followup(content)
        if intent is None:
            return raw_text
        corrected = dict(raw_text)
        corrected["content"] = (
            NEWS_DETAIL_CANONICAL_TEXT if intent == "detail" else NEWS_EXIT_CANONICAL_TEXT
        )
        return corrected

    if not isinstance(raw_text, str):
        return raw_text
    intent = classify_news_followup(raw_text)
    if intent is None:
        return raw_text
    return NEWS_DETAIL_CANONICAL_TEXT if intent == "detail" else NEWS_EXIT_CANONICAL_TEXT


def content_from_asr_text(text: str) -> str:
    if not isinstance(text, str):
        return ""
    stripped = text.strip()
    if not (stripped.startswith("{") and stripped.endswith("}")):
        return text
    try:
        value = json.loads(stripped)
    except json.JSONDecodeError:
        return text
    if isinstance(value, dict) and isinstance(value.get("content"), str):
        return value["content"]
    return text
