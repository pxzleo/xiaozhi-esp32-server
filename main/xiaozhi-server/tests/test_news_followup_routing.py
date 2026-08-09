import asyncio
import json
import queue
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch

from core.handle import intentHandler
from core.handle.newsFollowup import (
    NEWS_DETAIL_CANONICAL_TEXT,
    NEWS_EXIT_CANONICAL_TEXT,
    classify_news_followup,
    correct_news_followup_asr,
)
from core.providers.asr.base import ASRProviderBase
from plugins_func.functions.get_news_from_newsnow import get_news_from_newsnow


class FakeNewsFollowupASR(ASRProviderBase):
    def __init__(self, result):
        super().__init__()
        self.output_dir = "/tmp"
        self.delete_audio_file = True
        self.result = result

    async def speech_to_text(self, opus_data, session_id, artifacts=None):
        return self.result, None


class NewsFollowupCorrectionTest(unittest.TestCase):
    def test_homophone_news_reply_is_corrected_with_metadata_preserved(self):
        raw = {"content": "要开歌那个。", "language": "zh", "emotion": "😶"}

        corrected = correct_news_followup_asr(raw)

        self.assertEqual(NEWS_DETAIL_CANONICAL_TEXT, corrected["content"])
        self.assertEqual("zh", corrected["language"])
        self.assertEqual("😶", corrected["emotion"])
        self.assertEqual("要开歌那个。", raw["content"])

    def test_exit_reply_is_corrected_to_direct_exit_command(self):
        self.assertEqual(
            NEWS_EXIT_CANONICAL_TEXT,
            correct_news_followup_asr("你没有让你放歌呀，退一下吧。"),
        )

    def test_explicit_music_request_is_not_rewritten(self):
        text = "我要播放网易云音乐"
        self.assertIsNone(classify_news_followup(text))
        self.assertEqual(text, correct_news_followup_asr(text))

    def test_music_action_wins_when_exit_words_are_also_present(self):
        for text in (
            "我不想听新闻了，播放网易云音乐",
            "我不想听新闻了，播放周杰伦的稻香",
            "我不想听新闻了，给我放周杰伦的歌",
            "我不想听新闻了，听歌",
            "退出新闻，听周杰伦",
            "不想听这个新闻，放周杰伦的歌",
            "退一下，播一首晴天",
            "算了，放首歌吧",
            "不想听了，放首歌",
            "退出，播放稻香",
            "先退出新闻，再播放稻香",
            "我不想听新闻了播放网易云音乐",
            "先退出新闻再播放稻香",
            "不想听了然后放首歌",
        ):
            with self.subTest(text=text):
                self.assertIsNone(classify_news_followup(text))

    def test_music_mention_does_not_hide_real_exit(self):
        for text in (
            "你没有让你放歌呀，退一下吧",
            "不要播放音乐了，退出",
            "刚才说不要播放那首歌，退出",
            "新闻播放完了，退出",
            "这个新闻播放完了，退出",
            "播报结束对话",
            "播放音乐不是我的意思退出",
            "播放音乐这几个字是误识别退出",
            "退出不要详情",
            "退出不要再播放音乐",
        ):
            with self.subTest(text=text):
                self.assertEqual("exit", classify_news_followup(text))

    def test_unrelated_broad_requests_are_not_news_detail(self):
        for text in (
            "讲讲量子力学",
            "详细介绍广州天气",
            "说说别的新闻",
            "今天还有什么新闻",
            "我想听其他新闻",
            "换一条新闻",
            "播放新闻联播",
            "这条路怎么走",
            "我想走这条路",
            "那条裤子好看吗",
            "要买那个裤子",
            "这个新闻应用怎么用",
            "那个新闻网站打不开",
            "不要讲详情",
            "不想听详情",
            "不需要详情",
            "我不想听新闻详情了",
            "不要了这个新闻详情",
            "不用了不用讲详情",
            "不要播放新闻详情",
            "我的订单详情在哪",
            "这个详情页面怎么用",
            "新闻详情设置在哪里",
            "不需要退出",
            "不要现在退出",
            "别急着退出",
            "不用立刻退出",
            "别急着退出我还想听",
            "这首歌不用听了",
            "网易云音乐我不想听",
            "周杰伦我不想听",
            "这个功能不需要了",
            "会议不用听了",
            "听说不用了",
            "播放音乐之后不用了",
            "放心不用了",
            "听完了不用了",
            "听到了不需要",
        ):
            with self.subTest(text=text):
                self.assertIsNone(classify_news_followup(text))

    def test_explicit_news_references_are_not_mistaken_for_music(self):
        for text in (
            "我想听这个新闻的详情",
            "我要听那个新闻详情",
            "请播放刚才那条新闻",
            "播报一下刚才那条新闻的详情",
            "请播报刚才那条新闻详情",
            "请讲讲那个",
            "不要退出请讲讲那个",
            "别退出讲讲那个",
            "要了解详情",
            "想了解详情",
            "我要了解详情",
            "我想听详情",
            "好的讲讲详情",
            "可以讲讲详情",
            "给我讲讲详情",
            "要讲详情",
            "我要讲详情",
            "我想讲详情",
            "好的讲详情",
            "给我讲详情",
        ):
            with self.subTest(text=text):
                self.assertEqual("detail", classify_news_followup(text))

    def test_negated_exit_can_still_request_news_detail(self):
        self.assertEqual("detail", classify_news_followup("不要退出，讲讲详情"))

    def test_colloquial_affirmative_requests_news_detail(self):
        self.assertEqual("detail", classify_news_followup("好，给我说说"))

    def test_short_explicit_exit_variants(self):
        for text in ("不想听", "我不想听", "不需要了", "退出一下", "不用听了"):
            with self.subTest(text=text):
                self.assertEqual("exit", classify_news_followup(text))


