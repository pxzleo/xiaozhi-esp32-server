import asyncio
import unittest
from unittest.mock import patch

from plugins_func.functions import web_search


class _FakeResponse:
    status_code = 200

    def raise_for_status(self):
        return None

    def json(self):
        return {
            "answer": "不可追溯的自动总结",
            "results": [
                {
                    "title": f"交易所公告{index}",
                    "url": f"https://example.com/notice/{index}",
                    "published_date": "2026-08-01",
                    "content": "最近交易日行情说明" * 100,
                }
                for index in range(5)
            ],
        }


class _FakeAsyncClient:
    last_payload = None

    def __init__(self, *args, **kwargs):
        pass

    async def __aenter__(self):
        return self

    async def __aexit__(self, *args):
        return None

    async def post(self, _url, json, headers):
        self.__class__.last_payload = json
        return _FakeResponse()


class WebSearchTest(unittest.TestCase):
    def test_weekend_a_share_query_gets_closure_notice(self):
        notice = web_search._market_closure_notice(
            "2026年8月2日 A股市场今日行情"
        )

        self.assertIn("星期日", notice)
        self.assertIn("A股休市", notice)

    def test_weekday_query_has_no_weekend_notice(self):
        self.assertEqual(
            web_search._market_closure_notice("2026年8月3日 A股行情"),
            "",
        )

    def test_tavily_returns_sources_instead_of_generated_answer(self):
        with patch.object(web_search.httpx, "AsyncClient", _FakeAsyncClient):
            result = asyncio.run(
                web_search._search_tavily("key", "A股行情", 5)
            )

        self.assertFalse(_FakeAsyncClient.last_payload["include_answer"])
        self.assertEqual(_FakeAsyncClient.last_payload["search_depth"], "basic")
        self.assertIn("交易所公告0", result)
        self.assertIn("2026-08-01", result)
        self.assertIn("https://example.com/notice/0", result)
        self.assertNotIn("不可追溯的自动总结", result)
        self.assertEqual(result.count("标题："), web_search.MAX_SEARCH_RESULTS)
        summaries = [
            line.split("摘要：", 1)[1]
            for line in result.splitlines()
            if "摘要：" in line
        ]
        self.assertEqual(len(summaries), web_search.MAX_SEARCH_RESULTS)
        self.assertTrue(
            all(
                len(summary) <= web_search.MAX_RESULT_CONTENT_CHARS + 1
                for summary in summaries
            )
        )

    def test_non_a_share_weekend_query_is_not_rewritten(self):
        self.assertEqual(
            web_search._market_closure_notice("2026年8月2日美股股票行情"),
            "",
        )


if __name__ == "__main__":
    unittest.main()
