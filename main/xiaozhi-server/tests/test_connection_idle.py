import threading
import unittest

from core.websocket_server import WebSocketServer


class ConnectionIdleTest(unittest.TestCase):
    def setUp(self):
        self.server = WebSocketServer.__new__(WebSocketServer)
        self.server._initialize_model_activity_tracking()

    def test_idle_window_waits_until_last_connection_closes(self):
        self.server.mark_model_request_started()
        completed = threading.Event()

        waiter = threading.Thread(
            target=lambda: (
                self.server.wait_for_idle_window(quiet_seconds=0.01),
                completed.set(),
            )
        )
        waiter.start()

        self.assertFalse(completed.wait(0.02))
        self.server.mark_model_request_finished()
        self.assertTrue(completed.wait(0.2))
        waiter.join(timeout=0.2)

    def test_multiple_connections_only_become_idle_after_all_close(self):
        self.server.mark_model_request_started()
        self.server.mark_model_request_started()
        self.server.mark_model_request_finished()

        self.assertFalse(self.server._model_idle.is_set())

        self.server.mark_model_request_finished()
        self.assertTrue(self.server._model_idle.is_set())

    def test_postprocessing_callbacks_are_serialized(self):
        running = 0
        max_running = 0
        state_lock = threading.Lock()

        def callback():
            nonlocal running, max_running
            with state_lock:
                running += 1
                max_running = max(max_running, running)
            threading.Event().wait(0.02)
            with state_lock:
                running -= 1

        workers = [
            threading.Thread(
                target=self.server.run_postprocessing_when_idle,
                args=(callback, 0),
            )
            for _ in range(2)
        ]
        for worker in workers:
            worker.start()
        for worker in workers:
            worker.join(timeout=0.2)

        self.assertEqual(max_running, 1)

    def test_realtime_model_request_waits_for_postprocessing_gate(self):
        callback_started = threading.Event()
        release_callback = threading.Event()
        realtime_started = threading.Event()

        def callback():
            callback_started.set()
            release_callback.wait(0.2)

        postprocess = threading.Thread(
            target=self.server.run_postprocessing_when_idle,
            args=(callback, 0),
        )
        postprocess.start()
        self.assertTrue(callback_started.wait(0.1))

        realtime = threading.Thread(
            target=lambda: (
                self.server.mark_model_request_started(),
                realtime_started.set(),
                self.server.mark_model_request_finished(),
            )
        )
        realtime.start()
        self.assertFalse(realtime_started.wait(0.02))

        release_callback.set()
        self.assertTrue(realtime_started.wait(0.2))
        postprocess.join(timeout=0.2)
        realtime.join(timeout=0.2)


if __name__ == "__main__":
    unittest.main()