class NewsFollowupIntentTest(unittest.IsolatedAsyncioTestCase):
    def connection(self):
        logger = Mock()
        logger.bind.return_value = logger
        return SimpleNamespace(
            _external_news_waiting_response=True,
            logger=logger,
            cmd_exit=["退出", "关闭"],
            intent_type="function_call",
            current_speaker=None,
        )

    async def test_detail_reply_bypasses_general_tool_selection(self):
        conn = self.connection()
        process = AsyncMock(return_value=True)
        with patch.object(intentHandler, "process_intent_result", process):
            handled = await intentHandler.handle_user_intent(
                conn,
                json.dumps(
                    {"content": NEWS_DETAIL_CANONICAL_TEXT, "language": "zh"},
                    ensure_ascii=False,
                ),
            )

        self.assertTrue(handled)
        self.assertFalse(conn._external_news_waiting_response)
        intent_payload = json.loads(process.await_args.args[1])
        self.assertEqual(
            "get_news_from_newsnow",
            intent_payload["function_call"]["name"],
        )
        self.assertEqual(
            {"detail": True, "lang": "zh_CN"},
            intent_payload["function_call"]["arguments"],
        )

    async def test_exit_reply_closes_through_direct_exit_path(self):
        conn = self.connection()
        conn.cmd_exit = ["再见"]
        conn.close_after_chat = False
        conn.tts = SimpleNamespace(
            tts_text_queue=queue.Queue(), tts_one_sentence=Mock()
        )
        with patch.object(intentHandler, "send_stt_message", AsyncMock()) as send:
            handled = await intentHandler.handle_user_intent(
                conn, NEWS_EXIT_CANONICAL_TEXT
            )

        self.assertTrue(handled)
        self.assertFalse(conn._external_news_waiting_response)
        self.assertTrue(conn.close_after_chat)
        send.assert_awaited_once_with(conn, "退出")
        first = conn.tts.tts_text_queue.get_nowait()
        last = conn.tts.tts_text_queue.get_nowait()
        self.assertEqual("FIRST", first.sentence_type.value)
        self.assertEqual("LAST", last.sentence_type.value)
        conn.tts.tts_one_sentence.assert_called_once()
        self.assertEqual("再见", conn.tts.tts_one_sentence.call_args.kwargs["content_detail"])

    async def test_explicit_music_request_consumes_context_and_stays_general(self):
        conn = self.connection()
        with patch.object(
            intentHandler, "checkWakeupWords", AsyncMock(return_value=False)
        ):
            handled = await intentHandler.handle_user_intent(
                conn, "我要播放网易云音乐"
            )

        self.assertFalse(handled)
        self.assertFalse(conn._external_news_waiting_response)


class NewsFollowupASRIntegrationTest(unittest.IsolatedAsyncioTestCase):
    async def test_asr_hook_corrects_only_the_waiting_followup_round(self):
        provider = FakeNewsFollowupASR(
            {"content": "要开歌那个。", "language": "zh", "emotion": "😶"}
        )
        conn = SimpleNamespace(
            voiceprint_provider=None,
            session_id="news-followup",
            _external_news_waiting_response=True,
        )

        async def consume_first_round(current_conn, text):
            current_conn._external_news_waiting_response = False

        with patch(
            "core.providers.asr.base.startToChat",
            AsyncMock(side_effect=consume_first_round),
        ) as start_chat, patch("core.providers.asr.base.enqueue_asr_report"):
            await provider.handle_voice_stop(conn, [b"\x00\x00" * 100])
            await provider.handle_voice_stop(conn, [b"\x00\x00" * 100])

        first = json.loads(start_chat.await_args_list[0].args[1])
        second = json.loads(start_chat.await_args_list[1].args[1])
        self.assertEqual(NEWS_DETAIL_CANONICAL_TEXT, first["content"])
        self.assertEqual("要开歌那个。", second["content"])
        self.assertEqual("zh", first["language"])


class NewsFollowupLinkIsolationTest(unittest.IsolatedAsyncioTestCase):
    async def test_news_detail_uses_each_connections_own_authoritative_link(self):
        first_conn = SimpleNamespace(
            config={},
            last_newsnow_link={
                "url": "https://news.example/first",
                "title": "第一条新闻",
                "source_id": "thepaper",
            },
        )
        second_conn = SimpleNamespace(
            config={},
            last_newsnow_link={
                "url": "https://news.example/second",
                "title": "第二条新闻",
                "source_id": "baidu",
            },
        )

        async def detail_for_url(url):
            return f"正文来自 {url}"

        with patch(
            "plugins_func.functions.get_news_from_newsnow.fetch_news_detail",
            AsyncMock(side_effect=detail_for_url),
        ):
            first, second = await asyncio.gather(
                get_news_from_newsnow(first_conn, detail=True),
                get_news_from_newsnow(second_conn, detail=True),
            )

        self.assertIn("第一条新闻", first.result)
        self.assertIn("https://news.example/first", first.result)
        self.assertNotIn("第二条新闻", first.result)
        self.assertIn("第二条新闻", second.result)
        self.assertIn("https://news.example/second", second.result)
        self.assertNotIn("第一条新闻", second.result)


if __name__ == "__main__":
    unittest.main()
