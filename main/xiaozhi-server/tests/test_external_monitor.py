import asyncio
import json
import unittest
from datetime import datetime, timedelta, timezone
from unittest.mock import AsyncMock, MagicMock, patch

from config.manage_api_client import (
    ManageApiError,
    ManageApiBusinessError,
    ManageApiTimeoutError,
    _validate_classifier_output,
)
from config import manage_api_client
from core.proactive_monitor.runner import (
    ExternalMonitorRunner,
    MonitorError,
    MonitorProcessResult,
    QWeatherClient,
    _append_news_fingerprint,
    _authoritative_dedupe_recorded_at,
    _build_news_state,
    _log_monitor_decision,
    _news_incident_id,
    _normalized_hourly,
    _validate_monitor_state,
    detect_weather_hazards,
    normalize_news_url,
    prefilter_news,
    process_news,
    process_weather,
)


def hourly(icon="100", *, pop=0, wind=10, temp=20, count=24):
    return [
        {
            "fxTime": f"2026-08-{9 + index // 24:02d}T{index % 24:02d}:00+00:00",
            "icon": icon,
            "pop": str(pop),
            "windSpeed": str(wind),
            "temp": str(temp - index // 12),
            "precip": "0",
        }
        for index in range(count)
    ]


def weather_task(state=None):
    return {
        "device_id": "device-1",
        "mac_address": "AA:BB",
        "monitor_type": "weather",
        "config": {
            "hazard_types": [
                "rainstorm", "thunderstorm", "hail", "blizzard", "high_wind",
                "high_temperature", "low_temperature", "temperature_drop",
            ],
            "minimum_warning_severity": "moderate",
            "precip_probability": 70,
            "wind_speed_kmh": 62,
            "high_temp_c": 35,
            "low_temp_c": 0,
            "temp_drop_24h_c": 8,
            "forecast_hours": 6,
            "cooldown_minutes": 720,
        },
        "state": state or {},
        "weather_location": "广州",
        "weather_location_error": None,
        "weather_api_host": "https://weather.example.com",
        "weather_auth_type": "api_key",
        "weather_credential": "secret",
        "weather_credentials_error": None,
    }


def news_task(state=None):
    return {
        "device_id": "device-1",
        "mac_address": "AA:BB",
        "monitor_type": "news",
        "config": {
            "confidence": 0.85,
            "categories": ["public_safety"],
            "cooldown_minutes": 120,
            "dedupe_hours": 24,
        },
        "state": state or {},
        "news_sources": ["澎湃新闻", "财联社"],
        "news_sources_error": None,
    }


class FakeWeather:
    def __init__(self, warnings, values):
        self.warnings = warnings
        self.values = values

    async def snapshot(self, task, location):
        return {"id": "101280101", "name": location}, self.warnings, self.values


class FakeNews:
    def __init__(self, items):
        self.items = items

    async def fetch(self, source):
        return self.items[source]


class WeatherDetectionTest(unittest.IsolatedAsyncioTestCase):
    def test_weather_credentials_support_api_key_and_bearer_without_fallback(self):
        client = QWeatherClient()
        base_url, headers = client._access(weather_task())
        self.assertEqual("https://weather.example.com", base_url)
        self.assertEqual("secret", headers["X-QW-Api-Key"])
        bearer = weather_task()
        bearer.update({"weather_auth_type": "bearer", "weather_credential": "jwt"})
        _, headers = client._access(bearer)
        self.assertEqual("Bearer jwt", headers["Authorization"])
        missing = weather_task()
        missing["weather_credentials_error"] = "weather_credentials_missing"
        with self.assertRaises(MonitorError):
            client._access(missing)

    async def test_first_normal_forecast_only_builds_baseline(self):
        client = FakeWeather([], hourly(icon="310", pop=70))
        with patch("core.proactive_monitor.runner.create_proactive_monitor_event", AsyncMock()) as create:
            result = await process_weather(weather_task(), client)
            state, created = result
        self.assertEqual([], created)
        self.assertIn("rainstorm", state["detection_status"]["active_hazards"])
        create.assert_not_awaited()
        self.assertEqual(
            ("weather", "no_notification", "initial_baseline"),
            ("weather", result.decision["outcome"], result.decision["reason"]),
        )
        self.assertEqual(["rainstorm"], result.decision["details"]["hazard_types"])

    async def test_first_active_severe_warning_is_immediate_and_deduped(self):
        warning = {
            "id": "warning-1", "severity": "severe",
            "messageType": {"code": "alert", "supersedes": []},
            "headline": "暴雨红色预警", "description": "请减少外出",
            "effectiveTime": "2026-01-01T00:00:00+00:00",
            "expireTime": "2099-01-01T00:00:00+00:00",
        }
        client = FakeWeather([warning], hourly())
        with patch("core.proactive_monitor.runner.create_proactive_monitor_event", AsyncMock()) as create:
            state, created = await process_weather(weather_task(), client)
            self.assertEqual(1, len(created))
            stored = create.await_args.args[0]
            self.assertEqual("critical", stored["priority"])
            bounded = {key: value for key, value in stored["payload"].items() if key != "reference_url"}
            self.assertLessEqual(len(json.dumps(bounded, ensure_ascii=False, separators=(",", ":")).encode()), 512)
            second_task = weather_task(state)
            _, second_created = await process_weather(second_task, client)
        self.assertEqual([], second_created)
        self.assertEqual(1, create.await_count)

    async def test_first_moderate_warning_is_baselined_and_not_spoken_next_round(self):
        warning = {
            "id": "warning-m", "severity": "moderate",
            "messageType": {"code": "alert", "supersedes": []},
            "headline": "黄色预警", "effectiveTime": "2026-01-01T00:00:00+00:00",
            "expireTime": "2099-01-01T00:00:00+00:00",
        }
        client = FakeWeather([warning], hourly())
        with patch("core.proactive_monitor.runner.create_proactive_monitor_event", AsyncMock()) as create:
            state, first = await process_weather(weather_task(), client)
            _, second = await process_weather(weather_task(state), client)
        self.assertEqual([], first)
        self.assertEqual([], second)
        self.assertTrue(any(value.startswith("w:") for value in state["fingerprints"]))
        create.assert_not_awaited()

    async def test_more_than_sixteen_long_moderate_warnings_stay_baselined(self):
        warnings = [{
            "id": f"official-warning-{index}-" + "x" * 120,
            "severity": "moderate", "messageType": {"code": "alert"},
            "headline": "黄色预警", "effectiveTime": None,
            "issuedTime": "2026-01-01T00:00:00+00:00",
            "expireTime": "2099-01-01T00:00:00+00:00",
        } for index in range(20)]
        client = FakeWeather(warnings, hourly())
        with patch("core.proactive_monitor.runner.create_proactive_monitor_event", AsyncMock()) as create:
            state, first = await process_weather(weather_task(), client)
            _, second = await process_weather(weather_task(state), client)
        self.assertEqual([], first)
        self.assertEqual([], second)
        self.assertLessEqual(len(json.dumps(state, ensure_ascii=False).encode()), 4096)
        self.assertEqual(20, len(state["detection_status"]["active_warning_ids"]))
        create.assert_not_awaited()

    async def test_cancel_and_recovery_only_update_state(self):
        prior = {
            "schema_version": 1,
            "fingerprints": ["warning:warning-1:severe"],
            "detection_status": {
                "active_warning_ids": ["warning-1"], "active_hazards": ["rainstorm"]
            },
            "baseline": {"captured_at": "2026-01-01T00:00:00+00:00", "location_id": "101280101"},
        }
        cancelled = {
            "id": "cancel-1", "severity": "severe",
            "messageType": {"code": "cancel", "supersedes": ["warning-1"]},
        }
        with patch("core.proactive_monitor.runner.create_proactive_monitor_event", AsyncMock()) as create:
            state, created = await process_weather(weather_task(prior), FakeWeather([cancelled], hourly()))
        self.assertEqual([], created)
        self.assertEqual([], state["detection_status"]["active_warning_ids"])
        self.assertEqual([], state["detection_status"]["active_hazards"])
        create.assert_not_awaited()

    async def test_forecast_deterioration_and_warning_upgrade_create_events(self):
        baseline, _ = await process_weather(weather_task(), FakeWeather([], hourly()))
        with patch("core.proactive_monitor.runner.create_proactive_monitor_event", AsyncMock()) as create:
            _, created = await process_weather(
                weather_task(baseline), FakeWeather([], hourly(icon="310", pop=70))
            )
        self.assertEqual(1, len(created))
        self.assertEqual("high", create.await_args.args[0]["priority"])
        self.assertEqual("rolling_window", create.await_args.args[0]["dedupe_policy"])
        self.assertEqual(12, create.await_args.args[0]["dedupe_window_hours"])

        warning_prior = weather_task()["state"] = {
            "schema_version": 1,
            "fingerprints": ["warning:w1:moderate"],
            "detection_status": {"active_warning_ids": ["w1"], "active_hazards": []},
            "baseline": {"captured_at": "2026-01-01T00:00:00+00:00", "location_id": "101280101"},
        }
        upgraded = {
            "id": "w1", "severity": "severe", "messageType": {"code": "update"},
            "headline": "预警升级", "effectiveTime": "2026-01-01T00:00:00+00:00",
            "expireTime": "2099-01-01T00:00:00+00:00",
        }
        with patch("core.proactive_monitor.runner.create_proactive_monitor_event", AsyncMock()) as create:
            _, created = await process_weather(weather_task(warning_prior), FakeWeather([upgraded], hourly()))
        self.assertEqual(1, len(created))
        self.assertEqual("critical", create.await_args.args[0]["priority"])

    async def test_weather_multi_event_created_and_deduped_has_mixed_reason(self):
        prior = {
            "schema_version": 1,
            "fingerprints": [],
            "detection_status": {"active_warning_ids": [], "active_hazards": []},
            "baseline": {
                "captured_at": "2026-01-01T00:00:00+00:00",
                "location_id": "101280101",
            },
        }
        warning = {
            "id": "warning-mixed", "severity": "severe",
            "messageType": {"code": "alert"}, "headline": "预警升级",
            "effectiveTime": "2026-01-01T00:00:00+00:00",
            "expireTime": "2099-01-01T00:00:00+00:00",
        }
        persistence = [
            {"created": True, "deduped": False, "event": {}, "authoritative_event_id": "new"},
            {"created": False, "deduped": True, "event": {}, "authoritative_event_id": "old"},
        ]
        with patch(
            "core.proactive_monitor.runner.create_proactive_monitor_event",
            AsyncMock(side_effect=persistence),
        ):
            result = await process_weather(
                weather_task(prior), FakeWeather([warning], hourly(icon="310", pop=70))
            )
        self.assertEqual(2, len(result.created_event_ids))
        self.assertEqual("notification_created_and_deduped", result.decision["outcome"])
        self.assertTrue(result.decision["reason"].endswith("_created_and_deduped"))
        self.assertEqual(1, result.decision["details"]["created_count"])
        self.assertEqual(1, result.decision["details"]["deduped_count"])

    def test_threshold_boundaries_and_temperature_drop(self):
        values = hourly(pop=70, wind=62, temp=35)
        values[-1]["temp"] = "27"
        values = _normalized_hourly(values)
        hazards = {item["type"] for item in detect_weather_hazards(values, weather_task()["config"])}
        self.assertTrue({"rainstorm", "high_wind", "high_temperature", "temperature_drop"} <= hazards)
        cold = _normalized_hourly(hourly(temp=0))
        self.assertIn("low_temperature", {item["type"] for item in detect_weather_hazards(cold, weather_task()["config"])})
        missing_temp = hourly(temp=20)
        for item in missing_temp:
            item.pop("temp")
        with self.assertRaisesRegex(MonitorError, "逐小时天气必填字段无效"):
            _normalized_hourly(missing_temp)

    async def test_invalid_warning_expiry_is_not_treated_as_active(self):
        warning = {
            "id": "warning-invalid", "severity": "extreme",
            "messageType": {"code": "alert"}, "headline": "无效预警",
            "effectiveTime": "invalid", "expireTime": "invalid",
        }
        with patch("core.proactive_monitor.runner.create_proactive_monitor_event", AsyncMock()) as create:
            with self.assertRaisesRegex(MonitorError, "官方天气预警生效时间无效"):
                await process_weather(weather_task(), FakeWeather([warning], hourly()))
        create.assert_not_awaited()

    async def test_null_effective_time_uses_issued_time(self):
        warning = {
            "id": "warning-null-effective", "severity": "severe",
            "messageType": {"code": "alert"}, "headline": "红色预警",
            "effectiveTime": None, "issuedTime": "2026-01-01T00:00:00+00:00",
            "expireTime": "2099-01-01T00:00:00+00:00",
        }
        with patch("core.proactive_monitor.runner.create_proactive_monitor_event", AsyncMock()) as create:
            _, created = await process_weather(weather_task(), FakeWeather([warning], hourly()))
        self.assertEqual(1, len(created))
        create.assert_awaited_once()

    async def test_invalid_issued_time_is_rejected_when_effective_is_null(self):
        warning = {
            "id": "warning-invalid-issued", "severity": "severe",
            "messageType": {"code": "alert"}, "headline": "红色预警",
            "effectiveTime": None, "issuedTime": "invalid",
            "expireTime": "2099-01-01T00:00:00+00:00",
        }
        with self.assertRaisesRegex(MonitorError, "官方天气预警发布时间无效"):
            await process_weather(weather_task(), FakeWeather([warning], hourly()))

    def test_nonempty_invalid_optional_hourly_number_is_rejected(self):
        values = hourly()
        values[0]["pop"] = "unknown"
        with self.assertRaisesRegex(MonitorError, "可空数值字段无效"):
            _normalized_hourly(values)

    async def test_weather_401_and_403_are_non_retryable_permission_errors(self):
        client = QWeatherClient()
        for status in (401, 403):
            response = MagicMock()
            response.status_code = status
            response.raise_for_status.side_effect = __import__("httpx").HTTPStatusError(
                "denied", request=__import__("httpx").Request("GET", "https://weather.example.com/x"),
                response=__import__("httpx").Response(status),
            )
            http = AsyncMock()
            http.get.return_value = response
            context = AsyncMock()
            context.__aenter__.return_value = http
            with patch("core.proactive_monitor.runner.httpx.AsyncClient", return_value=context):
                with self.assertRaises(MonitorError) as caught:
                    await client._get("https://weather.example.com", {}, "/x")
            self.assertEqual("weather_api_denied", caught.exception.code)
            self.assertFalse(caught.exception.retryable)

    async def test_weather_timeout_is_retryable_and_city_resolution_is_cached(self):
        client = QWeatherClient()
        client._get = AsyncMock(return_value={
            "location": [{"id": "101280101", "name": "广州", "lat": "23.13", "lon": "113.27"}]
        })
        first = await client.resolve_location("广州", "https://weather.example.com", {})
        second = await client.resolve_location("广州", "https://weather.example.com", {})
        self.assertEqual(first, second)
        client._get.assert_awaited_once()

        timeout_client = QWeatherClient()
        http = AsyncMock()
        http.get.side_effect = __import__("httpx").ReadTimeout("timeout")
        context = AsyncMock()
        context.__aenter__.return_value = http
        with patch("core.proactive_monitor.runner.httpx.AsyncClient", return_value=context):
            with self.assertRaises(MonitorError) as caught:
                await timeout_client._get("https://weather.example.com", {}, "/x")
        self.assertEqual("weather_timeout", caught.exception.code)
        self.assertTrue(caught.exception.retryable)

    def test_state_is_rejected_before_persistence_when_over_4kb(self):
        state = {
            "schema_version": 1,
            "fingerprints": ["x" * 160 for _ in range(30)],
            "detection_status": {},
        }
        with self.assertRaisesRegex(MonitorError, "超过4096字节"):
            _validate_monitor_state("news", state)

    async def test_new_hazard_during_cooldown_remains_eligible_after_cooldown(self):
        future = (datetime.now(timezone.utc) + timedelta(hours=1)).isoformat()
        prior = {
            "schema_version": 1, "fingerprints": [],
            "detection_status": {"active_warning_ids": [], "active_hazards": [], "cooldown_until": future},
            "baseline": {"captured_at": "2026-01-01T00:00:00+00:00", "location_id": "101280101"},
        }
        rainy = FakeWeather([], hourly(icon="310", pop=70))
        with patch("core.proactive_monitor.runner.create_proactive_monitor_event", AsyncMock()) as create:
            state, during = await process_weather(weather_task(prior), rainy)
            state["detection_status"]["cooldown_until"] = "2026-01-01T00:00:00+00:00"
            _, after = await process_weather(weather_task(state), rainy)
        self.assertEqual([], during)
        self.assertNotIn("rainstorm", state["detection_status"]["active_hazards"])
        self.assertEqual(1, len(after))
        self.assertEqual(1, create.await_count)

    async def test_location_change_rebuilds_ordinary_baseline_without_alert(self):
        prior = {
            "schema_version": 1, "fingerprints": [],
            "detection_status": {"active_warning_ids": [], "active_hazards": []},
            "baseline": {"captured_at": "2026-01-01T00:00:00+00:00", "location_id": "old-city"},
        }
        with patch("core.proactive_monitor.runner.create_proactive_monitor_event", AsyncMock()) as create:
            state, created = await process_weather(
                weather_task(prior), FakeWeather([], hourly(icon="310", pop=70))
            )
        self.assertEqual([], created)
        self.assertEqual("101280101", state["baseline"]["location_id"])
        create.assert_not_awaited()


class NewsDetectionTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.items = {
            "澎湃新闻": [
                {"title": "某地发生强烈地震启动紧急响应", "url": "https://news.example/a?utm=x"},
                {"title": "明星演唱会现场夺冠热搜", "url": "https://news.example/fun"},
            ],
            "财联社": [
                {"title": "某地发生强烈地震启动紧急响应", "url": "https://news.example/a"},
            ],
        }

    def test_prefilter_clusters_sources_excludes_entertainment_and_normalizes_url(self):
        stats = {}
        candidates = prefilter_news(self.items, stats)
        self.assertEqual(1, len(candidates))
        self.assertEqual(["澎湃新闻", "财联社"], candidates[0]["sources"])
        self.assertEqual(1, stats["prefilter_rejection_counts"]["excluded_topic"])
        self.assertEqual("https://news.example/a", normalize_news_url("https://news.example/a?utm=x#x"))
        self.assertEqual("", normalize_news_url("https://user:password@news.example/a"))

    async def test_first_run_builds_news_baseline_without_classifier(self):
        with patch("core.proactive_monitor.runner.evaluate_proactive_news_candidates", AsyncMock()) as classify:
            state, created = await process_news(news_task(), FakeNews(self.items))
        self.assertEqual([], created)
        self.assertTrue(state["fingerprints"])
        classify.assert_not_awaited()

    async def test_twenty_first_run_candidates_all_remain_deduped(self):
        many = {"澎湃新闻": [], "财联社": []}
        candidates = [{
            "title": f"重大事件{index}", "url": f"https://news.example/{index}",
            "sources": ["澎湃新闻"], "primary_source": "澎湃新闻", "position": index,
            "facts": f"事实{index}", "cluster_id": f"{index:040x}", "score": 100 - index,
        } for index in range(20)]
        with patch("core.proactive_monitor.runner.prefilter_news", return_value=candidates), patch(
            "core.proactive_monitor.runner.evaluate_proactive_news_candidates", AsyncMock()
        ) as classify:
            state, _ = await process_news(news_task(), FakeNews(many))
            self.assertEqual(20, len(state["fingerprints"]))
            _, repeated = await process_news(news_task(state), FakeNews(many))
        self.assertEqual([], repeated)
        classify.assert_not_awaited()

    async def test_confidence_boundary_creates_one_and_24h_dedupes(self):
        task = news_task({"schema_version": 1, "fingerprints": [], "detection_status": {}})
        output = {"items": [{
            "index": 0, "is_major": True, "category": "public_safety",
            "severity": "high", "confidence": 0.85, "spoken_summary": "某地发生强烈地震。",
            "facts": ["当地已启动紧急响应"],
        }]}
        create_result = {
            "created": True, "deduped": False,
            "dedupe_recorded_at": "2026-08-09 12:00:00",
            "authoritative_event_id": "event", "event": {"event_id": "event"},
        }
        with patch("core.proactive_monitor.runner.evaluate_proactive_news_candidates", AsyncMock(return_value=output)), patch(
            "core.proactive_monitor.runner.create_proactive_monitor_event",
            AsyncMock(return_value=create_result),
        ) as create:
            result = await process_news(task, FakeNews(self.items))
            state, created = result
            self.assertEqual(1, len(created))
            self.assertEqual("https://news.example/a", create.await_args.args[0]["payload"]["reference_url"])
            self.assertEqual("rolling_window", create.await_args.args[0]["dedupe_policy"])
            self.assertEqual(24, create.await_args.args[0]["dedupe_window_hours"])
            task["state"] = state
            _, repeated = await process_news(task, FakeNews(self.items))
        self.assertEqual([], repeated)
        self.assertEqual(1, create.await_count)
        self.assertEqual(
            ("notification_created", "major_news_public_safety_high_created"),
            (result.decision["outcome"], result.decision["reason"]),
        )
        self.assertEqual("created", result.decision["details"]["persistence"])

    async def test_same_named_typhoon_updates_are_deduped_for_24_hours(self):
        first_items = {
            "澎湃新闻": [{
                "title": "中央气象台发布台风橙色预警 强台风白海豚逼近浙闽沿海",
                "url": "https://news.example/typhoon-warning",
            }],
            "财联社": [{
                "title": "中央气象台发布台风橙色预警 强台风白海豚逼近浙闽沿海",
                "url": "https://news.example/typhoon-warning",
            }],
        }
        landed_items = {
            "澎湃新闻": [{
                "title": "台风白海豚在浙江玉环沿海登陆",
                "url": "https://news.example/typhoon-landfall",
            }],
            "财联社": [{
                "title": "台风白海豚在浙江玉环沿海登陆",
                "url": "https://news.example/typhoon-landfall",
            }],
        }
        alert_items = {
            "澎湃新闻": [{
                "title": "中央气象台升级发布台风红色预警 六省份有大暴雨",
                "description": "台风白海豚登陆后继续带来强风暴雨",
                "url": "https://news.example/typhoon-red-alert",
            }],
            "财联社": [{
                "title": "中央气象台升级发布台风红色预警 六省份有大暴雨",
                "description": "台风白海豚登陆后继续带来强风暴雨",
                "url": "https://news.example/typhoon-red-alert",
            }],
        }
        verdict = {"items": [{
            "index": 0, "is_major": True, "category": "natural_disaster",
            "severity": "critical", "confidence": 0.95,
            "spoken_summary": "台风白海豚带来重大影响。",
            "facts": ["台风白海豚已登陆"],
        }]}
        task = news_task({"schema_version": 1, "fingerprints": [], "detection_status": {}})
        task["config"]["categories"] = ["natural_disaster"]
        task["config"]["dedupe_hours"] = 1
        create_result = {
            "created": True, "deduped": False,
            "dedupe_recorded_at": "2026-08-09 12:00:00",
            "authoritative_event_id": "event", "event": {"event_id": "event"},
        }
        first_time = datetime(2026, 8, 9, 4, 0, tzinfo=timezone.utc)
        landed_time = first_time + timedelta(hours=3)
        alert_time = first_time + timedelta(hours=6)
        with patch(
            "core.proactive_monitor.runner.evaluate_proactive_news_candidates",
            AsyncMock(side_effect=[verdict, verdict, verdict]),
        ), patch(
            "core.proactive_monitor.runner.create_proactive_monitor_event",
            AsyncMock(return_value=create_result),
        ) as create, patch(
            "core.proactive_monitor.runner._utc_now",
            side_effect=[
                first_time, first_time,
                landed_time, landed_time,
                alert_time, alert_time,
            ],
        ):
            first = await process_news(task, FakeNews(first_items))
            task["state"] = first.state
            second = await process_news(task, FakeNews(landed_items))
            task["state"] = second.state
            third = await process_news(task, FakeNews(alert_items))

        self.assertEqual(1, create.await_count)
        self.assertEqual([], second.created_event_ids)
        self.assertEqual("same_incident_recently_notified", second.decision["reason"])
        self.assertEqual([], third.created_event_ids)
        self.assertEqual("same_incident_recently_notified", third.decision["reason"])
        self.assertTrue(
            create.await_args.args[0]["dedupe_key"].startswith("news-incident:")
        )

    def test_named_typhoon_incident_is_stable_across_title_descriptions(self):
        verdict = {"category": "natural_disaster"}
        first = {
            "cluster_id": "a" * 40,
            "title": "台风白海豚外围云系影响浙江",
            "facts": "",
        }
        second = {
            "cluster_id": "b" * 40,
            "title": "台风白海豚中心已进入浙江",
            "facts": "",
        }

        self.assertEqual(
            _news_incident_id(first, verdict),
            _news_incident_id(second, verdict),
        )

    def test_generic_typhoon_description_does_not_invent_a_name(self):
        candidate = {
            "cluster_id": "c" * 40,
            "title": "强台风登陆广东",
            "facts": "",
        }

        self.assertEqual(
            candidate["cluster_id"],
            _news_incident_id(candidate, {"category": "natural_disaster"}),
        )

    def test_typhoon_incident_identity_ignores_changing_source_facts(self):
        verdict = {"category": "natural_disaster"}
        candidate = {
            "cluster_id": "d" * 40,
            "title": "台风白海豚登陆浙江",
            "facts": "台风白海豚已登陆浙江",
        }
        first = _news_incident_id(candidate, verdict)
        candidate["facts"] = "历史台风海燕造成严重损失"

        self.assertEqual(first, _news_incident_id(candidate, verdict))

    def test_unnamed_typhoon_alert_uses_unique_name_from_source_description(self):
        verdict = {"category": "natural_disaster"}
        named = {
            "cluster_id": "e" * 40,
            "title": "台风“白海豚”在浙江玉环沿海登陆",
            "facts": "台风白海豚已经登陆浙江",
        }
        unnamed = {
            "cluster_id": "f" * 40,
            "title": "中央气象台升级发布台风红色预警 六省份有大暴雨",
            "source_descriptions": ["台风白海豚登陆后继续带来强风暴雨"],
        }

        self.assertEqual(
            _news_incident_id(named, verdict),
            _news_incident_id(unnamed, verdict),
        )

    def test_ambiguous_source_description_does_not_choose_historical_typhoon(self):
        candidate = {
            "cluster_id": "1" * 40,
            "title": "中央气象台升级发布台风红色预警",
            "source_descriptions": ["台风白海豚正在影响浙江，历史台风海燕造成严重损失"],
        }

        self.assertEqual(
            candidate["cluster_id"],
            _news_incident_id(candidate, {"category": "natural_disaster"}),
        )

    def test_generic_typhoon_words_are_not_treated_as_names(self):
        verdict = {"category": "natural_disaster"}
        for index, title in enumerate((
            "中央气象台发布台风消息",
            "沿海地区关注台风动态",
            "应急部门发布台风防御指南",
        )):
            with self.subTest(title=title):
                candidate = {"cluster_id": str(index + 2) * 40, "title": title}
                self.assertEqual(
                    candidate["cluster_id"], _news_incident_id(candidate, verdict)
                )

    def test_named_typhoon_progress_suffixes_keep_the_exact_name(self):
        verdict = {"category": "natural_disaster"}
        baseline = {
            "cluster_id": "6" * 40,
            "title": "台风白海豚登陆浙江",
        }
        expected = _news_incident_id(baseline, verdict)
        for index, title in enumerate((
            "台风白海豚最新消息",
            "台风白海豚正在影响浙江",
            "台风白海豚增强为强台风",
            "台风白海豚袭击沿海",
            "台风白海豚移入东海",
        )):
            with self.subTest(title=title):
                candidate = {"cluster_id": str(index + 7) * 40, "title": title}
                self.assertEqual(expected, _news_incident_id(candidate, verdict))

    def test_source_description_comparison_fails_closed(self):
        candidate = {
            "cluster_id": "d" * 40,
            "title": "中央气象台升级发布台风红色预警",
            "source_descriptions": [
                "台风白海豚正在影响浙江，与山竹相比强度更高"
            ],
        }

        self.assertEqual(
            candidate["cluster_id"],
            _news_incident_id(candidate, {"category": "natural_disaster"}),
        )

    def test_prefilter_aggregates_all_source_descriptions_for_incident_identity(self):
        items = {
            "澎湃新闻": [{
                "title": "中央气象台升级发布台风红色预警",
                "url": "https://news.example/alert-a",
            }],
            "财联社": [{
                "title": "中央气象台升级发布台风红色预警",
                "description": "台风白海豚正在影响浙江",
                "url": "https://news.example/alert-b",
            }],
        }

        candidate = prefilter_news(items)[0]
        named = {
            "cluster_id": "e" * 40,
            "title": "台风白海豚登陆浙江",
        }

        self.assertEqual(
            _news_incident_id(named, {"category": "natural_disaster"}),
            _news_incident_id(candidate, {"category": "natural_disaster"}),
        )

    def test_conflicting_source_descriptions_fail_closed(self):
        candidate = {
            "cluster_id": "f" * 40,
            "title": "中央气象台升级发布台风红色预警",
            "source_descriptions": [
                "台风白海豚正在影响浙江",
                "台风海燕正在影响广东",
            ],
        }

        self.assertEqual(
            candidate["cluster_id"],
            _news_incident_id(candidate, {"category": "natural_disaster"}),
        )

    def test_news_state_keeps_incident_when_cluster_partition_is_full(self):
        incident = f"news-incident:{'a' * 40}:1786248000"
        fingerprints = [incident] + [
            f"news:{index:040x}:1786248000" for index in range(47)
        ]

        updated = _append_news_fingerprint(
            fingerprints, f"news:{48:040x}:1786248000"
        )

        self.assertEqual(48, len(updated))
        self.assertIn(incident, updated)

    def test_news_state_builder_trims_near_4kb_state_without_losing_incident(self):
        incident = f"news-incident:{'b' * 40}:1786248000"
        long_values = [f"legacy-{index:02d}-" + "x" * 150 for index in range(24)]
        fingerprints = _append_news_fingerprint(
            [incident] + long_values,
            f"news:{'c' * 40}:1786248000",
        )

        state = _build_news_state(fingerprints, {})

        encoded = json.dumps(
            state, ensure_ascii=False, separators=(",", ":")
        ).encode("utf-8")
        self.assertLessEqual(len(encoded), 4096)
        self.assertIn(incident, state["fingerprints"])

    async def test_recent_incident_does_not_starve_an_unrelated_major_event(self):
        now = datetime(2026, 8, 9, 8, 0, tzinfo=timezone.utc)
        typhoon = {
            "title": "台风白海豚在浙江玉环沿海登陆",
            "url": "https://news.example/typhoon", "sources": ["澎湃新闻"],
            "primary_source": "澎湃新闻", "position": 0,
            "facts": "台风白海豚已登陆", "cluster_id": "a" * 40, "score": 100,
        }
        policy = {
            "title": "国务院发布重大公共安全新政策",
            "url": "https://news.example/policy", "sources": ["澎湃新闻"],
            "primary_source": "澎湃新闻", "position": 1,
            "facts": "新政策正式发布", "cluster_id": "b" * 40, "score": 90,
        }
        typhoon_verdict = {
            "index": 0, "is_major": True, "category": "natural_disaster",
            "severity": "critical", "confidence": 0.99,
            "spoken_summary": "台风白海豚已登陆。", "facts": ["已登陆"],
        }
        policy_verdict = {
            "index": 1, "is_major": True, "category": "major_policy",
            "severity": "high", "confidence": 0.95,
            "spoken_summary": "重大公共安全新政策发布。", "facts": ["正式发布"],
        }
        incident_id = _news_incident_id(typhoon, typhoon_verdict)
        state = {
            "schema_version": 1,
            "fingerprints": [f"news-incident:{incident_id}:{int(now.timestamp())}"],
            "detection_status": {},
        }
        task = news_task(state)
        task["config"]["categories"] = ["natural_disaster", "major_policy"]
        create_result = {
            "created": True, "deduped": False,
            "authoritative_event_id": "policy", "event": {"event_id": "policy"},
        }
        with patch(
            "core.proactive_monitor.runner.prefilter_news",
            return_value=[typhoon, policy],
        ), patch(
            "core.proactive_monitor.runner.evaluate_proactive_news_candidates",
            AsyncMock(return_value={"items": [typhoon_verdict, policy_verdict]}),
        ), patch(
            "core.proactive_monitor.runner.create_proactive_monitor_event",
            AsyncMock(return_value=create_result),
        ) as create, patch(
            "core.proactive_monitor.runner._utc_now", return_value=now,
        ):
            result = await process_news(task, FakeNews({"澎湃新闻": [], "财联社": []}))

        self.assertEqual(1, len(result.created_event_ids))
        self.assertEqual("国务院发布重大公共安全新政策", create.await_args.args[0]["payload"]["title"])

    async def test_classifier_rejection_logs_each_concrete_reason_without_content(self):
        task = news_task({"schema_version": 1, "fingerprints": [], "detection_status": {}})
        output = {"items": [{
            "index": 0, "is_major": False, "category": "major_policy",
            "severity": "low", "confidence": 0.4,
            "spoken_summary": "不得写入日志的摘要", "facts": ["不得写入日志的事实"],
        }]}
        with patch(
            "core.proactive_monitor.runner.evaluate_proactive_news_candidates",
            AsyncMock(return_value=output),
        ):
            result = await process_news(task, FakeNews(self.items))
            _, created = result
        self.assertEqual([], created)
        self.assertEqual(
            ("no_notification", "classifier_rejected_all"),
            (result.decision["outcome"], result.decision["reason"]),
        )
        self.assertEqual({
            "not_major": 1,
            "severity_below_high": 1,
            "confidence_below_threshold": 1,
            "category_disabled": 1,
        }, result.decision["details"]["rejection_counts"])
        self.assertNotIn("不得写入日志", str(result.decision))

    async def test_authoritative_api_dedupe_has_distinct_outcome_and_reason(self):
        task = news_task({"schema_version": 1, "fingerprints": [], "detection_status": {}})
        output = {"items": [{
            "index": 0, "is_major": True, "category": "public_safety",
            "severity": "high", "confidence": 0.9,
            "spoken_summary": "重大事件摘要", "facts": ["已启动响应"],
        }]}
        create_result = {
            "created": False, "deduped": True,
            "authoritative_event_id": "existing", "event": {"event_id": "existing"},
        }
        with patch(
            "core.proactive_monitor.runner.evaluate_proactive_news_candidates",
            AsyncMock(return_value=output),
        ), patch(
            "core.proactive_monitor.runner.create_proactive_monitor_event",
            AsyncMock(return_value=create_result),
        ):
            result = await process_news(task, FakeNews(self.items))
        self.assertEqual("notification_deduped", result.decision["outcome"])
        self.assertEqual(
            "major_news_public_safety_high_deduped", result.decision["reason"]
        )
        self.assertEqual("deduped", result.decision["details"]["persistence"])

    async def test_authoritative_dedupe_restores_incident_at_original_created_time(self):
        now = datetime(2026, 8, 10, 0, 30, tzinfo=timezone.utc)
        task = news_task({"schema_version": 1, "fingerprints": [], "detection_status": {}})
        task["config"]["categories"] = ["natural_disaster"]
        items = {"澎湃新闻": [{
            "title": "台风白海豚登陆浙江",
            "url": "https://news.example/typhoon",
        }]}
        output = {"items": [{
            "index": 0, "is_major": True, "category": "natural_disaster",
            "severity": "critical", "confidence": 0.95,
            "spoken_summary": "台风白海豚已经登陆。", "facts": ["已经登陆"],
        }]}
        create_result = {
            "created": False, "deduped": True,
            "authoritative_event_id": "existing",
            "dedupe_recorded_at": "2026-08-09 08:59:00",
            "event": {
                "event_id": "existing",
                "created_at": "2026-08-09 08:00:00",
            },
        }
        with patch(
            "core.proactive_monitor.runner.evaluate_proactive_news_candidates",
            AsyncMock(return_value=output),
        ), patch(
            "core.proactive_monitor.runner.create_proactive_monitor_event",
            AsyncMock(return_value=create_result),
        ), patch("core.proactive_monitor.runner._utc_now", return_value=now):
            result = await process_news(task, FakeNews(items))

        expected_timestamp = int(datetime(
            2026, 8, 9, 0, 59, tzinfo=timezone.utc
        ).timestamp())
        incident_fingerprints = [
            item for item in result.state["fingerprints"]
            if item.startswith("news-incident:")
        ]
        self.assertEqual(1, len(incident_fingerprints))
        self.assertTrue(incident_fingerprints[0].endswith(f":{expected_timestamp}"))

    async def test_named_incident_rejects_dedupe_without_ledger_time(self):
        task = news_task({"schema_version": 1, "fingerprints": [], "detection_status": {}})
        task["config"]["categories"] = ["natural_disaster"]
        output = {"items": [{
            "index": 0, "is_major": True, "category": "natural_disaster",
            "severity": "critical", "confidence": 0.95,
            "spoken_summary": "台风白海豚已经登陆。", "facts": ["已经登陆"],
        }]}
        create_result = {
            "created": False, "deduped": True,
            "authoritative_event_id": "existing", "event": {"event_id": "existing"},
        }
        with patch(
            "core.proactive_monitor.runner.evaluate_proactive_news_candidates",
            AsyncMock(return_value=output),
        ), patch(
            "core.proactive_monitor.runner.create_proactive_monitor_event",
            AsyncMock(return_value=create_result),
        ):
            with self.assertRaises(MonitorError) as raised:
                await process_news(task, FakeNews({"澎湃新闻": [{
                    "title": "台风白海豚登陆浙江",
                    "url": "https://news.example/typhoon",
                }]}))

        self.assertEqual("news_dedupe_recorded_at_invalid", raised.exception.code)

    async def test_news_persistence_contract_failure_has_specific_reason(self):
        task = news_task({"schema_version": 1, "fingerprints": [], "detection_status": {}})
        output = {"items": [{
            "index": 0, "is_major": True, "category": "public_safety",
            "severity": "high", "confidence": 0.95,
            "spoken_summary": "重大事件。", "facts": ["已启动响应"],
        }]}
        contract_error = ManageApiBusinessError("manager-api外界事件创建响应格式错误")
        with patch(
            "core.proactive_monitor.runner.evaluate_proactive_news_candidates",
            AsyncMock(return_value=output),
        ), patch(
            "core.proactive_monitor.runner.create_proactive_monitor_event",
            AsyncMock(side_effect=contract_error),
        ), patch(
            "core.proactive_monitor.runner.get_proactive_monitor_event",
            AsyncMock(side_effect=ManageApiBusinessError("事件不存在")),
        ):
            with self.assertRaises(MonitorError) as raised:
                await process_news(task, FakeNews(self.items))

        self.assertEqual(
            "news_event_persistence_contract_invalid", raised.exception.code
        )

    def test_authoritative_dedupe_parses_manager_ledger_time(self):
        created_at = _authoritative_dedupe_recorded_at({
            "dedupe_recorded_at": "2026-08-09 11:59:00",
        })

        self.assertEqual(
            datetime(2026, 8, 9, 3, 59, tzinfo=timezone.utc), created_at
        )

    def test_decision_log_hashes_device_and_does_not_log_raw_identifier(self):
        with patch("core.proactive_monitor.runner.logger") as safe_logger:
            _log_monitor_decision(
                {"device_id": "private-device-id"},
                "news", "no_notification", "prefilter_rejected_all",
                fetched_item_count=3,
            )
        logged = safe_logger.bind.return_value.info.call_args.args
        self.assertNotIn("private-device-id", str(logged))
        self.assertIn("device_hash", logged[0])

    async def test_classifier_failure_is_explicit_and_never_creates_event(self):
        task = news_task({"schema_version": 1, "fingerprints": [], "detection_status": {}})
        with patch(
            "core.proactive_monitor.runner.evaluate_proactive_news_candidates",
            AsyncMock(side_effect=ManageApiTimeoutError("timeout")),
        ), patch("core.proactive_monitor.runner.create_proactive_monitor_event", AsyncMock()) as create:
            with self.assertRaisesRegex(RuntimeError, "新闻分类模型调用失败"):
                await process_news(task, FakeNews(self.items))
        create.assert_not_awaited()

    async def test_event_retry_is_byte_stable_when_monitor_completion_was_lost(self):
        task = news_task({"schema_version": 1, "fingerprints": [], "detection_status": {}})
        output = {"items": [{
            "index": 0, "is_major": True, "category": "public_safety",
            "severity": "high", "confidence": 0.9, "spoken_summary": "重大事件摘要",
            "facts": ["已启动响应"],
        }]}
        with patch(
            "core.proactive_monitor.runner.evaluate_proactive_news_candidates",
            AsyncMock(return_value=output),
        ), patch("core.proactive_monitor.runner.create_proactive_monitor_event", AsyncMock()) as create:
            await process_news(task, FakeNews(self.items))
            first = create.await_args.args[0]
            await process_news(task, FakeNews(self.items))
            second = create.await_args.args[0]
        self.assertEqual(first, second)

    async def test_event_retry_accepts_authoritative_existing_when_model_output_changes(self):
        task = news_task({"schema_version": 1, "fingerprints": [], "detection_status": {}})
        first_output = {"items": [{
            "index": 0, "is_major": True, "category": "public_safety",
            "severity": "high", "confidence": 0.9, "spoken_summary": "首次摘要",
            "facts": ["首次事实"],
        }]}
        second_output = {"items": [{
            **first_output["items"][0], "severity": "critical",
            "spoken_summary": "变化后的摘要", "facts": ["变化后的事实"],
        }]}
        create = AsyncMock()
        with patch(
            "core.proactive_monitor.runner.evaluate_proactive_news_candidates",
            AsyncMock(side_effect=[first_output, second_output]),
        ), patch("core.proactive_monitor.runner.create_proactive_monitor_event", create), patch(
            "core.proactive_monitor.runner.get_proactive_monitor_event", AsyncMock()
        ) as get_existing:
            await process_news(task, FakeNews(self.items))
            first = create.await_args.args[0]
            create.side_effect = __import__("httpx").RemoteProtocolError("response lost")
            get_existing.return_value = {
                "event_id": first["event_id"], "mac_address": first["mac_address"],
                "topic": first["topic"], "event_type": first["event_type"],
                "dedupe_key": first["dedupe_key"], "payload": first["payload"],
            }
            result = await process_news(task, FakeNews(self.items))
            _, created = result
        self.assertEqual([first["event_id"]], created)
        get_existing.assert_awaited_once()
        self.assertEqual("notification_deduped", result.decision["outcome"])
        self.assertTrue(result.decision["reason"].endswith("_deduped"))
        self.assertEqual("authoritative_existing", result.decision["details"]["persistence"])

    def test_classifier_contract_rejects_invalid_and_accepts_exact_boundary(self):
        valid = {"items": [{
            "index": 0, "is_major": True, "category": "public_safety",
            "severity": "high", "confidence": 0.85, "spoken_summary": "摘要", "facts": ["事实"],
        }]}
        self.assertEqual(valid, _validate_classifier_output(json.dumps(valid), 1))
        invalid = {"items": [{**valid["items"][0], "reasoning": "hidden"}]}
        with self.assertRaises(ManageApiBusinessError):
            _validate_classifier_output(json.dumps(invalid), 1)


class RunnerTest(unittest.IsolatedAsyncioTestCase):
    async def test_runner_claims_once_and_completes_failure_explicitly(self):
        runner = ExternalMonitorRunner({"plugins": {}}, poll_seconds=0.01)
        task = {
            "device_id": "d", "monitor_type": "bad", "state": {},
            "lease_owner": runner.lease_owner, "lease_token": "t",
        }
        with patch("core.proactive_monitor.runner.claim_proactive_monitor_tasks", AsyncMock(return_value=[task])), patch(
            "core.proactive_monitor.runner.complete_proactive_monitor_task", AsyncMock()
        ) as complete:
            self.assertEqual(1, await runner.run_once())
        self.assertFalse(complete.await_args.kwargs["success"])
        self.assertEqual("monitor_type_invalid", complete.await_args.kwargs["error_code"])

    async def test_empty_authoritative_claim_pauses_offline_devices(self):
        runner = ExternalMonitorRunner({"plugins": {}})
        with patch(
            "core.proactive_monitor.runner.claim_proactive_monitor_tasks",
            AsyncMock(return_value=[]),
        ) as claim:
            self.assertEqual(0, await runner.run_once())
        claim.assert_awaited_once_with(runner.lease_owner, runner.limit)

    async def test_retryable_source_error_uses_bounded_backoff_then_completes(self):
        runner = ExternalMonitorRunner({"plugins": {}})
        task = {
            "device_id": "d", "monitor_type": "weather", "state": {},
            "lease_owner": runner.lease_owner, "lease_token": "t",
        }
        retry = MonitorError("weather_timeout", "timeout", retryable=True)
        with patch(
            "core.proactive_monitor.runner.claim_proactive_monitor_tasks",
            AsyncMock(return_value=[task]),
        ), patch(
            "core.proactive_monitor.runner.process_weather",
            AsyncMock(side_effect=[
                retry,
                retry,
                MonitorProcessResult(
                    {"schema_version": 1}, [], "no_notification", "no_active_risk"
                ),
            ]),
        ) as process, patch(
            "core.proactive_monitor.runner.complete_proactive_monitor_task", AsyncMock()
        ) as complete, patch("core.proactive_monitor.runner.asyncio.sleep", AsyncMock()) as sleep:
            await runner.run_once()
        self.assertEqual(3, process.await_count)
        self.assertEqual([0.25, 0.5], [call.args[0] for call in sleep.await_args_list])
        self.assertTrue(complete.await_args.kwargs["success"])

    async def test_success_completion_failure_logs_one_specific_final_decision(self):
        runner = ExternalMonitorRunner({"plugins": {}})
        task = {
            "device_id": "d", "monitor_type": "weather", "state": {},
            "lease_owner": runner.lease_owner, "lease_token": "t",
        }
        result = MonitorProcessResult(
            {"schema_version": 1}, [], "no_notification", "no_active_risk"
        )
        complete = AsyncMock(side_effect=[RuntimeError("write failed"), None])
        with patch(
            "core.proactive_monitor.runner.claim_proactive_monitor_tasks",
            AsyncMock(return_value=[task]),
        ), patch(
            "core.proactive_monitor.runner.process_weather", AsyncMock(return_value=result),
        ), patch(
            "core.proactive_monitor.runner.complete_proactive_monitor_task", complete,
        ), patch("core.proactive_monitor.runner._log_monitor_decision") as decision:
            await runner.run_once()
        decision.assert_called_once()
        self.assertEqual("monitor_failed", decision.call_args.args[2])
        self.assertEqual("monitor_completion_failed", decision.call_args.args[3])
        self.assertEqual("no_active_risk", decision.call_args.kwargs["planned_reason"])
        self.assertTrue(decision.call_args.kwargs["completion_recorded"])

    async def test_runner_start_and_stop_cancel_background_loop_cleanly(self):
        runner = ExternalMonitorRunner({"plugins": {}}, poll_seconds=30)
        with patch(
            "core.proactive_monitor.runner.claim_proactive_monitor_tasks",
            AsyncMock(return_value=[]),
        ):
            await runner.start()
            await asyncio.sleep(0)
            await runner.stop()
        self.assertIsNone(runner._task)


class ManagerClientContractTest(unittest.IsolatedAsyncioTestCase):
    async def test_monitor_endpoints_use_actual_config_contract(self):
        task = {
            "device_id": "d", "monitor_type": "weather", "lease_owner": "worker",
            "lease_token": "token",
        }
        with patch.object(
            manage_api_client, "_execute_proactive_request", AsyncMock(side_effect=[[], None])
        ) as request:
            claimed = await manage_api_client.claim_proactive_monitor_tasks("worker", 20)
            await manage_api_client.complete_proactive_monitor_task(
                task, success=False, state={}, error_code="weather_timeout"
            )
        self.assertEqual([], claimed)
        self.assertEqual("/config/proactive/monitors/claim", request.await_args_list[0].args[1])
        self.assertEqual("/config/proactive/monitors/complete", request.await_args_list[1].args[1])
        self.assertEqual("token", request.await_args_list[1].kwargs["json"]["lease_token"])

    async def test_monitor_event_query_does_not_accept_device_payload(self):
        with patch.object(
            manage_api_client, "_execute_proactive_request", AsyncMock(return_value={"event_id": "e"})
        ) as request:
            result = await manage_api_client.get_proactive_monitor_event("e", "AA:BB")
        self.assertEqual("e", result["event_id"])
        endpoint = request.await_args.args[1]
        self.assertEqual("/config/proactive/monitor-events/e?mac_address=AA%3ABB", endpoint)

    async def test_monitor_event_create_uses_authoritative_dedupe_endpoint(self):
        response = {
            "created": False, "deduped": True,
            "dedupe_recorded_at": "2026-08-09 11:59:00",
            "authoritative_event_id": "existing", "event": {"event_id": "existing"},
        }
        with patch.object(
            manage_api_client, "_execute_proactive_request", AsyncMock(return_value=response)
        ) as request:
            result = await manage_api_client.create_proactive_monitor_event({"event_id": "new"})
        self.assertTrue(result["deduped"])
        self.assertEqual("/config/proactive/monitor-events", request.await_args.args[1])

    async def test_classifier_transport_failure_has_explicit_manager_error(self):
        with patch.object(
            manage_api_client, "_execute_proactive_request",
            AsyncMock(side_effect=__import__("httpx").ConnectError("offline")),
        ):
            with self.assertRaisesRegex(ManageApiError, "新闻分类请求失败"):
                await manage_api_client.evaluate_proactive_news_candidates([{
                    "title": "重大事件", "source": "澎湃新闻", "facts": "事实",
                }])


if __name__ == "__main__":
    unittest.main()
