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


if __name__ == "__main__":
    unittest.main()
