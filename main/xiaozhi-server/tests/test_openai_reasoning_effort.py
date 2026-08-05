import unittest
from types import SimpleNamespace
from unittest.mock import patch

from core.providers.llm.openai import openai as provider_module


class _FakeStream:
    def __init__(self, chunks):
        self._chunks = chunks
        self.closed = False

    def __iter__(self):
        return iter(self._chunks)

    def close(self):
        self.closed = True


class _CloseFailsOnceStream(_FakeStream):
    def __init__(self):
        super().__init__([])
        self.close_attempts = 0

    def close(self):
        self.close_attempts += 1
        if self.close_attempts == 1:
            raise RuntimeError("temporary close failure")
        super().close()


class OpenAIReasoningEffortTest(unittest.TestCase):
    def setUp(self):
        self.config = {
            "model_name": "ornith-1.0-35b",
            "api_key": "test-key",
            "base_url": "http://127.0.0.1:1234/v1",
            "reasoning_effort": "none",
        }

    @patch.object(provider_module.openai, "OpenAI")
    def test_response_passes_reasoning_effort(self, openai_client):
        stream = _FakeStream(
            [SimpleNamespace(choices=[SimpleNamespace(delta=SimpleNamespace(content="好"))])]
        )
        openai_client.return_value.chat.completions.create.return_value = stream
        provider = provider_module.LLMProvider(self.config)

        self.assertEqual(list(provider.response("session", [{"role": "user", "content": "你好"}])), ["好"])
        request = openai_client.return_value.chat.completions.create.call_args.kwargs
        self.assertEqual(request["reasoning_effort"], "none")
        self.assertTrue(stream.closed)

    @patch.object(provider_module.openai, "OpenAI")
    def test_function_response_passes_reasoning_effort(self, openai_client):
        stream = _FakeStream(
            [
                SimpleNamespace(
                    choices=[
                        SimpleNamespace(
                            delta=SimpleNamespace(content="好", tool_calls=None)
                        )
                    ],
                    usage=None,
                )
            ]
        )
        openai_client.return_value.chat.completions.create.return_value = stream
        provider = provider_module.LLMProvider(self.config)

        result = list(
            provider.response_with_functions(
                "session", [{"role": "user", "content": "你好"}], functions=[]
            )
        )
        self.assertEqual(result, [("好", None)])
        request = openai_client.return_value.chat.completions.create.call_args.kwargs
        self.assertEqual(request["reasoning_effort"], "none")
        self.assertTrue(stream.closed)

    @patch.object(provider_module.openai, "OpenAI")
    def test_cancelled_turn_does_not_start_request(self, openai_client):
        provider = provider_module.LLMProvider(self.config)

        result = list(
            provider.response_with_functions(
                "session",
                [{"role": "user", "content": "错误识别"}],
                functions=[],
                request_id="turn-1",
                should_cancel=lambda: True,
            )
        )

        self.assertEqual(result, [])
        openai_client.return_value.chat.completions.create.assert_not_called()

    @patch.object(provider_module.openai, "OpenAI")
    def test_cancel_response_closes_active_stream(self, openai_client):
        stream = _FakeStream(
            [
                SimpleNamespace(
                    choices=[
                        SimpleNamespace(
                            delta=SimpleNamespace(content="旧回复", tool_calls=None)
                        )
                    ],
                    usage=None,
                )
            ]
        )
        openai_client.return_value.chat.completions.create.return_value = stream
        provider = provider_module.LLMProvider(self.config)
        response = provider.response_with_functions(
            "session",
            [{"role": "user", "content": "旧的错误识别"}],
            functions=[],
            request_id="turn-1",
        )

        self.assertEqual(next(response), ("旧回复", None))
        self.assertTrue(provider.cancel_response("session", "turn-1"))
        self.assertTrue(stream.closed)
        response.close()

    @patch.object(provider_module.openai, "OpenAI")
    def test_out_of_order_stream_registration_keeps_new_turn(self, openai_client):
        provider = provider_module.LLMProvider(self.config)
        old_stream = _FakeStream([])
        new_stream = _FakeStream([])

        provider._register_stream("session", "new-turn", new_stream)
        provider._register_stream("session", "old-turn", old_stream)

        self.assertFalse(old_stream.closed)
        self.assertFalse(new_stream.closed)
        self.assertTrue(provider.cancel_response("session", "old-turn"))
        self.assertTrue(old_stream.closed)
        self.assertFalse(new_stream.closed)
        self.assertTrue(provider.cancel_response("session", "new-turn"))

    @patch.object(provider_module.openai, "OpenAI")
    def test_failed_close_remains_registered_for_retry(self, openai_client):
        provider = provider_module.LLMProvider(self.config)
        stream = _CloseFailsOnceStream()
        provider._register_stream("session", "turn-1", stream)

        self.assertFalse(provider.cancel_response("session", "turn-1"))
        self.assertTrue(
            provider._is_registered_stream("session", "turn-1", stream)
        )
        self.assertTrue(provider.cancel_response("session", "turn-1"))
        self.assertTrue(stream.closed)


if __name__ == "__main__":
    unittest.main()
